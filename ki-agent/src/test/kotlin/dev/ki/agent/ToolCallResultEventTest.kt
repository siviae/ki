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
import dev.ki.agent.tools.ScriptTool
import dev.ki.agent.tools.builtin.BuiltinTools
import dev.ki.agent.tools.tool
import dev.ki.ai.KiLlm
import dev.ki.ai.KiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The TUI's tool-call row only ever showed the pass/fail color, never the tool's actual
 * output (a user-reported gap). [ToolCallEvent.result] closes it: koog exposes the tool's
 * return value via `onToolCallCompleted`'s `ctx.toolResult` and the failure text via
 * `onToolCallFailed`'s `ctx.message` — this verifies KiAgent forwards them.
 */
class ToolCallResultEventTest {

    /** Scripted blocking executor: each call returns the next queued assistant message. */
    private class ScriptedExecutor(private val replies: List<Message.Assistant>) : PromptExecutor() {
        var calls = 0; private set
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            val r = replies[calls.coerceAtMost(replies.size - 1)]
            calls++
            return r
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            throw NotImplementedError()
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): List<Message.Assistant> =
            throw NotImplementedError()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = throw NotImplementedError()
        override fun close() {}
    }

    private fun toolCallMessage(id: String, name: String, args: String) = Message.Assistant(
        part = MessagePart.Tool.Call(id = id, tool = name, args = args),
        metaInfo = ResponseMetaInfo.Empty,
    )

    private fun textMessage(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)

    @Test fun `a successful tool call carries its own output as the event result`() {
        val dir = Files.createTempDirectory("ki-tool-result")
        dir.resolve("needle.txt").writeText("x")

        val executor = ScriptedExecutor(
            listOf(toolCallMessage("c1", "ls", """{"path":"$dir"}"""), textMessage("done")),
        )
        val llm = KiLlm.of(executor, KiModel(id = "test", contextWindow = 4000))
        val agent = KiAgent(llm, systemPrompt = "sys", tools = listOf(BuiltinTools.byName("ls", dir)!!))

        val events = mutableListOf<ToolCallEvent>()
        runBlocking { agent.run("list files", onTool = { events.add(it) }) }

        assertEquals(listOf(ToolPhase.STARTING, ToolPhase.OK), events.map { it.phase })
        assertNull(events.first().result, "STARTING carries no result yet")
        assertTrue(
            events.last().result?.contains("needle.txt") == true,
            "expected the ls tool's real output in the OK event, got: ${events.last().result}",
        )
    }

    @Test fun `a failing tool call carries the failure message as the event result`() {
        val boom = ScriptTool(tool("boom") {
            description = "always throws"
            execute { error("kaboom") }
        })

        val executor = ScriptedExecutor(
            listOf(toolCallMessage("c1", "boom", "{}"), textMessage("done")),
        )
        val llm = KiLlm.of(executor, KiModel(id = "test", contextWindow = 4000))
        val agent = KiAgent(llm, systemPrompt = "sys", tools = listOf(boom))

        val events = mutableListOf<ToolCallEvent>()
        runBlocking { agent.run("trigger the error", onTool = { events.add(it) }) }

        assertEquals(listOf(ToolPhase.STARTING, ToolPhase.ERROR), events.map { it.phase })
        assertTrue(
            events.last().result?.contains("kaboom") == true,
            "expected the failure message in the ERROR event, got: ${events.last().result}",
        )
    }
}
