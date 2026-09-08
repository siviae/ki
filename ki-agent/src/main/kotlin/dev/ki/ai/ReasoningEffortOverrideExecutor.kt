package dev.ki.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonPrimitive

/**
 * Forces a reasoning effort on every request (`[llm].reasoning_effort` in the manifest).
 *
 * koog 1.2.0 carries a typed [ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort]
 * enum with only NONE/MINIMAL/LOW/MEDIUM/HIGH — proxies that accept richer levels ("xhigh")
 * cannot be expressed through the typed field. Instead the effort is injected through
 * [LLMParams.additionalProperties], which the OpenAI client flattens into the request JSON
 * body: `"reasoning_effort": "xhigh"` reaches the wire verbatim, and the typed field stays
 * null so no reasoning capability check fires on models that don't declare one.
 */
class ReasoningEffortOverrideExecutor(
    private val delegate: PromptExecutor,
    private val effort: String,
) : PromptExecutor() {

    private fun override(prompt: Prompt): Prompt = prompt.copy(
        params = prompt.params.copy(
            additionalProperties = (prompt.params.additionalProperties ?: emptyMap()) +
                (REASONING_EFFORT_KEY to JsonPrimitive(effort)),
        ),
    )

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        delegate.execute(override(prompt), model, tools)

    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
        delegate.executeMultipleChoices(override(prompt), model, tools)

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        delegate.executeStreaming(override(prompt), model, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override fun close() = delegate.close()

    companion object {
        /** Wire-level key the OpenAI-compatible proxies read; set through [LLMParams.additionalProperties]. */
        const val REASONING_EFFORT_KEY = "reasoning_effort"
    }
}
