package dev.ki.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import dev.ki.agent.tools.ParamType
import dev.ki.agent.tools.ScriptTool
import dev.ki.agent.tools.tool
import dev.ki.ai.KiLlm
import dev.ki.ai.KiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tool that throws must not crash the agent loop: koog's environment catches the
 * exception and feeds it back as an error tool-result, so the model gets a chance to
 * recover. This pins that contract — turn 1 calls a throwing tool, turn 2 answers,
 * and we assert the error text reached the second-turn prompt.
 */
class ToolErrorRecoveryTest {

    private class ScriptedExecutor(private val responses: List<Message.Assistant>) : PromptExecutor() {
        val prompts = mutableListOf<List<Message>>()
        private var i = 0
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            prompts.add(prompt.messages)
            return responses[i.coerceAtMost(responses.size - 1)].also { i++ }
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            throw NotImplementedError()
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): List<Message.Assistant> =
            throw NotImplementedError()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw NotImplementedError()
        override fun close() {}
    }

    private fun assistant(text: String) =
        Message.Assistant(MessagePart.Text(text), ResponseMetaInfo.Empty)

    @Test fun `a throwing tool yields an error result and the agent recovers`() {
        val boom = ScriptTool(
            tool("boom") {
                description = "Always fails."
                param("x", "anything", ParamType.STRING, required = false)
                execute { throw RuntimeException("kaboom") }
            }
        )

        val executor = ScriptedExecutor(
            listOf(
                Message.Assistant(
                    parts = listOf(MessagePart.Tool.Call(id = "c1", tool = "boom", args = "{}")),
                    metaInfo = ResponseMetaInfo.Empty,
                ),
                assistant("recovered: the tool failed but here is the answer"),
            )
        )

        val llm = KiLlm.of(executor, KiModel(id = "test", contextWindow = 100_000))
        val agent = KiAgent(llm, systemPrompt = "SYS", tools = listOf(boom), compressHistory = false)

        val out = runBlocking { agent.run("do the thing", sessionId = "S") }

        assertEquals("recovered: the tool failed but here is the answer", out)
        assertEquals(2, executor.prompts.size, "loop should have taken a second turn after the tool error")

        // The error must have been surfaced to the model as a tool result, not thrown.
        // koog delivers tool results inside a user message as Tool.Result parts.
        val secondTurn = executor.prompts[1]
        val results = secondTurn.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
        assertTrue(results.isNotEmpty(), "second turn should carry the tool result")
        val text = results.joinToString(" ") { it.output }
        assertTrue(
            text.contains("kaboom") || text.contains("failed", ignoreCase = true),
            "error result should describe the failure, was: $text",
        )
    }
}
