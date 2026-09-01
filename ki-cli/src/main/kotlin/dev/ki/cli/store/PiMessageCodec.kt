package dev.ki.cli.store

import dev.ki.store.MessageCodec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.time.Instant

/**
 * koog `Message` ⇄ pi session-message (JSON-shaped as pi's `AgentMessage` union).
 *
 * This is the semantic bridge of the M1.3 pi-jsonl session store: pi JSONL files must be
 * shaped exactly like pi writes them (cross-resume both directions), while ki's storage
 * SPI deals in serialized koog messages (see [MessageCodec]).
 *
 * Mapping (ki/koog → pi):
 * - `Message.User` with `Text` part → `{"role":"user","content":[{"type":"text",...}]}`
 * - `Message.User` with `Tool.Result` part → `{"role":"toolResult",...}` (one pi message
 *   per part, in part order)
 * - `Message.Assistant` → `{"role":"assistant",...}` with `thinking`/`text`/`toolCall`
 *   content blocks; `ResponseMetaInfo` counts → `usage`, `finishReason` → `stopReason`.
 *
 * Fields koog has no native slot for (pi `usage` cost/cacheRead/reasoning, the assistant
 * `api`/`provider`, `thinkingSignature`, `responseId`) ride in `metaInfo.metadata`
 * (`pi_usage`, `thinkingSignature`, `response_id`) and are restored verbatim on the way
 * back — so a ki-written assistant entry re-saved is byte-stable.
 *
 * Timestamps: koog carries `kotlin.time.Instant`; pi messages carry epoch millis inside
 * the message and an ISO-8601 timestamp on the entry (the store owns the entry).
 */
object PiMessageCodec {
    /** Static identity fields pi puts on every assistant message. */
    data class AssistantIdentity(val provider: String, val api: String, val defaultModel: String)

    // ── koog → pi ─────────────────────────────────────────────────────────────────

    /** Convert one koog message to 1..n pi messages (Tool.Result parts split out). */
    fun toPiMessages(m: Message, identity: AssistantIdentity): List<Map<String, Any?>> = when (m) {
        is Message.User -> m.parts.flatMap { part ->
            when (part) {
                is MessagePart.Tool.Result -> listOf(
                    linkedMapOf<String, Any?>(
                        "role" to "toolResult",
                        "toolCallId" to part.id,
                        "toolName" to part.tool,
                        "content" to listOf(linkedMapOf("type" to "text", "text" to part.output)),
                        "isError" to part.isError,
                        "timestamp" to m.metaInfo.timestamp.toEpochMilliseconds(),
                    ),
                )
                is MessagePart.Text -> listOf(
                    linkedMapOf<String, Any?>(
                        "role" to "user",
                        "content" to listOf(linkedMapOf("type" to "text", "text" to part.text)),
                        "timestamp" to m.metaInfo.timestamp.toEpochMilliseconds(),
                    ),
                )
                else -> emptyList()
            }
        }

        is Message.Assistant -> listOf(assistantToPi(m, identity))
        is Message.System -> emptyList() // the system prompt lives in the .system.md sidecar
    }

    private fun assistantToPi(m: Message.Assistant, identity: AssistantIdentity): Map<String, Any?> {
        val content = m.parts.mapNotNull { part ->
            when (part) {
                is MessagePart.Text -> linkedMapOf<String, Any?>("type" to "text", "text" to part.text)
                is MessagePart.Reasoning -> {
                    val block = linkedMapOf<String, Any?>("type" to "thinking", "thinking" to part.content.joinToString(""))
                    m.metaInfo.metadata?.get("thinkingSignature")?.let { block["thinkingSignature"] = it }
                    block
                }
                is MessagePart.Tool.Call -> linkedMapOf<String, Any?>(
                    "type" to "toolCall",
                    "id" to part.id,
                    "name" to part.tool,
                    "arguments" to parseJsonObjectOrEmpty(part.args),
                )
                else -> null
            }
        }
        return linkedMapOf<String, Any?>(
            "role" to "assistant",
            "content" to content,
            "api" to (m.metaInfo.metadata.str("pi_api") ?: identity.api),
            "provider" to (m.metaInfo.metadata.str("pi_provider") ?: identity.provider),
            "model" to (m.metaInfo.modelId ?: identity.defaultModel),
            "usage" to usageToPi(m),
            "stopReason" to stopReasonToPi(m.finishReason),
            "timestamp" to m.metaInfo.timestamp.toEpochMilliseconds(),
        ).also { map ->
            m.metaInfo.metadata.str("pi_response_id")?.let { map["responseId"] = it }
            m.metaInfo.metadata.str("pi_error_message")?.let { map["errorMessage"] = it }
        }
    }

    private fun usageToPi(m: Message.Assistant): Map<String, Any?> {
        // exact pi usage captured at load time rides in metadata — restore it verbatim
        val stored = m.metaInfo.metadata.str("pi_usage")
        if (stored != null) return parseJsonObjectOrEmpty(stored)
        val input = m.metaInfo.inputTokensCount ?: 0
        val output = m.metaInfo.outputTokensCount ?: 0
        return linkedMapOf(
            "input" to input,
            "output" to output,
            "cacheRead" to 0,
            "cacheWrite" to 0,
            "totalTokens" to (m.metaInfo.totalTokensCount ?: input + output),
            "cost" to linkedMapOf("input" to 0, "output" to 0, "cacheRead" to 0, "cacheWrite" to 0, "total" to 0),
        )
    }

