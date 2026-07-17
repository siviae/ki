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
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M6 blocker (per review): koog chat-memory and the history-compression strategy must
 * coexist under the real [KiAgent]. Forces the context over budget via a large system
 * prompt, drives a tool call so the compression edge is reached, and asserts:
 * compression fires (the summarize turn happens), chat-memory still stores the
 * session, the system prompt survives, and usage is captured.
 */
class ContextCompressionTest {

    /** Replays scripted assistant responses; records each prompt it executes. */
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

    private class InMemorySessionStore : SessionStore {
        val rows = mutableMapOf<String, List<StoredMessage>>()
        override fun load(conversationId: String) = rows[conversationId] ?: emptyList()
        override fun save(conversationId: String, messages: List<StoredMessage>) { rows[conversationId] = messages }
        override fun listSessions() = rows.map { SessionInfo(it.key, 0, it.value.size) }
    }

    private fun assistant(text: String) =
        Message.Assistant(MessagePart.Text(text), ResponseMetaInfo.Empty)

    @Test fun `compression and chat-memory coexist and preserve the system prompt`() {
        val dir = Files.createTempDirectory("ki-ctx-compress")
        val store = InMemorySessionStore()
        val provider = StoreChatHistoryProvider(store)

        // Response script: (1) call the ls tool, (2) answer the summarize request,
        // (3) produce the final reply from the compressed history.
        val executor = ScriptedExecutor(
            listOf(
                Message.Assistant(
                    parts = listOf(MessagePart.Tool.Call(id = "c1", tool = "ls", args = """{"path":"$dir"}""")),
                    metaInfo = ResponseMetaInfo.Empty,
                ),
                assistant("TL;DR: listed the directory."),
                assistant("final answer"),
            )
        )

        // A large system prompt guarantees the context is over budget on the first check.
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
        )

        val out = runBlocking { agent.run("list the files", sessionId = "S") }

        assertEquals("final answer", out)
        // Three LLM calls == the compression path was taken (tool call, summarize, final).
        assertEquals(3, executor.prompts.size, "expected the compression path (3 LLM calls)")
        val sawSummarize = executor.prompts.any { msgs ->
            msgs.any { m -> m.parts.filterIsInstance<MessagePart.Text>().any { it.text.contains("comprehensive summary of this conversation") } }
        }
        assertTrue(sawSummarize, "the summarize (compression) turn did not fire")

        // Chat-memory still persisted the session alongside the custom strategy...
        val stored = store.load("S")
        assertTrue(stored.isNotEmpty(), "chat-memory did not store under the compression strategy")
        // ...and the system prompt survived compaction.
        assertTrue(stored.any { it.role == "System" }, "system prompt was lost")

        // Usage was captured (estimate, since the stub returns no token counts).
        val usage = agent.lastUsage
        assertNotNull(usage)
        assertTrue(usage.tokens > 0)
        assertEquals(300L, usage.window)
    }
}
