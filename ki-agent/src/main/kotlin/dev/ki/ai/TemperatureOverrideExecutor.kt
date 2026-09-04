package dev.ki.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow

/**
 * Forces a sampling temperature on every request (`[llm].temperature` in the manifest).
 * koog's agent-built prompts carry the provider default otherwise; pi has no such knob,
 * so a caller wanting identical sampling conditions across runtimes sets this on ki's side.
 */
class TemperatureOverrideExecutor(
    private val delegate: PromptExecutor,
    private val temperature: Double,
) : PromptExecutor() {

    private fun override(prompt: Prompt): Prompt =
        prompt.copy(params = prompt.params.copy(temperature = temperature))

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        delegate.execute(override(prompt), model, tools)

    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
        delegate.executeMultipleChoices(override(prompt), model, tools)

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        delegate.executeStreaming(override(prompt), model, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override fun close() = delegate.close()
}
