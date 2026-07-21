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
import dev.ki.agent.hooks.InterceptorChain
import dev.ki.agent.tools.InterceptionResult
import dev.ki.agent.tools.ParamType
import dev.ki.agent.tools.ScriptTool
import dev.ki.agent.tools.extension
import dev.ki.agent.tools.tool
import dev.ki.ai.KiLlm
import dev.ki.ai.KiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives an `onToolCall` **block** through koog's real tool-dispatch (`nodeExecuteTools`),
 * not by calling the wrapper directly — this is what proves (a) the decorator's overridden
 * `execute` is on koog's execution path, and (b) a block is a *distinct* signal: koog feeds
 * the reason back to the model as a tool result (so the loop recovers) **and** fires
 * `onToolCallFailed`, so the transcript shows ERROR, not a green success.
 */
class ExtensionHookIntegrationTest {

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

    @Test fun `a blocked tool call feeds the reason back and reports ERROR, not OK`() {
        val runner = ScriptTool(
            tool("runner") {
                description = "Runs a command."
                param("cmd", "the command", ParamType.STRING, required = true)
                execute { "should never run" }
            }
        )
        val guarded = InterceptorChain(
            listOf(extension {
                onToolCall("runner") { _, args ->
                    if (args.string("cmd").startsWith("rm")) InterceptionResult.Block("policy: rm is not allowed")
                    else InterceptionResult.Permit
                }
            })
        ).wrap(runner)

        val executor = ScriptedExecutor(
            listOf(
                Message.Assistant(
                    parts = listOf(MessagePart.Tool.Call(id = "c1", tool = "runner", args = """{"cmd":"rm -rf /"}""")),
                    metaInfo = ResponseMetaInfo.Empty,
                ),
                Message.Assistant(MessagePart.Text("understood, I won't"), ResponseMetaInfo.Empty),
            )
        )

        val llm = KiLlm.of(executor, KiModel(id = "test", contextWindow = 100_000))
        val agent = KiAgent(llm, systemPrompt = "SYS", tools = listOf(guarded), compressHistory = false)

        val events = mutableListOf<ToolCallEvent>()
        val out = runBlocking { agent.run("clean up", sessionId = "S", onTool = { events += it }) }

        assertEquals("understood, I won't", out)
        assertEquals(2, executor.prompts.size, "loop should continue after the block, not abort")

        // The block reason reached the model as a tool result.
        val results = executor.prompts[1].flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
        assertTrue(
            results.any { it.output.contains("rm is not allowed") },
            "block reason must be fed back to the model, was: ${results.map { it.output }}",
        )

        // The UI signal is a failure, not a success — a blocked call must not read as green-OK.
        val phases = events.filter { it.name == "runner" }.map { it.phase }
        assertTrue(ToolPhase.ERROR in phases, "blocked call must surface as ERROR, phases were: $phases")
        assertTrue(ToolPhase.OK !in phases, "blocked call must NOT surface as OK, phases were: $phases")
    }
}
