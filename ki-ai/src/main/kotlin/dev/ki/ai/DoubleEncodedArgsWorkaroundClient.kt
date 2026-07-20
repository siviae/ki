package dev.ki.ai

import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Workaround for a koog 1.0.0-preview7 bug: `AbstractOpenAILLMClient.convertPromptToMessages`
 * JSON-string-encodes a tool call's arguments a second time when replaying a prior assistant
 * tool call back into request history — `MessagePart.Tool.Call.args` is already the model's raw
 * JSON arguments string, so the result is a doubly-quoted string (`"\"{...}\""`) instead of a
 * once-quoted one (`"{...}"`). Confirmed via direct request replay against the samokat LiteLLM
 * proxy: single-encoded `arguments` returns 200; the doubly-encoded shape koog actually produces
 * reproduces `litellm.InternalServerError: ... 'str' object has no attribute 'items'` for the
 * deepseek-v4-flash model group (its function-call parsing re-parses `arguments` and calls
 * `.items()` on the result, which is a plain string when double-encoded, not the args object).
 *
 * This unwraps the extra layer of quoting from the serialized request body right before it goes
 * over the wire. Drop this once koog fixes the upstream double-encode.
 */
internal class DoubleEncodedArgsWorkaroundClient(
    apiKey: String,
    settings: OpenAIClientSettings,
) : OpenAILLMClient(apiKey, settings, HttpClientFactoryResolver.resolve()) {

    override fun serializeProviderChatRequest(
        messages: List<OpenAIMessage>,
        model: LLModel,
        tools: List<OpenAITool>?,
        toolChoice: OpenAIToolChoice?,
        params: LLMParams,
        stream: Boolean,
    ): String {
        val raw = super.serializeProviderChatRequest(messages, model, tools, toolChoice, params, stream)
        return unwrapDoubleEncodedToolArgs(raw)
    }
}

/** Visible for testing — see [DoubleEncodedArgsWorkaroundClientTest]. */
internal fun unwrapDoubleEncodedToolArgs(raw: String): String {
    val root = Json.parseToJsonElement(raw).jsonObject
    val messages = root["messages"]?.jsonArray ?: return raw
    val fixed = JsonArray(messages.map { it.withFixedToolCalls() })
    return Json.encodeToString(JsonObject.serializer(), JsonObject(root + ("messages" to fixed)))
}

private fun JsonElement.withFixedToolCalls(): JsonElement {
    val message = jsonObject
    val toolCalls = message["tool_calls"]?.jsonArray ?: return this
    val fixedCalls = JsonArray(toolCalls.map { it.withFixedArguments() })
    return JsonObject(message + ("tool_calls" to fixedCalls))
}

private fun JsonElement.withFixedArguments(): JsonElement {
    val call = jsonObject
    val function = call["function"]?.jsonObject ?: return this
    val arguments = function["arguments"]?.jsonPrimitive?.contentOrNull ?: return this
    // Double-encoded: parsing once yields another JSON string, not the args object.
    val unwrapped = (runCatching { Json.parseToJsonElement(arguments) }.getOrNull() as? JsonPrimitive)
        ?.contentOrNull ?: return this
    val fixedFunction = JsonObject(function + ("arguments" to JsonPrimitive(unwrapped)))
    return JsonObject(call + ("function" to fixedFunction))
}
