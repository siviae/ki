package dev.ki.cli.store

import dev.ki.store.MessageCodec
import dev.ki.store.StoredMessage
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlin.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1.3 pi-jsonl session store: pi-format JSONL v3 written append-only, koog⇄pi message
 * conversion, compaction synthesis on history compression, cross-resume with pi files.
 */
class PiJsonlSessionStoreTest {
    private fun dir(): Path = Files.createTempDirectory("ki-pijsonl")

    private fun store(dir: Path = this.dir()) = PiJsonlSessionStore(dir, Path.of("/tmp/proj"))

    /** ki messages for one turn: System prompt + user + assistant(thinking+text) + user(toolResult) + assistant(text). */
    private fun turnRows(turnText: String, toolCallId: String = "call_1"): List<StoredMessage> {
        fun koog(m: Message) = StoredMessage(0, m.role.name, MessageCodec.encode(m))
        val system = Message.System(
            content = "You are ki.",
            metaInfo = RequestMeta(0),
        )
        val user = Message.User(
            parts = listOf(MessagePart.Text("run the thing")),
            metaInfo = RequestMeta(1_000),
        )
        val assistant = Message.Assistant(
            parts = listOf(
                MessagePart.Reasoning(content = listOf("thinking about it"), summary = null, encrypted = null, id = null),
                MessagePart.Text(turnText),
                MessagePart.Tool.Call(id = toolCallId, tool = "bash", args = "{\"command\":\"ls\"}"),
            ),
            metaInfo = ResponseMeta(2_000, input = 100, output = 20, total = 120),
            finishReason = "tool_calls",
        )
        val toolResult = Message.User(
            parts = listOf(MessagePart.Tool.Result(id = toolCallId, tool = "bash", output = "file.txt", isError = false)),
            metaInfo = RequestMeta(3_000),
        )
        val assistant2 = Message.Assistant(
            parts = listOf(MessagePart.Text("Done: file.txt")),
            metaInfo = ResponseMeta(4_000, input = 150, output = 10, total = 160),
            finishReason = "stop",
        )
        return listOf(system, user, assistant, toolResult, assistant2).map { koog(it) }
    }

    private object RequestMeta {
        operator fun invoke(ms: Long) =
            ai.koog.prompt.message.RequestMetaInfo(Instant.fromEpochMilliseconds(ms))
    }

    private object ResponseMeta {
        operator fun invoke(ms: Long, input: Int, output: Int, total: Int) =
            ai.koog.prompt.message.ResponseMetaInfo(
                timestamp = Instant.fromEpochMilliseconds(ms),
                totalTokensCount = total,
                inputTokensCount = input,
                outputTokensCount = output,
                modelId = "deepseek-v4-flash",
                metadata = null,
            )
    }

    private fun koogUser(text: String, ms: Long) = StoredMessage(
        0, "User",
        MessageCodec.encode(Message.User(listOf(MessagePart.Text(text)), RequestMeta(ms))),
    )

    private fun koogAssistant(text: String, ms: Long, total: Int) = StoredMessage(
        0, "Assistant",
        MessageCodec.encode(
            Message.Assistant(
                parts = listOf(MessagePart.Text(text)),
                metaInfo = ResponseMeta(ms, input = 10, output = 5, total = total),
                finishReason = "stop",
            ),
        ),
    )

    // ── format ──────────────────────────────────────────────────────────────────────

    @Test fun `first save writes header, message entries and session_info`() {
        val d = dir()
        val s = store(d)
        s.save("conv-1", turnRows("Working on it."))
        val file = Files.list(d).toList().first { it.toString().endsWith(".jsonl") }
        val lines = file.readText().trim().lines()

        val header = KiJson.readMap(lines[0])
        assertEquals("session", header["type"])
        assertEquals(3, header["version"])
        assertEquals("conv-1", header["id"])
        assertEquals("/tmp/proj", header["cwd"])

        val types = lines.drop(1).map { KiJson.readMap(it)["type"] }
        assertEquals(listOf("message", "message", "message", "message", "session_info"), types)

        // tree chain: first entry is a root, every next entry's parentId is the previous id
        val ids = lines.drop(1).map { KiJson.readMap(it)["id"] as String }
        val parentIds = lines.drop(1).map { KiJson.readMap(it)["parentId"] }
        assertNull(parentIds.first())
        assertEquals(ids.dropLast(1), parentIds.drop(1))

        // assistant entry carries pi-shaped usage/stopReason/provider
        val assistant = lines.map { KiJson.readMap(it) }.last { it["type"] == "message" }["message"] as Map<*, *>
        assertEquals("assistant", assistant["role"])
        assertEquals("stop", assistant["stopReason"])
        assertEquals("ki", assistant["provider"])
        assertEquals("openai-completions", assistant["api"])
        val usage = assistant["usage"] as Map<*, *>
        assertEquals(150, usage["input"])
        assertEquals(10, usage["output"])
        assertEquals(160, usage["totalTokens"])
    }

