package dev.ki.cli.rpc

import dev.ki.agent.ToolCallEvent
import dev.ki.agent.ToolPhase
import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wire-protocol tests for the ki-rpc server (M1.4): requests answered with pi's
 * `{type:"response", id, success}` shape, events emitted in pi's `AgentEvent` dialect,
 * one `agent_end` per drained queue (steering stays in-run), tool events carrying the
 * FULL args/result (the TUI previews are not what telemetry sees).
 */
class RpcServerTest {

    /** Scriptable fake: run() records prompts, emits a scripted tool call, appends to a fake store. */
    private class FakeAgent(
        private val turns: Int = 1,
        private val failFirst: Boolean = false,
    ) : RpcAgent {
        val prompts = ArrayList<String>()
        val pathMessages = ArrayList<Map<String, Any?>>()
        var runFailure: Throwable? = null
        var afterRun: (() -> Unit)? = null

        override suspend fun runTurn(
            prompt: String,
            onReasoning: ((String) -> Unit)?,
            onTool: ((ToolCallEvent) -> Unit)?,
        ): String {
            prompts.add(prompt)
            afterRun?.invoke()
            runFailure?.let { throw it }
            onTool?.invoke(
                ToolCallEvent(
                    id = "call_1", name = "bash", args = "command: ls", phase = ToolPhase.STARTING,
                    fullArgs = """{"command":"ls -la"}""",
                ),
            )
            if (failFirst) {
                onTool?.invoke(
                    ToolCallEvent(
                        id = "call_1", name = "bash", args = "command: ls", phase = ToolPhase.ERROR,
                        result = "boom", fullArgs = """{"command":"ls -la"}""",
                    ),
                )
            } else {
                onTool?.invoke(
                    ToolCallEvent(
                        id = "call_1", name = "bash", args = "command: ls", phase = ToolPhase.OK,
                        result = "total 0", fullArgs = """{"command":"ls -la"}""",
                        fullResult = ai.koog.serialization.JSONObject(
                            mapOf("output" to ai.koog.serialization.JSONPrimitive("file1\nfile2")),
                        ),
                    ),
                )
            }
            pathMessages.add(
                linkedMapOf(
                    "role" to "user",
                    "content" to listOf(linkedMapOf("type" to "text", "text" to prompt)),
                    "timestamp" to 1_000L + prompts.size,
                ),
            )
            pathMessages.add(
                linkedMapOf(
                    "role" to "assistant",
                    "content" to listOf(
                        linkedMapOf("type" to "text", "text" to "reply-${prompts.size}"),
                    ),
                    "timestamp" to 2_000L + prompts.size,
                    "usage" to linkedMapOf("input" to 10, "output" to 5, "totalTokens" to 15),
                    "model" to "test-model",
                    "provider" to "test-provider",
                    "stopReason" to "stop",
                ),
            )
            return "reply-${prompts.size}"
        }

        override val toolNames: List<String> = listOf("bash", "read")
        override fun toolDescription(name: String): String? =
            mapOf("bash" to "Run bash", "read" to "Read a file")[name]
        override fun piPathMessages(): List<Map<String, Any?>> = pathMessages.toList()
        var compactRequested = false
        override fun compactNow() { compactRequested = true }
    }

    private fun serve(agent: RpcAgent, metaDir: java.nio.file.Path? = null, lines: List<String>): List<Map<String, Any?>> {
        val out = ArrayList<String>()
        RpcServer(agent, metaDir) { out.add(it) }.serve(BufferedReader(StringReader(lines.joinToString("\n"))))
        return out.map { dev.ki.cli.store.KiJson.readMap(it) }
    }

    @Test
    fun `prompt gets an immediate response then the pi event sequence`() {
        val agent = FakeAgent()
        val lines = serve(agent, lines = listOf("""{"type":"prompt","id":"req_1","message":"hello"}"""))
        assertEquals("response", lines[0]["type"])
        assertEquals("req_1", lines[0]["id"])
        assertEquals(true, lines[0]["success"])
        val types = lines.drop(1).map { it["type"] }
        assertEquals(
            listOf("agent_start", "turn_start", "tool_execution_start", "tool_execution_end", "message_end", "turn_end", "agent_end"),
            types,
        )
        assertEquals(listOf("hello"), agent.prompts)
    }

    @Test
    fun `tool events carry parsed full args and full result`() {
        val lines = serve(FakeAgent(), lines = listOf("""{"type":"prompt","id":"req_1","message":"x"}"""))
        val start = lines.first { it["type"] == "tool_execution_start" }
        assertEquals("bash", start["toolName"])
        assertEquals("call_1", start["toolCallId"])
        assertEquals(mapOf("command" to "ls -la"), start["args"])
        val end = lines.first { it["type"] == "tool_execution_end" }
        assertEquals(false, end["isError"])
        assertEquals(mapOf("output" to "file1\nfile2"), end["result"])
    }

