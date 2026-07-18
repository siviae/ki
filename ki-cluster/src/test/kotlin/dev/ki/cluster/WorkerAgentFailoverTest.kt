package dev.ki.cluster

import ai.koog.agents.core.tools.ToolDescriptor
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
import dev.ki.store.CheckpointStore
import dev.ki.store.SessionInfo
import dev.ki.store.SessionOwnership
import dev.ki.store.SessionStore
import dev.ki.store.SteeringInbox
import dev.ki.store.SteeringMessage
import dev.ki.store.StoreChatHistoryProvider
import dev.ki.store.StoreCheckpointProvider
import dev.ki.store.StoredCheckpoint
import dev.ki.store.StoredMessage
import dev.ki.store.SessionTurnRunner
import dev.ki.store.TurnReplySink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The seam the other tests don't cross: `SessionWorker` driving a **real `KiAgent`** through a
 * simulated crash + takeover. `WorkerIT`'s failover used an echo lambda, proving only
 * lock-release-takeover; `CheckpointRecoveryTest` proved agent resume but not through the worker
 * with a re-fed inbox message. This closes both — and answers the load-bearing question: when a
 * takeover node **re-feeds the still-unconsumed peeked message** into `agent.run`, does the user
 * message get **double-added** (checkpoint already carries it) or appear once?
 *
 * Offline: in-memory stores + a scripted executor, routed through the worker.
 */
class WorkerAgentFailoverTest {

    private val model = KiModel(id = "test", contextWindow = 100_000)

    private class ScriptedExecutor(private val responses: List<() -> Message.Assistant>) : PromptExecutor() {
        val prompts = mutableListOf<List<Message>>()
        private var i = 0
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            prompts.add(prompt.messages)
            return responses[i.coerceAtMost(responses.size - 1)]().also { i++ }
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = throw NotImplementedError()
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): List<Message.Assistant> = throw NotImplementedError()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = throw NotImplementedError()
        override fun close() {}
    }

    private class MemCheckpointStore : CheckpointStore {
        private val m = LinkedHashMap<String, MutableList<StoredCheckpoint>>()
        override fun load(sessionId: String) = m[sessionId]?.toList() ?: emptyList()
        override fun save(sessionId: String, checkpoint: StoredCheckpoint) {
            val l = m.getOrPut(sessionId) { mutableListOf() }; l.removeAll { it.checkpointId == checkpoint.checkpointId }; l.add(checkpoint)
        }
        override fun latest(sessionId: String) = m[sessionId]?.maxByOrNull { it.version }
        override fun delete(sessionId: String) { m.remove(sessionId) }
    }

    private class MemSessionStore : SessionStore {
        private val rows = mutableMapOf<String, List<StoredMessage>>()
        override fun load(conversationId: String) = rows[conversationId] ?: emptyList()
        override fun save(conversationId: String, messages: List<StoredMessage>) { rows[conversationId] = messages }
        override fun listSessions() = rows.map { SessionInfo(it.key, 0, it.value.size) }
    }

    private class FakeInbox : SteeringInbox {
        private data class Row(val seq: Long, val session: String, val payload: String, var consumed: Boolean)
        private val rows = ArrayList<Row>(); private var next = 1L
        @Synchronized override fun write(sessionId: String, payload: String) { rows.add(Row(next++, sessionId, payload, false)) }
        @Synchronized override fun peek(sessionId: String) = rows.filter { it.session == sessionId && !it.consumed }.map { SteeringMessage(it.seq, it.payload) }
        @Synchronized override fun markConsumed(sessionId: String, throughSeq: Long) { rows.forEach { if (it.session == sessionId && !it.consumed && it.seq <= throughSeq) it.consumed = true } }
        @Synchronized override fun pendingSessions(limit: Int) = rows.filter { !it.consumed }.map { it.session }.distinct().take(limit)
    }

    private class SingleOwnership : SessionOwnership {
        private val held = HashSet<String>()
        @Synchronized override fun tryClaim(sessionId: String) = held.add(sessionId) || held.contains(sessionId)
        @Synchronized override fun release(sessionId: String) { held.remove(sessionId) }
        @Synchronized override fun isOwner(sessionId: String) = held.contains(sessionId)
        @Synchronized override fun owned() = held.toSet()
    }

    private fun noteTool() = ScriptTool(
        tool("note") {
            description = "Records a note."
            param("text", "the note", ParamType.STRING, required = true)
            execute { args -> "noted:${args.string("text")}" }
        }
    )

    private fun runner(exec: PromptExecutor, history: StoreChatHistoryProvider, ckpt: StoreCheckpointProvider) =
        SessionTurnRunner { sid, input ->
            KiAgent(
                KiLlm.of(exec, model), systemPrompt = "SYS", tools = listOf(noteTool()),
                historyProvider = history, checkpointProvider = ckpt, compressHistory = true,
            ).run(input, sid)
        }

    private fun assistantToolCall(text: String) = Message.Assistant(
        parts = listOf(MessagePart.Tool.Call(id = "c1", tool = "note", args = """{"text":"$text"}""")),
        metaInfo = ResponseMetaInfo.Empty,
    )
    private fun assistantText(t: String) = Message.Assistant(MessagePart.Text(t), ResponseMetaInfo.Empty)

    @Test fun `worker takeover resumes a real agent from checkpoint without double-adding the input`() {
        val ckptStore = MemCheckpointStore()
        val ckpt = StoreCheckpointProvider(ckptStore)
        val history = StoreChatHistoryProvider(MemSessionStore())
        val inbox = FakeInbox()
        val sid = "S"
        val replies = mutableListOf<String>()
        val sink = TurnReplySink { _, r -> replies.add(r) }

        inbox.write(sid, "do it")

        // Node A: agent calls the tool (checkpoint written) then crashes.
        val execA = ScriptedExecutor(listOf({ assistantToolCall("remember-1") }, { throw RuntimeException("CRASH") }))
        val workerA = SessionWorker(SingleOwnership(), inbox, runner(execA, history, ckpt), sink)
        assertFailsWith<RuntimeException> { runBlocking { workerA.processSession(sid) } }
        assertEquals(1, inbox.peek(sid).size, "crashed turn must leave the message unconsumed")

        // Node B: fresh worker + agent over the same checkpoint store + session id; just answers.
        val execB = ScriptedExecutor(listOf({ assistantText("done") }))
        val workerB = SessionWorker(SingleOwnership(), inbox, runner(execB, history, ckpt), sink)
        val processed = runBlocking { workerB.processSession(sid) }

        assertTrue(processed, "B takes over and completes the turn")
        assertEquals(listOf("done"), replies, "the reply is produced once by the takeover node")
        assertTrue(inbox.peek(sid).isEmpty(), "message consumed after the successful takeover turn")

        // The load-bearing assertion: the user input survives resume EXACTLY once — the checkpoint
        // already carried "do it", and B re-fed "do it" via agent.run; it must not double-add.
        val userDoIt = execB.prompts.last()
            .count { m -> m is Message.User && m.parts.filterIsInstance<MessagePart.Text>().any { it.text.contains("do it") } }
        assertEquals(1, userDoIt, "user input must appear exactly once after checkpoint-resumed takeover")
    }
}