    @Test fun `system prompt goes to the sidecar`() {
        val d = dir()
        val s = store(d)
        s.save("conv-side", turnRows("ok"))
        val file = Files.list(d).toList().first { it.toString().endsWith(".jsonl") }
        val sidecar = file.resolveSibling(file.name + ".system.md")
        assertEquals("You are ki.", sidecar.readText())
    }

    // ── append-only ─────────────────────────────────────────────────────────────────

    @Test fun `second save appends only the new tail and keeps old bytes`() {
        val d = dir()
        val s = store(d)
        s.save("conv-2", turnRows("Working on it."))
        val file = Files.list(d).toList().first { it.toString().endsWith(".jsonl") }
        val before = file.readText()

        // same history + one more user/assistant exchange
        val extended = turnRows("Working on it.") +
            listOf(koogUser("and now?", 5_000), koogAssistant("All done.", 6_000, total = 200))
        s.save("conv-2", extended)
        val after = file.readText()

        assertTrue(after.startsWith(before), "append-only: old bytes must be a prefix")
        val newLines = after.removePrefix(before).trim().lines()
        assertEquals(2, newLines.size, "only the two new messages appended")
        assertEquals("message", KiJson.readMap(newLines[0])["type"])
    }

    @Test fun `identical save is a no-op`() {
        val d = dir()
        val s = store(d)
        s.save("conv-3", turnRows("ok"))
        val file = Files.list(d).toList().first()
        val before = file.readText()
        s.save("conv-3", turnRows("ok"))
        assertEquals(before, file.readText())
    }

    // ── load / round-trip ───────────────────────────────────────────────────────────

    @Test fun `load returns the koog conversation including the sidecar system prompt`() {
        val d = dir()
        val s = store(d)
        s.save("conv-4", turnRows("Working on it."))
        val rows = s.load("conv-4")

        assertEquals("System", rows[0].role)
        assertTrue(MessageCodec.decode(rows[0].json) is Message.System)
        assertEquals(listOf("System", "User", "Assistant", "User", "Assistant"), rows.map { it.role })

        val user = MessageCodec.decode(rows[1].json) as Message.User
        assertEquals("run the thing", (user.parts[0] as MessagePart.Text).text)

        val toolResult = MessageCodec.decode(rows[3].json) as Message.User
        val result = toolResult.parts[0] as MessagePart.Tool.Result
        assertEquals("bash", result.tool)
        assertEquals("file.txt", result.output)
        assertTrue(!result.isError)

        val assistant = MessageCodec.decode(rows[2].json) as Message.Assistant
        assertEquals("tool_calls", assistant.finishReason)
        assertEquals("deepseek-v4-flash", assistant.metaInfo.modelId)
        assertEquals(120, assistant.metaInfo.totalTokensCount)
    }

    @Test fun `round-trip ki save load save is stable`() {
        val d = dir()
        val s = store(d)
        val rows = turnRows("Working on it.")
        s.save("conv-5", rows)
        val reloaded = s.load("conv-5")
        // re-save what was loaded (a fresh process would do exactly this) — file must not change
        val file = Files.list(d).toList().first { it.toString().endsWith(".jsonl") }
        val before = file.readText()
        s.save("conv-5", reloaded)
        assertEquals(before, file.readText())
    }

    // ── compaction synthesis ────────────────────────────────────────────────────────