    @Test
    fun `tool error sets isError and passes the failure text`() {
        val lines = serve(FakeAgent(failFirst = true), lines = listOf("""{"type":"prompt","id":"r1","message":"x"}"""))
        val end = lines.first { it["type"] == "tool_execution_end" }
        assertEquals(true, end["isError"])
        assertEquals("boom", end["result"])
    }

    @Test
    fun `message_end and agent_end expose the stored assistant entry with metrics`() {
        val lines = serve(FakeAgent(), lines = listOf("""{"type":"prompt","id":"r1","message":"q"}"""))
        val messageEnd = lines.first { it["type"] == "message_end" }["message"] as Map<*, *>
        assertEquals("assistant", messageEnd["role"])
        assertEquals("test-model", messageEnd["model"])
        assertEquals("test-provider", messageEnd["provider"])
        assertEquals(mapOf("input" to 10, "output" to 5, "totalTokens" to 15), messageEnd["usage"])
        val agentEnd = lines.first { it["type"] == "agent_end" }
        val messages = agentEnd["messages"] as List<*>
        assertEquals(2, messages.size)
        val last = messages.last() as Map<*, *>
        assertEquals("assistant", last["role"])
        assertEquals("reply-1", ((last["content"] as List<*>).first() as Map<*, *>)["text"])
    }

    @Test
    fun `steer during a run is answered in the same run - one agent_end after the queue drains`() {
        val agent = FakeAgent()
        val lines = serve(
            agent,
            lines = listOf(
                """{"type":"prompt","id":"r1","message":"first"}""",
                """{"type":"steer","id":"r2","message":"steered"}""",
            ),
        )
        // both prompts answered (ki v1: steer = next turn of the same run)
        assertEquals(listOf("first", "steered"), agent.prompts)
        val agentEnds = lines.filter { it["type"] == "agent_end" }
        assertEquals(1, agentEnds.size, "one run, one agent_end: ${lines.map { it["type"] }}")
        val starts = lines.filter { it["type"] == "agent_start" }
        assertEquals(1, starts.size)
        // the final agent_end carries BOTH turns' messages
        assertEquals(4, (agentEnds[0]["messages"] as List<*>).size)
    }

    @Test
    fun `abort cancels the active turn and ends the run`() {
        val agent = FakeAgent()
        agent.afterRun = { Thread.sleep(10_000) } // hold the turn open
        val out = ArrayList<String>()
        val input = listOf(
            """{"type":"prompt","id":"r1","message":"slow"}""",
            """{"type":"abort","id":"r2"}""",
        )
        RpcServer(agent, null) { out.add(it) }.serve(BufferedReader(StringReader(input.joinToString("\n"))))
        val types = out.map { dev.ki.cli.store.KiJson.readMap(it)["type"] }
        assertTrue("agent_end" in types, "abort must end the run: $types")
        assertTrue("agent_start" in types)
    }

    @Test
    fun `compact request flags the next turn for compression`() {
        val agent = FakeAgent()
        val lines = serve(agent, lines = listOf("""{"type":"compact","id":"r1"}"""))
        assertEquals(true, lines.first()["success"])
        assertEquals(mapOf("pending" to true), lines.first()["data"])
        assertTrue(agent.compactRequested)
    }

    @Test
    fun `unknown command answers with success false`() {
        val lines = serve(FakeAgent(), lines = listOf("""{"type":"getState","id":"r9"}"""))
        val response = lines.first()
        assertEquals("response", response["type"])
        assertEquals(false, response["success"])
        assertNotNull(response["error"])
    }

    @Test
    fun `tool meta files are exported at startup`() {
        val dir = Files.createTempDirectory("ki-rpc-meta")
        serve(FakeAgent(), metaDir = dir, lines = listOf("""{"type":"prompt","id":"r1","message":"x"}"""))
        val bashMeta = dev.ki.cli.store.KiJson.readMap(Files.readString(dir.resolve("bash.json")))
        assertEquals("Run bash", bashMeta["description"])
        assertEquals(emptyList<Any?>(), bashMeta["promptGuidelines"])
        assertTrue(Files.exists(dir.resolve("read.json")))
    }

    @Test
    fun `turn failure still ends the run with an agent_end`() {
        val agent = FakeAgent().apply { runFailure = RuntimeException("LLM down") }
        val lines = serve(agent, lines = listOf("""{"type":"prompt","id":"r1","message":"x"}"""))
        assertTrue("agent_end" in lines.map { it["type"] }, "failed turn must still emit agent_end")
    }
}