    private fun stopReasonToPi(finishReason: String?): String = when (finishReason) {
        null -> "aborted"
        "tool_calls" -> "toolUse"
        "stop", "length", "aborted", "error" -> finishReason
        else -> "error"
    }

    // ── pi → koog ─────────────────────────────────────────────────────────────────

    /** Convert one pi message to 0..1 koog messages (pi user/toolResult/assistant only). */
    fun toKoog(pi: Map<String, Any?>): Message? = when (pi["role"]) {
        "user" -> Message.User(parts = listOf(textPartOf(pi)), metaInfo = requestMetaOf(pi))
        "toolResult" -> Message.User(
            parts = listOf(
                MessagePart.Tool.Result(
                    id = pi["toolCallId"] as? String ?: "",
                    tool = pi["toolName"] as? String ?: "",
                    output = textContentOf(pi["content"]),
                    isError = pi["isError"] == true,
                ),
            ),
            metaInfo = requestMetaOf(pi),
        )
        "assistant" -> assistantToKoog(pi)
        else -> null
    }

    private fun assistantToKoog(pi: Map<String, Any?>): Message.Assistant {
        val blocks = (pi["content"] as? List<*>) ?: emptyList<Any?>()
        val parts = blocks.mapNotNull { block ->
            val b = block as? Map<*, *> ?: return@mapNotNull null
            when (b["type"]) {
                "thinking" -> MessagePart.Reasoning(
                    content = listOf(b["thinking"] as? String ?: ""),
                    summary = null,
                    encrypted = null,
                    id = null,
                )
                "text" -> MessagePart.Text(b["text"] as? String ?: "")
                "toolCall" -> MessagePart.Tool.Call(
                    id = b["id"] as? String ?: "",
                    tool = b["name"] as? String ?: "",
                    args = jsonToString(b["arguments"]),
                )
                else -> null
            }
        }
        val meta = buildJsonObject {
            (pi["thinkingSignature"] as? String)?.let { put("thinkingSignature", JsonPrimitive(it)) }
            (pi["responseId"] as? String)?.let { put("pi_response_id", JsonPrimitive(it)) }
            (pi["errorMessage"] as? String)?.let { put("pi_error_message", JsonPrimitive(it)) }
            (pi["api"] as? String)?.let { put("pi_api", JsonPrimitive(it)) }
            (pi["provider"] as? String)?.let { put("pi_provider", JsonPrimitive(it)) }
            (pi["usage"] as? Map<*, *>)?.let { put("pi_usage", jsonValue(it)) }
        }
        val usage = pi["usage"] as? Map<*, *>
        fun num(key: String): Int? = (usage?.get(key) as? Number)?.toInt()
        return Message.Assistant(
            parts = parts,
            metaInfo = ResponseMetaInfo(
                timestamp = Instant.fromEpochMilliseconds((pi["timestamp"] as? Number)?.toLong() ?: 0L),
                totalTokensCount = num("totalTokens"),
                inputTokensCount = num("input"),
                outputTokensCount = num("output"),
                modelId = pi["model"] as? String,
                metadata = meta,
            ),
            finishReason = stopReasonToKoog(pi["stopReason"] as? String),
            rawResponse = null,
            id = null,
        )
    }

    private fun stopReasonToKoog(stopReason: String?): String? = when (stopReason) {
        "toolUse" -> "tool_calls"
        null -> null
        else -> stopReason
    }

    private fun requestMetaOf(pi: Map<String, Any?>) = RequestMetaInfo(
        timestamp = Instant.fromEpochMilliseconds((pi["timestamp"] as? Number)?.toLong() ?: 0L),
    )

    private fun textPartOf(pi: Map<String, Any?>) = MessagePart.Text(textContentOf(pi["content"]))

    // ── helpers ─────────────────────────────────────────────────────────────────────

    /** Text of a pi content field: bare string or a block list's text blocks joined. */
    private fun textContentOf(content: Any?): String = when (content) {
        is String -> content
        is List<*> -> content.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("\n")
        else -> ""
    }

    private fun jsonToString(value: Any?): String = when (value) {
        null -> "{}"
        is String -> value
        else -> KiJson.write(value)
    }

    /** pi `usage` map → kotlinx JSON for koog metadata (recursive). */
    private fun jsonValue(value: Any?): JsonElement = when (value) {
        is Map<*, *> -> buildJsonObject {
            value.forEach { (k, v) -> put(k.toString(), jsonValue(v)) }
        }
        is List<*> -> buildJsonObject { /* lists not expected in usage; flatten defensively */
            value.forEachIndexed { i, v -> put(i.toString(), jsonValue(v)) }
        }
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        null -> JsonNull
        else -> JsonPrimitive(KiJson.write(value))
    }

    private fun parseJsonObjectOrEmpty(text: String?): Map<String, Any?> = runCatching {
        if (text.isNullOrBlank()) emptyMap() else KiJson.readMap(text)
    }.getOrDefault(emptyMap())

    /** JsonObject primitive field as String (null when absent or non-primitive). */
    private fun JsonObject?.str(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}

/** Minimal Jackson bridge used by the codec (pi side is dynamic JSON). */
internal object KiJson {
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())

    fun write(value: Any?): String = mapper.writeValueAsString(value)
    fun readMap(text: String): Map<String, Any?> =
        mapper.readValue(text, Map::class.java) as Map<String, Any?>

    fun readTree(text: String) = mapper.readTree(text)
}