    @Test fun `history compression produces a compaction entry and the replay matches`() {
        val d = dir()
        val s = store(d)
        // five exchanges on disk
        val rows = mutableListOf<StoredMessage>()
        rows += turnRows("turn-1")
        for (i in 2..5) {
            rows += koogUser("question-$i", i * 10_000L)
            rows += koogAssistant("answer-$i", i * 10_000L + 1_000, total = i * 1_000)
        }
        s.save("conv-6", rows)

        // koog compresses: [System, TL;DR assistant, last-2 exchanges]
        val tldr = koogAssistant("TL;DR: user asked questions 1-5, all answered.", 60_000, total = 9_000)
        val compressed = mutableListOf(tldr)
        compressed += koogUser("question-4", 40_000L)
        compressed += koogAssistant("answer-4", 41_000L, total = 4_000)
        compressed += koogUser("question-5", 50_000L)
        compressed += koogAssistant("answer-5", 51_000L, total = 5_000)
        s.save("conv-6", listOf(rows.first()) + compressed) // rows.first() = System

        val file = Files.list(d).toList().first { it.toString().endsWith(".jsonl") }
        val lines = file.readText().trim().lines()
        val compaction = lines.map { KiJson.readMap(it) }.last { it["type"] == "compaction" }
        assertEquals("TL;DR: user asked questions 1-5, all answered.", compaction["summary"])
        assertTrue((compaction["tokensBefore"] as Number).toLong() > 0)
        val firstKept = compaction["firstKeptEntryId"] as String

        // replay: load returns [System, summary-user, kept..., tail...] — pi semantics
        val loaded = s.load("conv-6")
        assertEquals("System", loaded[0].role)
        val summaryMsg = MessageCodec.decode(loaded[1].json) as Message.User
        val summaryText = (summaryMsg.parts[0] as MessagePart.Text).text
        assertTrue(summaryText.startsWith("The conversation history before this point was compacted"))
        assertTrue(summaryText.contains("TL;DR: user asked questions 1-5"))
        // summary-user + kept question-4/answer-4/question-5/answer-5
        assertEquals(listOf("System", "User", "User", "Assistant", "User", "Assistant"), loaded.map { it.role })

        // firstKeptEntryId points at the OLD entry holding question-4
        fun textOf(message: Map<*, *>?): String = when (val c = message?.get("content")) {
            is String -> c
            is List<*> -> c.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("\n")
            else -> ""
        }
        val q4 = lines.map { KiJson.readMap(it) }
            .first { it["type"] == "message" && textOf(it["message"] as? Map<*, *>).contains("question-4") }
        assertEquals(q4["id"], firstKept)

        // the compaction replay must expose exactly the compressed list
        val replay = s.load("conv-6").drop(1) // drop sidecar System
        assertEquals(5, replay.size, "summary + 4 kept messages")
    }

