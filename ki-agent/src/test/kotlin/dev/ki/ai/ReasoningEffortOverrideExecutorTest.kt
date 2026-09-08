package dev.ki.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReasoningEffortOverrideExecutorTest {
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

    private fun effort(prompt: Prompt?): String? =
        prompt?.params?.additionalProperties?.get(ReasoningEffortOverrideExecutor.REASONING_EFFORT_KEY)?.jsonPrimitive?.content

    @Test
    fun `effort is injected into the prompt params`() = runBlocking {
        val delegate = RecordingExecutor()
        val executor = ReasoningEffortOverrideExecutor(delegate, "xhigh")
        executor.execute(Prompt.build("sys") { user("hi") }, model, emptyList())
        assertEquals("xhigh", effort(delegate.last))
    }

    @Test
    fun `streaming path is overridden too`() = runBlocking {
        val delegate = RecordingExecutor()
        val executor = ReasoningEffortOverrideExecutor(delegate, "high")
        executor.executeStreaming(Prompt.build("sys") { user("hi") }, model, emptyList())
        assertEquals("high", effort(delegate.last))
    }

    @Test
    fun `override composes with prompt-level additional properties`() = runBlocking {
        val delegate = RecordingExecutor()
        val executor = ReasoningEffortOverrideExecutor(delegate, "xhigh")
        val prompt = Prompt.build("sys") { user("hi") }
            .copy(params = LLMParams(additionalProperties = mapOf("custom_key" to JsonPrimitive("v"))))
        executor.execute(prompt, model, emptyList())
        val props = delegate.last!!.params.additionalProperties ?: emptyMap()
        assertEquals("xhigh", props[ReasoningEffortOverrideExecutor.REASONING_EFFORT_KEY]!!.jsonPrimitive.content)
        assertEquals("v", props["custom_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `existing effort in params is replaced, not duplicated`() = runBlocking {
        val delegate = RecordingExecutor()
        val executor = ReasoningEffortOverrideExecutor(delegate, "xhigh")
        val prompt = Prompt.build("sys") { user("hi") }
            .copy(params = LLMParams(additionalProperties = mapOf(ReasoningEffortOverrideExecutor.REASONING_EFFORT_KEY to JsonPrimitive("low"))))
        executor.execute(prompt, model, emptyList())
        val props = delegate.last!!.params.additionalProperties ?: emptyMap()
        assertEquals("xhigh", props[ReasoningEffortOverrideExecutor.REASONING_EFFORT_KEY]!!.jsonPrimitive.content)
        assertTrue(props.size == 1)
    }
}
