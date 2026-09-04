package dev.ki.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class TemperatureOverrideExecutorTest {
    private class RecordingExecutor(var last: Prompt? = null) : PromptExecutor() {
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            last = prompt
            return Message.Assistant(MessagePart.Text("ok"), ResponseMetaInfo.Empty)
        }

        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
            listOf(execute(prompt, model, tools))

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
            last = prompt
            return flowOf()
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = ModerationResult(isHarmful = false, categories = emptyMap())

        override fun close() {}
    }

    private val model = LLModel(provider = ai.koog.prompt.llm.LLMProvider.OpenAI, id = "m", capabilities = listOf(), contextLength = 1000)

    @Test
    fun `temperature overrides the prompt params`() = runBlocking {
        val delegate = RecordingExecutor()
        val executor = TemperatureOverrideExecutor(delegate, 0.0)
        val prompt = Prompt.build("sys") { user("hi") }
        executor.execute(prompt, model, emptyList())
        assertEquals(0.0, delegate.last?.params?.temperature)
    }

    @Test
    fun `streaming path is overridden too`() = runBlocking {
        val delegate = RecordingExecutor()
        val executor = TemperatureOverrideExecutor(delegate, 0.7)
        executor.executeStreaming(Prompt.build("sys") { user("hi") }, model, emptyList())
        assertEquals(0.7, delegate.last?.params?.temperature)
    }

    @Test
    fun `temperature override composes with prompt-level params`() = runBlocking {
        val delegate = RecordingExecutor()
        val executor = TemperatureOverrideExecutor(delegate, 0.0)
        val prompt = Prompt.build("sys", params = LLMParams(temperature = 0.9)) { user("hi") }
        executor.execute(prompt, model, emptyList())
        assertEquals(0.0, delegate.last?.params?.temperature)
    }
}
