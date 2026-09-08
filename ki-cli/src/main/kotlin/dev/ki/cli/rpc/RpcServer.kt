package dev.ki.cli.rpc

import dev.ki.agent.TurnImage
import dev.ki.agent.ToolCallEvent
import dev.ki.agent.ToolPhase
import dev.ki.cli.store.KiJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * The agent surface the RPC server drives. Implemented by [dev.ki.cli.KiController];
 * faked in tests so the wire protocol is testable without an LLM.
 */
/** One queued RPC turn: text + optional images (vision). */
private data class TurnMessage(val text: String, val images: List<TurnImage> = emptyList())

interface RpcAgent {
    /** Run one turn; callbacks mirror [dev.ki.agent.KiAgent.run]. Named runTurn because
     *  KiController.run already occupies this JVM signature. */
    suspend fun runTurn(
        prompt: String,
        onReasoning: ((String) -> Unit)?,
        onTool: ((ToolCallEvent) -> Unit)?,
        images: List<TurnImage> = emptyList(),
    ): String

    /** Names of the active tools (manifest allowlist, declaration order). */
    val toolNames: List<String>

    /** A tool's description for the tool-meta export (null when unknown). */
    fun toolDescription(name: String): String?

    /** Force history compression on the next turn (pi's `compact` request parity). */
    fun compactNow()

    /**
     * Queue a mid-run steer (pi parity, v2). Returns true when the text was queued for
     * injection at the active run's next safe point; false when no run is active (or the
     * agent cannot inject) — the server then treats the text as a regular next-turn
     * message (v1 fallback).
     */
    fun steerRun(text: String): Boolean

    /**
     * The conversation's current tree-path messages in pi JSON shape — the source for
     * `agent_end.messages` (telemetry reads usage/model/provider/stopReason off the last
     * assistant entry).
     */
    fun piPathMessages(): List<Map<String, Any?>>
}

/**
 * ki's RPC server (M1.4): pi's `--mode rpc` dialect over stdin/stdout newline-JSON, so the
 * RocketChat bot can spawn ki exactly where it spawns `pi --mode rpc` (M2 of the migration).
 *
 * Wire protocol (pi rpc-mode.js / rpc-client.js):
 *  - stdin requests: `{type, ...params, id}` — answered with `{type:"response", id, success, data?|error?}`
 *    on stdout; anything else on stdout is an event and reaches `RpcClient.onEvent` listeners.
 *  - events: pi's `AgentEvent` shapes — `agent_start`, `turn_start`, `message_end`, `turn_end`,
 *    `tool_execution_start`, `tool_execution_end`, `agent_end`.
 *
 * ki's `run()` is a whole-turn call (no mid-run message hooks), so the event set is
 * turn-granular: reasoning deltas and partial results are not streamed (nothing in the bot
 * or the telemetry consumes them). `agent_end.messages` is synthesized from the session
 * store's pi-shaped tree path after the turn — the same entries the M1.3 JSONL writer
 * persists, so usage/model/provider/stopReason/timestamps are already pi-native.
 *
 * Steering (v2): while a turn is mid-run, `steer` is injected at the agent's post-tool
 * safe point — appended as a user message before the next LLM call, so the RUNNING turn
 * redirects (pi parity). When no turn is active the steer falls back to the v1
 * turn-boundary queue: answered as the next turn of the SAME run — `agent_end` fires
 * only after the queue drains. The bot's timeout flow (steer at think_sec, resolve on
 * the single `agent_end`) observes identical behavior. A turn failure (LLM error) ends
 * the run like pi's does: `agent_end` carries the store's last known messages.
 */
