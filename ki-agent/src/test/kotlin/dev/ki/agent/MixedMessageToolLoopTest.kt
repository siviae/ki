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
import dev.ki.agent.tools.tool
import dev.ki.ai.KiLlm
import dev.ki.ai.KiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for bot session nNWb3vJMKrv2THW3E (koog 1.0.0-preview7 era): the model
 * answered with TEXT AND A TOOL CALL in one message ("Эта таблица — день-офф… Сначала
 * посмотрю…" + shifts_db_refresh_schema) and the run finished without executing the
 * call — the bot's reply just stopped mid-investigation with no error surfaced.
 *
 * Whichever edge the graph picks, a mixed text+toolCall assistant message must keep the
 * tool loop alive, and a failing script tool must surface as an ERROR event, never kill
 * the turn silently. Covered on both LLM paths: streaming (the bot runs with
 * streaming=true) and blocking.
 */
class MixedMessageToolLoopTest {

    /** Scripted executor: each call returns the next queued assistant message. */
    private class ScriptedBlockingExecutor(private val replies: List<Message.Assistant>) : PromptExecutor() {
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

    /** Streaming twin: converts each queued assistant message into complete frames. */
    private class ScriptedStreamingExecutor(private val replies: List<Message.Assistant>) : PromptExecutor() {
        var calls = 0; private set
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
            throw NotImplementedError()
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
            val r = replies[calls.coerceAtMost(replies.size - 1)]
            calls++
            for (part in r.parts) {
                when (part) {
                    is MessagePart.Text -> emit(StreamFrame.TextComplete(part.text))
                    is MessagePart.Tool.Call -> emit(StreamFrame.ToolCallComplete(part.id, part.tool, part.args))
                    else -> {}
                }
            }
            emit(StreamFrame.End(metaInfo = ResponseMetaInfo.Empty))
        }
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): List<Message.Assistant> =
            throw NotImplementedError()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = throw NotImplementedError()
        override fun close() {}
    }

    private fun probeTool() = ScriptTool(tool("probe") {
        description = "probe tool"
        param("cache_dir", "optional arg", required = false)
        execute { _ -> "probe-ok" }
    })

    /** The bot's exact shape: narration text, then the tool call, then more text. */
    private fun mixedMessage(id: String, toolName: String) = Message.Assistant(
        listOf(
            MessagePart.Text("Эта таблица — не то. Сначала посмотрю схему."),
            MessagePart.Tool.Call(id = id, tool = toolName, args = "{}"),
            MessagePart.Text("Собираю результат."),
        ),
        ResponseMetaInfo.Empty,
    )

    private fun textMessage(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)

    private fun toolCallMessage(id: String, name: String, args: String) = Message.Assistant(
        part = MessagePart.Tool.Call(id = id, tool = name, args = args),
        metaInfo = ResponseMetaInfo.Empty,
    )

    @Test fun `mixed text + tool call keeps the tool loop alive (streaming path)`() {
        val executor = ScriptedStreamingExecutor(
            listOf(mixedMessage("c1", "probe"), textMessage("done")),
        )
        val llm = KiLlm.of(executor, KiModel(id = "test", contextWindow = 4000))
        val agent = KiAgent(llm, systemPrompt = "sys", tools = listOf(probeTool()), streaming = true)

        val events = mutableListOf<ToolCallEvent>()
        val result = runBlocking { agent.run("go", onTool = { events.add(it) }) }

        assertEquals("done", result)
        assertEquals(listOf(ToolPhase.STARTING, ToolPhase.OK), events.map { it.phase })
    }

    /**
     * The bot's SECOND stall shape (reproduced against the live model): turn 1 ends with a
     * tool result, the model replies with mixed [text, toolCall, text]. In ki's
     * streamingStrategy the nodeSendToolResult edges were declared finish-FIRST
     * (onTextMessage before onToolCalls), and koog picks the FIRST matching edge — so the
     * run ended without executing the second call. The nodeCallLLM edges had the correct
     * tool-first order, which is why the FIRST call of a turn always worked.
     */
    @Test fun `mixed text + tool call after a tool result keeps the loop alive (streaming path)`() {
        val executor = ScriptedStreamingExecutor(
            listOf(
                toolCallMessage("c1", "probe", "{}"),
                mixedMessage("c2", "probe"),
                textMessage("done"),
            ),
        )
        val llm = KiLlm.of(executor, KiModel(id = "test", contextWindow = 4000))
        val agent = KiAgent(llm, systemPrompt = "sys", tools = listOf(probeTool()), streaming = true)

        val events = mutableListOf<ToolCallEvent>()
        val result = runBlocking { agent.run("go", onTool = { events.add(it) }) }

        assertEquals("done", result)
        assertEquals(
            listOf(ToolPhase.STARTING, ToolPhase.OK, ToolPhase.STARTING, ToolPhase.OK),
            events.map { it.phase },
            "both the pure call and the mixed-message call must execute",
        )
    }

    @Test fun `mixed text + tool call keeps the tool loop alive (blocking path)`() {
        val executor = ScriptedBlockingExecutor(
            listOf(mixedMessage("c1", "probe"), textMessage("done")),
        )
        val llm = KiLlm.of(executor, KiModel(id = "test", contextWindow = 4000))
        val agent = KiAgent(llm, systemPrompt = "sys", tools = listOf(probeTool()))

        val events = mutableListOf<ToolCallEvent>()
        val result = runBlocking { agent.run("go", onTool = { events.add(it) }) }

        assertEquals("done", result)
        assertEquals(listOf(ToolPhase.STARTING, ToolPhase.OK), events.map { it.phase })
    }

    /**
     * Mid-run steering (pi parity): a steer message that arrives while the run is
     * between tool calls must be injected into the prompt BEFORE the next LLM call —
     * not queued until the whole run ends. Bot sessions run long turns; a user's
     * "используй другую таблицу" must redirect the current run, not become a new one.
     */
    @Test fun `steer arriving after a tool result is injected into the next LLM call`() {
        // Records the user-side prompt text of every LLM call.
        val promptTexts = mutableListOf<String>()
        class RecordingExecutor : PromptExecutor() {
            val replies = ArrayDeque(
                listOf(
                    toolCallMessage("c1", "probe", "{}"),
                    textMessage("done"),
                ),
            )
            override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
                throw NotImplementedError()

            override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
                promptTexts += prompt.messages
                    .filterIsInstance<Message.User>()
                    .flatMap { it.parts }
                    .filterIsInstance<MessagePart.Text>()
                    .joinToString("\n") { it.text }
                val r = replies.removeFirst()
                for (part in r.parts) {
                    when (part) {
                        is MessagePart.Text -> emit(StreamFrame.TextComplete(part.text))
                        is MessagePart.Tool.Call -> emit(StreamFrame.ToolCallComplete(part.id, part.tool, part.args))
                        else -> {}
                    }
                }
                emit(StreamFrame.End(metaInfo = ResponseMetaInfo.Empty))
            }
            override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): List<Message.Assistant> =
                throw NotImplementedError()
            override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = throw NotImplementedError()
            override fun close() {}
        }

        val executor = RecordingExecutor()
        val llm = KiLlm.of(executor, KiModel(id = "test", contextWindow = 4000))
        val agent = KiAgent(llm, systemPrompt = "sys", tools = listOf(probeTool()), streaming = true)

        // Steer exactly when the first tool finished — mid-run, before LLM call #2.
        runBlocking {
            agent.run("go", onTool = { e ->
                if (e.phase == ToolPhase.OK) agent.steer("use table extra_hours_requests instead")
            })
        }

        assertTrue(
            promptTexts.size >= 2,
            "expected two LLM calls, got ${'$'}{promptTexts.size}",
        )
        assertTrue(
            promptTexts[1].contains("use table extra_hours_requests instead"),
            "steer text must be in the second call's prompt, got: ${'$'}{promptTexts[1]}",
        )
    }

    @Test fun `steer with no active run is rejected`() {
        val executor = ScriptedStreamingExecutor(listOf(textMessage("ok")))
        val llm = KiLlm.of(executor, KiModel(id = "test", contextWindow = 4000))
        val agent = KiAgent(llm, systemPrompt = "sys", tools = listOf(probeTool()), streaming = true)
        assertTrue(!agent.steer("stop"), "no run active — must not queue")
    }
}
