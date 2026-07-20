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
import dev.ki.agent.tools.builtin.BuiltinTools
import dev.ki.ai.KiLlm
import dev.ki.ai.KiModel
import dev.ki.store.SessionInfo
import dev.ki.store.SessionStore
import dev.ki.store.StoreChatHistoryProvider
import dev.ki.store.StoredMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9.1 — streaming reasoning. With `streaming = true` the agent drives the tool loop via a
 * streaming LLM node: each turn's [StreamFrame]s are collected, reasoning deltas are pushed
 * to the run's `onReasoning` sink, and the frames are folded back into the same
 * `Message.Assistant` the blocking node would produce. These tests assert the fold keeps
 * the tool loop working, reasoning reaches the sink live, and M6 compression still coexists.
 */
class StreamingReasoningTest {

    /**
     * Replays scripted [StreamFrame] flows for streaming calls and scripted assistant
     * messages for blocking calls (the compression summarize + compressed-send steps use
     * the blocking `execute`). Records the streaming-call count.
     */
    private class ScriptedStreamExecutor(
        private val streams: List<List<StreamFrame>>,
        private val blocking: List<Message.Assistant> = emptyList(),
    ) : PromptExecutor() {
        var streamCalls = 0; private set
        var blockingCalls = 0; private set

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
            val frames = streams[streamCalls.coerceAtMost(streams.size - 1)]
            streamCalls++
            return flowOf(*frames.toTypedArray())
        }
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            val r = blocking[blockingCalls.coerceAtMost(blocking.size - 1)]
            blockingCalls++
            return r
        }
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): List<Message.Assistant> =
            throw NotImplementedError()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw NotImplementedError()
        override fun close() {}
    }

    private class InMemorySessionStore : SessionStore {
        val rows = mutableMapOf<String, List<StoredMessage>>()
        override fun load(conversationId: String) = rows[conversationId] ?: emptyList()
        override fun save(conversationId: String, messages: List<StoredMessage>) { rows[conversationId] = messages }
        override fun listSessions() = rows.map { SessionInfo(it.key, 0, it.value.size) }
    }

    private fun reasoning(text: String) = StreamFrame.ReasoningDelta(id = "r", text = text, summary = "", index = null)
    private fun toolCall(id: String, name: String, args: String) = StreamFrame.ToolCallComplete(id = id, name = name, content = args, index = null)
    private fun textDone(text: String) = StreamFrame.TextComplete(text = text, index = null)
    private val end = StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty)

    @Test fun `streaming fold drives the tool loop and delivers reasoning deltas`() {
        val dir = Files.createTempDirectory("ki-stream")

        // Turn 1: think, then call ls. Turn 2 (after tool result): think, then answer.
        val executor = ScriptedStreamExecutor(
            streams = listOf(
                listOf(reasoning("let me "), reasoning("list the dir"), toolCall("c1", "ls", """{"path":"$dir"}"""), end),
                listOf(reasoning("done, "), reasoning("answering"), textDone("final answer"), end),
            )
        )
        val llm = KiLlm.of(executor, KiModel(id = "test", contextWindow = 4000))
        val agent = KiAgent(
            llm = llm,
            systemPrompt = "you are ki",
            tools = listOf(BuiltinTools.byName("ls", dir)!!),
            compressHistory = false,
            streaming = true,
        )

        val reasoned = StringBuilder()
        val tools = mutableListOf<ToolCallEvent>()
        val out = runBlocking {
            agent.run("list the files", sessionId = "S",
                onReasoning = { d -> reasoned.append(d) },
                onTool = { e -> tools.add(e) })
        }

        assertEquals("final answer", out, "folded text response was not returned")
        assertEquals(2, executor.streamCalls, "expected two streaming LLM calls (initial + post-tool)")
        assertEquals("let me list the dirdone, answering", reasoned.toString(), "reasoning deltas were not delivered in order")
        assertTrue(agent.lastUsage != null, "usage was not recorded on the streaming path")

        // Tool-call lifecycle surfaced (M9.2): the ls call fired STARTING then OK, same id.
        assertEquals(listOf(ToolPhase.STARTING, ToolPhase.OK), tools.map { it.phase }, "tool lifecycle not surfaced")
        assertTrue(tools.all { it.name == "ls" && it.id == tools.first().id }, "tool events should share name + call id")
    }

    @Test fun `compression coexists with the streaming strategy`() {
        val dir = Files.createTempDirectory("ki-stream-compress")
        val store = InMemorySessionStore()
        val provider = StoreChatHistoryProvider(store)

        // Streaming turn 1 calls ls; over budget → koog summarizes (blocking) then sends the
        // compressed history (blocking) to produce the final answer.
        val executor = ScriptedStreamExecutor(
            streams = listOf(listOf(toolCall("c1", "ls", """{"path":"$dir"}"""), end)),
            blocking = listOf(
                Message.Assistant(MessagePart.Text("TL;DR: listed the directory."), ResponseMetaInfo.Empty),
                Message.Assistant(MessagePart.Text("final answer"), ResponseMetaInfo.Empty),
            ),
        )
        val bigSystem = "context ".repeat(400)
        val llm = KiLlm.of(executor, KiModel(id = "test", contextWindow = 300))
        val agent = KiAgent(
            llm = llm,
            systemPrompt = bigSystem,
            tools = listOf(BuiltinTools.byName("ls", dir)!!),
            historyProvider = provider,
            compressHistory = true,
            keepLastMessages = 2,
            contextBudgetRatio = 0.7,
            streaming = true,
        )

        val out = runBlocking { agent.run("list the files", sessionId = "S", onReasoning = {}) }

        assertEquals("final answer", out)
        assertEquals(1, executor.streamCalls, "initial call should stream")
        assertEquals(2, executor.blockingCalls, "compression should summarize then send compressed history (blocking)")
        val stored = store.load("S")
        assertTrue(stored.isNotEmpty(), "chat-memory did not store under the streaming compression strategy")
        assertTrue(stored.any { it.role == "System" }, "system prompt was lost")
    }
}