    @Test fun `post-compaction save appends after the compaction entry`() {
        val d = dir()
        val s = store(d)
        val rows = mutableListOf<StoredMessage>()
        rows += turnRows("turn-1")
        for (i in 2..5) {
            rows += koogUser("q-$i", i * 10_000L)
            rows += koogAssistant("a-$i", i * 10_000L + 1_000, total = i * 1_000)
        }
        s.save("conv-7", listOf(rows.first()) + rows.drop(1))
        val tldr = koogAssistant("TL;DR early.", 60_000, total = 9_000)
        val compressed = listOf(tldr, koogUser("q-5", 50_000L), koogAssistant("a-5", 51_000L, total = 5_000))
        s.save("conv-7", listOf(rows.first()) + compressed)

        // a new turn after compaction: koog's next store() gets the list as loaded from the
        // replay (summary-user message) plus the new exchange
        val replayed = s.load("conv-7")
        s.save("conv-7", replayed + listOf(koogUser("q-6", 70_000L), koogAssistant("a-6", 71_000L, total = 6_000)))
        val file = Files.list(d).toList().first { it.toString().endsWith(".jsonl") }
        val types = file.readText().trim().lines().map { KiJson.readMap(it)["type"] }
        // exactly one compaction; the last two entries are the new post-compaction messages
        assertEquals(1, types.count { it == "compaction" })
        val lastTwo = file.readText().trim().lines().takeLast(2).map { KiJson.readMap(it)["message"] as Map<*, *> }
        assertEquals(
            listOf("q-6", "a-6"),
            lastTwo.map { m ->
                (m["content"] as? String)
                    ?: ((m["content"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("text")
            },
        )
    }

    // ── cross-resume: real pi-written file ──────────────────────────────────────────

    @Test fun `loads a real pi session file with model_change and thinking blocks`() {
        val fixture = Path.of("src/test/resources/pi-sessions/2026-07-21T09-49-59-077Z_019f8415-09a5-7230-adf8-b72d0f1e86fc.jsonl")
        val d = dir()
        Files.copy(fixture, d.resolve(fixture.fileName.toString()))
        val s = PiJsonlSessionStore(d, Path.of("/Users/hq-vdxck792py/IdeaProjects/knowledge-base"))

        val sessions = s.listSessions()
        assertEquals(1, sessions.size)
        assertEquals("019f8415-09a5-7230-adf8-b72d0f1e86fc", sessions[0].conversationId)

        val rows = s.load(sessions[0].conversationId)
        // no sidecar in the fixture dir → no System row
        assertEquals("User", rows[0].role)
        val roles = rows.map { it.role }
        assertTrue("Assistant" in roles && "User" in roles)
        // the tool call round-trip
        val toolResult = rows.map { MessageCodec.decode(it.json) }
            .filterIsInstance<Message.User>()
            .firstOrNull { m -> m.parts.any { it is MessagePart.Tool.Result } }
        assertTrue(toolResult != null)
        val tr = toolResult!!.parts.filterIsInstance<MessagePart.Tool.Result>().first()
        assertEquals("shifts_analytics_daemon_mgmt", tr.tool)
        assertTrue(tr.output.contains("stopped"))

        // assistant entries carry the original pi usage (metadata passthrough)
        val assistant = rows.map { MessageCodec.decode(it.json) }.filterIsInstance<Message.Assistant>().first()
        val usage = PiMessageCodec.toPiMessages(assistant, PiMessageCodec.AssistantIdentity("p", "a", "m"))[0]["usage"] as Map<*, *>
        assertTrue((usage["totalTokens"] as Number).toInt() > 0)
        assertEquals("deepseek-v4-flash:max", assistant.metaInfo.modelId)
    }

    // ── orphaned tool-call healing ──────────────────────────────────────────────

    /**
     * Regression for bot session nNWb3vJMKrv2THW3E: a turn crashed AFTER the assistant
     * message was persisted but BEFORE the tool ran, leaving an assistant message with a
     * toolCall part that has no matching toolResult. On the next resume the model saw the
     * dangling call and just promised more text ("Продолжаю. Проверю...") without ever
     * calling the tool — the conversation was stuck in a promise loop. `load` must heal
     * the history by inserting a synthetic tool result for every unpaired call.
     */
    @Test fun `load heals an orphaned tool call left by a crashed turn`() {
        fun koog(m: Message) = StoredMessage(0, m.role.name, MessageCodec.encode(m))
        val user = Message.User(
            parts = listOf(MessagePart.Text("сделай выгрузку")),
            metaInfo = RequestMeta(1_000),
        )
        val assistant = Message.Assistant(
            parts = listOf(
                MessagePart.Text("Эта таблица — не то. Сначала посмотрю схему."),
                MessagePart.Tool.Call(id = "call_lost", tool = "shifts_db_query", args = "{}"),
                MessagePart.Text("Собираю результат."),
            ),
            metaInfo = ResponseMeta(2_000, input = 100, output = 20, total = 120),
            finishReason = "tool_calls",
        )
        val nextUser = Message.User(
            parts = listOf(MessagePart.Text("продолжай")),
            metaInfo = RequestMeta(3_000),
        )
        val store = store()
        val id = "heal-1"
        store.save(id, listOf(user, assistant, nextUser).map { koog(it) })

        val loaded = store.load(id)

        assertEquals(4, loaded.size, "expected the dangling call healed with a synthetic result")
        val healed = MessageCodec.decode(loaded[2].json)
        assertTrue(healed is Message.User, "synthetic result must be a tool-result user message")
        val resultPart = healed.parts.filterIsInstance<MessagePart.Tool.Result>().single()
        assertEquals("call_lost", resultPart.id)
        assertEquals("shifts_db_query", resultPart.tool)
        assertTrue(resultPart.isError, "the interrupted call never produced a real result")
        val followUp = MessageCodec.decode(loaded[3].json)
        assertTrue(followUp is Message.User && followUp.parts.any { it is MessagePart.Text && it.text == "продолжай" })
    }

    @Test fun `load leaves paired tool calls untouched`() {
        val store = store()
        val id = "heal-2"
        store.save(id, turnRows("working"))

        val loaded = store.load(id)

        assertEquals(5, loaded.size, "a properly paired call must not gain a synthetic result")
    }
}