class RpcServer(
    private val agent: RpcAgent,
    private val metaDir: Path?,
    private val output: (String) -> Unit,
) {
    private val messages = Channel<TurnMessage>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var loop: Job? = null
    @Volatile private var currentTurn: Job? = null
    @Volatile private var aborted = false

    /** Serve requests until stdin EOF (pi's bot kills the process with SIGTERM to stop). */
    fun serve(input: BufferedReader) {
        exportToolMeta()
        ensureLoop()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val request: Map<String, Any?> = runCatching { KiJson.readMap(line) }.getOrDefault(emptyMap())
            if (request.isEmpty()) continue
            handle(request)
        }
        // stdin closed (parent died or stop()): let the active turn finish, then exit.
        messages.close()
        runBlocking { loop?.join() }
    }

    private fun handle(request: Map<String, Any?>) {
        val id = request["id"]
        when (request["type"]) {
            "prompt", "follow_up" -> {
                respond(id, success = true)
                submit(request["message"] as? String ?: "", parseImages(request))
            }
            "steer" -> {
                respond(id, success = true)
                val text = request["message"] as? String ?: ""
                // v2: mid-run injection when a turn is active; v1 fallback queues it as
                // the next turn of the same run (agent_end still fires only after drain).
                if (!agent.steerRun(text)) submit(text)
            }
            "abort" -> {
                respond(id, success = true)
                aborted = true
                while (messages.tryReceive().isSuccess) { /* drop queued */ }
                currentTurn?.cancel()
            }
            "compact" -> {
                // pi answers with a CompactionResult synchronously; ki v1 flags the next
                // turn — the compaction (koog M6) happens at its start and lands in the
                // session file as a pi compaction entry. Turn-boundary difference only.
                respond(id, success = true, data = mapOf("pending" to true))
                agent.compactNow()
            }
            "stop", "shutdown" -> {
                respond(id, success = true)
                exitProcess(0)
            }
            else -> respond(id, success = false, error = "Unknown command: ${request["type"]}")
        }
    }

    /** RPC `images` entries: [{mime, data(base64)}]; malformed entries are dropped. */
    private fun parseImages(request: Map<String, Any?>): List<TurnImage> =
        (request["images"] as? List<*>).orEmpty().mapNotNull { item ->
            (item as? Map<*, *>)?.let { m ->
                val data = m["data"] as? String ?: return@mapNotNull null
                val mime = (m["mime"] as? String) ?: "image/png"
                TurnImage(data, mime)
            }
        }

    private fun submit(message: String, images: List<TurnImage> = emptyList()) {
        messages.trySend(TurnMessage(message, images))
        ensureLoop()
    }

    private fun ensureLoop() {
        synchronized(lock) { if (loop?.isActive != true) loop = scope.launch { runLoop() } }
    }

    /**
     * One pi "run": `agent_start` when the queue wakes the loop, turns until the queue
     * drains, then `agent_end`. Steering/queued prompts keep the run alive; an abort ends
     * it immediately (queue already dropped).
     */
    private suspend fun runLoop() {
        var active = false
        for (message in messages) {
            if (!active) {
                emit(mapOf("type" to "agent_start"))
                active = true
            }
            aborted = false
            val turn = scope.launch { doTurn(message.text, message.images) }
            currentTurn = turn
            turn.join()
            // Drained = nothing receivable: an open channel with an empty buffer, or a
            // closed one (kotlinx keeps isEmpty=false for a closed channel even after
            // its buffer is fully consumed).
            if (aborted || messages.isEmpty || messages.isClosedForReceive) {
                val messages = agent.piPathMessages().toMutableList()
                if (aborted) {
                    // pi's abort stores the interrupted (partial) assistant and emits its
                    // message_end with stopReason "aborted". ki's cancel drops the turn
                    // before anything is stored, so synthesize an aborted assistant into
                    // the event payload (session file stays untouched) — telemetry sees
                    // the same shape on both runtimes. model/provider/usage ride over
                    // from the last stored assistant (same session, same model).
                    val lastAssistant: Map<String, Any?> =
                        messages.lastOrNull { it["role"] == "assistant" } ?: emptyMap()
                    val abortedAssistant = LinkedHashMap(synthesizedAssistant("", stopReason = "aborted"))
                    for (key in listOf("model", "provider", "usage")) {
                        lastAssistant[key]?.let { abortedAssistant[key] = it }
                    }
                    messages.add(abortedAssistant)
                    emit(mapOf("type" to "message_end", "message" to abortedAssistant))
                }
                emit(mapOf("type" to "agent_end", "stopReason" to if (aborted) "aborted" else "stop", "messages" to messages))
                active = false
            }
        }
    }

    private suspend fun doTurn(message: String, images: List<TurnImage> = emptyList()) {
        emit(mapOf("type" to "turn_start"))
        // v1: no reasoning-delta streaming — nothing downstream consumes the deltas.
        val finalText = agent.runTurn(
            message,
            onReasoning = null,
            onTool = { event -> emitToolEvents(event) },
            images = images,
        )
        val assistant = agent.piPathMessages().lastOrNull { it["role"] == "assistant" }
        // message_end carries the stored assistant entry: telemetry reads usage/model/
        // provider/thinking+text blocks straight off it.
        assistant?.let { emit(mapOf("type" to "message_end", "message" to it)) }
        emit(
            mapOf(
                "type" to "turn_end",
                "message" to (assistant ?: synthesizedAssistant(finalText)),
                "toolResults" to emptyList<Any?>(),
            ),
        )
    }

    /** Map ki's ToolCallEvent (with full args/result) onto pi's tool_execution pair. */
    private fun emitToolEvents(event: ToolCallEvent) {
        when (event.phase) {
            ToolPhase.STARTING -> emit(
                mapOf(
                    "type" to "tool_execution_start",
                    "toolCallId" to event.id,
                    "toolName" to event.name,
                    "args" to jsonToValue(event.fullArgs),
                ),
            )
            ToolPhase.OK, ToolPhase.ERROR -> emit(
                mapOf(
                    "type" to "tool_execution_end",
                    "toolCallId" to event.id,
                    "toolName" to event.name,
                    "result" to (event.fullResult?.let { jsonToValue(it) } ?: event.result ?: ""),
                    "isError" to (event.phase == ToolPhase.ERROR),
                ),
            )
        }
    }

    /** Last-assistant fallback when the store has nothing (e.g. instant failure). */
    private fun synthesizedAssistant(text: String, stopReason: String = "stop"): Map<String, Any?> = linkedMapOf(
        "role" to "assistant",
        "content" to listOf(linkedMapOf("type" to "text", "text" to text)),
        "stopReason" to stopReason,
        "timestamp" to System.currentTimeMillis(),
    )

    private fun emit(event: Map<String, Any?>) {
        output(KiJson.write(event))
    }

    private fun respond(id: Any?, success: Boolean, data: Any? = null, error: String? = null) {
        val response = linkedMapOf<String, Any?>(
            "type" to "response",
            "id" to id,
            "success" to success,
        )
        if (success) response["data"] = data ?: emptyMap<String, Any?>() else response["error"] = error
        emit(response)
    }

    /**
     * Write `.pi/extensions/tool-meta/<name>.json` for every active tool — the bot's
     * telemetry reads these on each tool's first call (tool_info event: description +
     * promptGuidelines). ki tools have no promptGuidelines yet.
     */
    private fun exportToolMeta() {
        val dir = metaDir ?: return
        runCatching {
            Files.createDirectories(dir)
            for (name in agent.toolNames) {
                val description = agent.toolDescription(name) ?: continue
                val meta = linkedMapOf<String, Any?>(
                    "description" to description,
                    "promptGuidelines" to emptyList<Any?>(),
                )
                Files.writeString(dir.resolve("$name.json"), KiJson.write(meta))
            }
        }
    }
}

