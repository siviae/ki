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
 * M9 crash-recovery, verified across **two agent instances** sharing one checkpoint
 * store and session id — the offline stand-in for a killed-and-restarted process (and
 * the M10 cross-node-takeover primitive). Instance A runs a turn that calls a tool, then
 * "crashes" (executor throws) before completing; instance B, built fresh over the same
 * [CheckpointStore] and session id, resumes from the last checkpoint and finishes.
 *
 * The load-bearing assertions: (1) B **completes** (recovery happened), (2) B's resumed
 * prompt carries the pre-crash tool call + result **exactly once** — no context
 * duplication when the checkpoint's wholesale `messageHistory` restore drives resume, and
 * (3) the tombstone lifecycle (non-tombstone after crash, tombstone after clean finish).
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
        parts = listOf(MessagePart.Tool.Call(id = "c1", tool = "note", args = """{"text":"$text"}""")),
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

    @Test fun `a crashed run resumes from checkpoint on a fresh instance without duplicating history`() {
        val store = MemCheckpointStore()
        val provider = StoreCheckpointProvider(store)
        val model = KiModel(id = "test", contextWindow = 100_000)
        val sessionId = "S-recover"

        // Instance A: call the tool (creates a node checkpoint), then "crash".
        val crashExec = ScriptedExecutor(
            listOf(
                { toolCall("remember-42") },
                { throw RuntimeException("CRASH before completion") },
            )
        )
        val agentA = KiAgent(
            KiLlm.of(crashExec, model), systemPrompt = "SYS", tools = listOf(noteTool()),
            compressHistory = false, checkpointProvider = provider,
        )
        val crashed = runCatching { runBlocking { agentA.run("do it", sessionId) } }
        assertTrue(crashed.isFailure, "instance A should have crashed mid-run")

        // After the crash, the latest checkpoint exists and is NOT a tombstone.
        val afterCrash = store.latest(sessionId)
        assertNotNull(afterCrash, "a checkpoint should have been written before the crash")
        assertFalse(
            CheckpointCodec.decode(afterCrash.json).isTombstone(),
            "an interrupted run must not leave a tombstone",
        )

        // Instance B: fresh agent, same store + session id, just answers.
        val resumeExec = ScriptedExecutor(listOf({ text("done") }))
        val agentB = KiAgent(
            KiLlm.of(resumeExec, model), systemPrompt = "SYS", tools = listOf(noteTool()),
            compressHistory = false, checkpointProvider = provider,
        )
        val out = runBlocking { agentB.run("do it", sessionId) }

        // (1) recovery completed.
        assertEquals("done", out)
        assertTrue(resumeExec.prompts.isNotEmpty(), "instance B should have called the model to finish")

        // (2) the resumed prompt carries the pre-crash tool call + result EXACTLY once.
        val resumed = resumeExec.prompts.last().flatMap { it.parts }
        val calls = resumed.filterIsInstance<MessagePart.Tool.Call>().filter { it.tool == "note" }
        val results = resumed.filterIsInstance<MessagePart.Tool.Result>().filter { it.output.contains("remember-42") }
        assertEquals(1, calls.size, "tool call must appear once, not duplicated across ChatMemory + checkpoint")
        assertEquals(1, results.size, "tool result must appear once")

        // (3) a clean finish writes a tombstone.
        val afterFinish = store.latest(sessionId)
        assertNotNull(afterFinish)
        assertTrue(
            CheckpointCodec.decode(afterFinish.json).isTombstone(),
            "a completed run must leave a tombstone so the happy path skips restore",
        )
    }
}
