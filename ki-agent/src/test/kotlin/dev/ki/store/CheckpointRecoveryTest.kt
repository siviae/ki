package dev.ki.store

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.snapshot.feature.isTombstone
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import dev.ki.agent.KiAgent
import dev.ki.agent.tools.ParamType
import dev.ki.agent.tools.ScriptTool
import dev.ki.agent.tools.tool
import dev.ki.ai.KiLlm
import dev.ki.ai.KiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M9 crash-recovery, verified across **two agent instances** sharing one checkpoint store
 * and session id — the offline stand-in for a killed-and-restarted process (and the M10
 * cross-node-takeover primitive).
 *
 * Both features run together exactly as the CLI wires them — **chat-memory + checkpoints**
 * — because the load-bearing risk is their coexistence: on resume, chat-memory reloads the
 * last completed turn while `Persistence` restores the checkpoint's fuller history, and if
 * the restore *appended* instead of *replacing* the context would silently double. So the
 * scenario is: run 0 completes (chat-memory now holds a turn), run 1 crashes mid-tool, and
 * a fresh instance resumes — asserting every prior message survives **exactly once**.
 */
class CheckpointRecoveryTest {

    /** In-memory [CheckpointStore] — exercises [StoreCheckpointProvider] + [CheckpointCodec]. */
    private class MemCheckpointStore : CheckpointStore {
        private val bySession = LinkedHashMap<String, MutableList<StoredCheckpoint>>()
        override fun load(sessionId: String) = bySession[sessionId]?.toList() ?: emptyList()
        override fun save(sessionId: String, checkpoint: StoredCheckpoint) {
            val list = bySession.getOrPut(sessionId) { mutableListOf() }
            list.removeAll { it.checkpointId == checkpoint.checkpointId }
            list.add(checkpoint)
        }
        override fun latest(sessionId: String) = bySession[sessionId]?.maxByOrNull { it.version }
        override fun delete(sessionId: String) { bySession.remove(sessionId) }
    }

    private class MemSessionStore : SessionStore {
        private val rows = mutableMapOf<String, List<StoredMessage>>()
        override fun load(conversationId: String) = rows[conversationId] ?: emptyList()
        override fun save(conversationId: String, messages: List<StoredMessage>) { rows[conversationId] = messages }
        override fun listSessions() = rows.map { SessionInfo(it.key, 0, it.value.size) }
    }

    private class ScriptedExecutor(private val responses: List<() -> Message.Assistant>) : PromptExecutor() {
        val prompts = mutableListOf<List<Message>>()
        private var i = 0
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            prompts.add(prompt.messages)
            return responses[i.coerceAtMost(responses.size - 1)]().also { i++ }
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            throw NotImplementedError()
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): List<Message.Assistant> =
            throw NotImplementedError()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw NotImplementedError()
        override fun close() {}
    }

    private fun toolCall(text: String) = Message.Assistant(
        parts = listOf(MessagePart.Tool.Call(id = "c-$text", tool = "note", args = """{"text":"$text"}""")),
        metaInfo = ResponseMetaInfo.Empty,
    )

    private fun text(t: String) = Message.Assistant(MessagePart.Text(t), ResponseMetaInfo.Empty)

    private fun noteTool() = ScriptTool(
        tool("note") {
            description = "Records a note and echoes it."
            param("text", "the note", ParamType.STRING, required = true)
            execute { args -> "noted:${args.string("text")}" }
        }
    )

    // compressHistory = true matches the shipped CLI config: checkpoints coexist with the
    // FromLastNMessages compression strategy branch (not NoCompression), which is the branch
    // install(Persistence)'s `require(uniqueNames)` actually runs against in production.
    private fun agent(exec: PromptExecutor, history: StoreChatHistoryProvider, ckpt: StoreCheckpointProvider) =
        KiAgent(
            KiLlm.of(exec, KiModel(id = "test", contextWindow = 100_000)),
            systemPrompt = "SYS", tools = listOf(noteTool()),
            compressHistory = true, historyProvider = history, checkpointProvider = ckpt,
        )

    @Test fun `a crashed run resumes on a fresh instance without duplicating prior history`() {
        val ckptStore = MemCheckpointStore()
        val ckpt = StoreCheckpointProvider(ckptStore)
        val history = StoreChatHistoryProvider(MemSessionStore())
        val sid = "S-recover"

        // Run 0: completes a full turn — chat-memory now holds it, checkpoint chain tombstoned.
        val e0 = ScriptedExecutor(listOf({ toolCall("remember-0") }, { text("ack-0") }))
        runBlocking { agent(e0, history, ckpt).run("first task", sid) }
        assertTrue(
            CheckpointCodec.decode(ckptStore.latest(sid)!!.json).isTombstone(),
            "a completed run must leave a tombstone",
        )

        // Run 1: calls the tool (node checkpoint) then crashes before completion.
        val e1 = ScriptedExecutor(listOf({ toolCall("remember-1") }, { throw RuntimeException("CRASH") }))
        val crashed = runCatching { runBlocking { agent(e1, history, ckpt).run("second task", sid) } }
        assertTrue(crashed.isFailure, "run 1 should have crashed mid-run")
        val afterCrash = ckptStore.latest(sid)
        assertNotNull(afterCrash)
        assertFalse(
            CheckpointCodec.decode(afterCrash.json).isTombstone(),
            "an interrupted run must not leave a tombstone",
        )

        // Run 2: fresh instance, same stores + session id — resumes and finishes.
        val e2 = ScriptedExecutor(listOf({ text("done") }))
        val out = runBlocking { agent(e2, history, ckpt).run("second task", sid) }
        assertEquals("done", out)
        assertTrue(e2.prompts.isNotEmpty(), "instance B should have called the model to finish")

        // No duplication: every distinct prior marker appears AT MOST once in the resumed
        // prompt, even though chat-memory (turn 0) and the checkpoint (turn 0 + partial
        // turn 1) both feed history. The checkpoint's wholesale restore must win, not append.
        val resumed = e2.prompts.last().flatMap { it.parts }
        val callTexts = resumed.filterIsInstance<MessagePart.Tool.Call>().map { it.tool + it.args }
        val resultTexts = resumed.filterIsInstance<MessagePart.Tool.Result>().map { it.output }
        for (marker in listOf("remember-0", "remember-1")) {
            assertTrue(
                callTexts.count { it.contains(marker) } <= 1,
                "tool call for $marker duplicated: $callTexts",
            )
            assertTrue(
                resultTexts.count { it.contains(marker) } <= 1,
                "tool result for $marker duplicated: $resultTexts",
            )
        }
        // And the pre-crash turn-1 work is actually present (recovery, not a blank restart).
        assertTrue(
            resultTexts.any { it.contains("remember-1") } || callTexts.any { it.contains("remember-1") },
            "resumed context should carry the pre-crash turn-1 work",
        )
        // Turn 0 survives EXACTLY once — not dropped (which <=1 alone would let pass), not doubled.
        assertEquals(
            1, resultTexts.count { it.contains("remember-0") },
            "turn-0 tool result must survive exactly once through the ChatMemory+checkpoint resume",
        )
    }
}
