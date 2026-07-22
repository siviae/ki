package dev.ki.agent.tools

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * A deep copy of this [Prompt] with [transform] applied to every text-bearing span, preserving
 * message order and type, tool-call ids/names, non-text parts, and per-message metadata. Built
 * for `onProviderRequest` masking: return the masked copy for the wire while the persisted prompt
 * (which backs chat-memory) stays untouched — `copy` never mutates the receiver.
 *
 * Transformed: `Text.text`, `Tool.Result.output`, `Tool.Call.args`, and `Reasoning`
 * content/summary. `Tool.Call.args` **is** masked: a tool gets its secrets from its own config,
 * never from LLM-produced args, so a secret appearing there is always illegitimate — masking it is
 * safe (value replacement is JSON-safe) and closes the leak of past tool calls to the provider as
 * history. Left as-is: `Attachment` (binary), `Reasoning.encrypted` (opaque), and all ids, names,
 * and metadata. Unknown message or part types pass through unchanged.
 */
fun Prompt.mapMessages(transform: (String) -> String): Prompt =
    copy(messages = messages.map { it.mapText(transform) })

private fun Message.mapText(t: (String) -> String): Message = when (this) {
    is Message.System -> copy(parts = parts.map { it.copy(text = t(it.text)) })
    is Message.User -> copy(parts = parts.map { it.mapRequestPart(t) })
    is Message.Assistant -> copy(parts = parts.map { it.mapResponsePart(t) })
    else -> this
}

private fun MessagePart.RequestPart.mapRequestPart(t: (String) -> String): MessagePart.RequestPart = when (this) {
    is MessagePart.Text -> copy(text = t(text))
    is MessagePart.Tool.Result -> copy(output = t(output))
    else -> this // Attachment and anything else: keep as-is.
}

private fun MessagePart.ResponsePart.mapResponsePart(t: (String) -> String): MessagePart.ResponsePart = when (this) {
    is MessagePart.Text -> copy(text = t(text))
    is MessagePart.Tool.Call -> copy(args = t(args))
    is MessagePart.Reasoning -> copy(content = content.map(t), summary = summary?.map(t))
    else -> this
}