/** kotlinx [JsonElement] → Jackson-friendly value tree (maps, lists, scalars). */
private fun jsonToValue(element: JsonElement): Any? = when (element) {
    is JsonObject -> element.entries.associate { (k, v) -> k to jsonToValue(v) }
    is JsonArray -> element.map { jsonToValue(it) }
    is JsonPrimitive -> element.content
}

/** koog [ai.koog.serialization.JSONElement] → the same Jackson-friendly tree. */
private fun jsonToValue(element: ai.koog.serialization.JSONElement): Any? = when (element) {
    is ai.koog.serialization.JSONObject ->
        element.entries.entries.associate { (k, v) -> k to jsonToValue(v) }
    is ai.koog.serialization.JSONArray -> element.elements.map { jsonToValue(it) }
    is ai.koog.serialization.JSONPrimitive -> element.content
    is ai.koog.serialization.JSONNull -> null
    else -> element.toString()
}

/** Parse raw JSON text into a Jackson-friendly value; falls back to the raw string. */
private fun jsonToValue(raw: String?): Any? {
    if (raw.isNullOrBlank()) return emptyMap<String, Any?>()
    return runCatching { jsonToValue(Json.parseToJsonElement(raw)) }.getOrElse { raw }
}

/** stdin/stdout entry point used by Main's `--mode rpc` branch. */
fun serveRpc(agent: RpcAgent, metaDir: Path?) {
    val server = RpcServer(agent, metaDir) { line ->
        print(line)
        print("\n")
        System.out.flush()
    }
    server.serve(BufferedReader(InputStreamReader(System.`in`)))
}
