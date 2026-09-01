package dev.ki.cli.store

import dev.ki.store.MessageCodec
import dev.ki.store.SessionInfo
import dev.ki.store.SessionStore
import dev.ki.store.StoredMessage
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Session-store SPI implementation persisting conversations as **pi-format JSONL v3**
 * (pi-coding-agent `docs/session-format.md`) — the files pi itself writes under
 * `~/.pi/agent/sessions/<--cwd-slug-->/<timestamp>_<uuid>.jsonl`. This makes ki sessions
 * first-class citizens of pi's ecosystem: the RocketChat bot telemetry reads these files
 * directly, `/resume` lists them, and a session started in pi resumes in ki and back.
 *
 * SPI mapping (see [dev.ki.store.SessionStore]): the SPI hands over the FULL message list
 * on every save (wholesale replace), but this store is append-only — it diffs the incoming
 * list against the messages on the session tree path and appends only the new tail. When
 * the incoming list is SHORTER (koog M6 history compression replaces old messages with a
 * TL;DR assistant message), the drop is synthesized as a pi `compaction` entry:
 * summary = the TL;DR text, `firstKeptEntryId` = the old entry the kept history starts at.
 * pi's `buildSessionContext` replay (summary + kept + after) then matches exactly.
 *
 * The koog System message (base system prompt) has no pi entry equivalent — it lives in
 * the `<session>.system.md` sidecar, mirroring pi's own sidecar written by hats.ts.
 *
 * Not implemented (deliberately): branching writes — ki never branches mid-session; tree
 * branching that EXISTS in a pi-written file is fully honored on load (leaf walk).
 */
class PiJsonlSessionStore(
    /** Directory holding the `<timestamp>_<uuid>.jsonl` files (pi's per-cwd slug dir). */
    private val sessionDir: Path,
    /** Working directory recorded in the session header. */
    private val cwd: Path,
    /** pi provider name for assistant entries written by ki (e.g. "ai.ecom.tech"). */
    private val provider: String = "ki",
    /** pi api name for assistant entries (e.g. "openai-completions"). */
    private val api: String = "openai-completions",
) : SessionStore {
    private val states = HashMap<String, SessionState>()
    private val lock = Any()

    // ── SessionStore ────────────────────────────────────────────────────────────────

    override fun load(conversationId: String): List<StoredMessage> = synchronized(lock) {
        val st = state(conversationId)
        val rows = ArrayList<StoredMessage>()

        // The base system prompt rides in the sidecar (pi parity — no system entries in JSONL).
        val sidecar = st.sidecarPath
        if (Files.exists(sidecar)) {
            val text = Files.readString(sidecar).trim()
            if (text.isNotEmpty()) {
                rows += StoredMessage(0, "System", MessageCodec.encode(Message.System(text, RequestMetaInfo(kotlin.time.Instant.fromEpochMilliseconds(0)))))
            }
        }

        for (msg in st.pathMessages) {
            val koog = PiMessageCodec.toKoog(msg) ?: continue
            rows += StoredMessage(rows.size, koog.role.name, MessageCodec.encode(koog))
        }
        rows
    }

    override fun save(conversationId: String, messages: List<StoredMessage>) {
        synchronized(lock) {
            val st = state(conversationId)

            val koog = messages.map { MessageCodec.decode(it.json) }
            val systemPrompt = koog.filterIsInstance<Message.System>().firstOrNull()
                ?.parts?.joinToString("") { it.text }
            val conversation = koog.filter { it !is Message.System }
            val identity = PiMessageCodec.AssistantIdentity(provider, api, defaultModel = "")

            // sidecar: the base system prompt (mirrors pi's hats.ts `.system.md`)
            if (systemPrompt != null) {
                Files.createDirectories(st.sidecarPath.parent)
                Files.writeString(st.sidecarPath, systemPrompt)
            }

            val piMessages = conversation.flatMap { PiMessageCodec.toPiMessages(it, identity) }
            val canonical = piMessages.map { KiJson.write(it) }
            val old = st.pathCanonical
            val common = commonPrefix(old, canonical)

            if (common == canonical.size && old.size == common) return // nothing new

            if (common < old.size) {
                appendCompaction(st, piMessages, canonical, common, old)
            } else {
                for (i in common until canonical.size) appendMessage(st, piMessages[i])
            }
            maybeAppendSessionInfo(st, piMessages)
            st.flush()
        }
    }

    override fun listSessions(): List<SessionInfo> = synchronized(lock) {
        if (!sessionDir.isDirectory()) return emptyList()
        val files = Files.list(sessionDir).toList().filter { it.extension == "jsonl" }
        return files.map { path ->
            val header = readHeader(path)
            val id = (header?.get("id") as? String)
                ?: path.name.removeSuffix(".jsonl").substringAfterLast('_')
            SessionInfo(id, Files.getLastModifiedTime(path).toMillis(), countMessageEntries(path))
        }.sortedByDescending { it.updatedAt }
    }

    // ── save internals ──────────────────────────────────────────────────────────────

    private fun appendMessage(st: SessionState, message: Map<String, Any?>) {
        st.appendEntry(
            linkedMapOf(
                "type" to "message",
                "id" to st.newId(),
                "parentId" to st.leafId,
                "timestamp" to isoNow(),
                "message" to message,
            ),
        )
    }

    /**
     * History shrank or diverged at [common]: koog M6 compression → pi compaction entry.
     * The TL;DR is the first diverging assistant message; kept messages already exist as
     * old entries (pi's replay re-emits them from `firstKeptEntryId`), so only the tail
     * after the last kept message is appended.
     */
    private fun appendCompaction(
        st: SessionState,
        piMessages: List<Map<String, Any?>>,
        canonical: List<String>,
        common: Int,
        old: List<String>,
    ) {
        val tldr = piMessages.getOrNull(common)
        val summary = if (tldr?.get("role") == "assistant") {
            firstText(tldr).ifBlank { "History compressed by the agent runtime." }
        } else {
            "History compressed by the agent runtime."
        }
        val keepStart = if (tldr?.get("role") == "assistant") common + 1 else common

        // Kept messages match the old path (koog keeps the same Message objects).
        // Count how many messages starting at keepStart equal the old tail — those are
        // already on disk; firstKeptEntryId points at the first of them.
        var firstKeptEntryId: String? = null
        var firstNew = keepStart
        if (keepStart < canonical.size) {
            val keptCount = canonical.size - keepStart
            val oldStart = old.size - keptCount
            var k = 0
            while (k < keptCount && oldStart + k >= 0 && old[oldStart + k] == canonical[keepStart + k]) k++
            if (k > 0) {
                firstKeptEntryId = st.pathId(oldStart)
                firstNew = keepStart + k
            }
        }
        val tokensBefore = old.asSequence()
            .mapNotNull { (KiJson.readMap(it)["usage"] as? Map<*, *>)?.get("totalTokens") as? Number }
            .map { it.toLong() }
            .sum()

        st.appendEntry(
            linkedMapOf(
                "type" to "compaction",
                "id" to st.newId(),
                "parentId" to st.leafId,
                "timestamp" to isoNow(),
                "summary" to summary,
                "firstKeptEntryId" to firstKeptEntryId,
                "tokensBefore" to tokensBefore,
            ),
        )
        for (i in firstNew until canonical.size) appendMessage(st, piMessages[i])
    }

    /** `/resume` display name from the first user message (pi otherwise shows the first message). */
    private fun maybeAppendSessionInfo(st: SessionState, piMessages: List<Map<String, Any?>>) {
        if (st.sessionInfoWritten) return
        val firstUser = piMessages.firstOrNull { it["role"] == "user" } ?: return
        val text = firstText(firstUser).replace(Regex("\\s+"), " ").trim().take(80)
        if (text.isEmpty()) return
        st.appendEntry(
            linkedMapOf(
                "type" to "session_info",
                "id" to st.newId(),
                "parentId" to st.leafId,
                "timestamp" to isoNow(),
                "name" to text,
            ),
        )
        st.sessionInfoWritten = true
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private fun state(conversationId: String): SessionState {
        states[conversationId]?.let { return it }
        val file = findFile(conversationId) ?: newFile(conversationId)
        val st = SessionState.open(file, conversationId, cwd)
        states[conversationId] = st
        return st
    }

    private fun findFile(conversationId: String): Path? {
        if (!sessionDir.isDirectory()) return null
        val files = Files.list(sessionDir).toList()
        for (path in files) {
            if (path.extension != "jsonl") continue
            val header = readHeader(path) ?: continue
            if (header["id"] == conversationId) return path
        }
        return null
    }

    private fun newFile(conversationId: String): Path {
        Files.createDirectories(sessionDir)
        return sessionDir.resolve("${isoNow().replace(":", "-")}_$conversationId.jsonl")
    }

    private fun commonPrefix(a: List<String>, b: List<String>): Int {
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        return i
    }

    private fun firstText(message: Map<String, Any?>): String = when (val content = message["content"]) {
        is String -> content
        is List<*> -> content.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("\n")
        else -> ""
    }

    private fun readHeader(path: Path): Map<String, Any?>? = runCatching {
        Files.newBufferedReader(path).use { r ->
            r.readLine()?.takeIf { it.isNotBlank() }?.let { KiJson.readMap(it) }
        }
    }.getOrNull()

    private fun countMessageEntries(path: Path): Int = runCatching {
        Files.newBufferedReader(path).useLines { lines ->
            lines.count { "\"type\": \"message\"" in it || "\"type\":\"message\"" in it }
        }
    }.getOrDefault(0)

    companion object {
        const val SYSTEM_SIDECAR_SUFFIX = ".system.md"

        /** pi's session dir slug: `--<cwd with [/\\:] → ->-->`. */
        fun slugFor(cwd: Path): String =
            "--" + cwd.toString().trimStart('/', '\\').replace(Regex("[/\\\\:]"), "-") + "--"
    }

    // ── per-session state ───────────────────────────────────────────────────────────

    /**
     * One JSONL file's entries plus the derived path view. `pathMessages`/`pathIds`/
     * `pathCanonical` are three parallel lists describing pi's `buildSessionContext` replay
     * from the current leaf: the message maps, the message-entry ids, and their canonical
     * JSON (the diff key). They are rebuilt from the file after every load and flush.
     */
    private class SessionState private constructor(
        val file: Path,
        val conversationId: String,
        private val headerCwd: Path,
    ) {
        private val entries = ArrayList<Map<String, Any?>>()
        private val byId = HashMap<String, Map<String, Any?>>()
        var leafId: String? = null
            private set
        var sessionInfoWritten = false
        private val dirty = ArrayList<Map<String, Any?>>()

        val pathMessages = ArrayList<Map<String, Any?>>()
        val pathIds = ArrayList<String>()
        val pathCanonical = ArrayList<String>()

        val sidecarPath: Path = file.resolveSibling(file.name + SYSTEM_SIDECAR_SUFFIX)

        companion object {
            fun open(file: Path, conversationId: String, cwd: Path): SessionState {
                val st = SessionState(file, conversationId, cwd)
                if (Files.exists(file)) st.read()
                st.rebuildPath()
                return st
            }
        }

        fun read() {
            Files.newBufferedReader(file).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val entry = runCatching { KiJson.readMap(line) }.getOrNull() ?: continue
                    if (entry["type"] == "session") continue
                    entries.add(entry)
                    (entry["id"] as? String)?.let { byId[it] = entry }
                }
            }
            leafId = entries.lastOrNull()?.get("id") as? String
        }

        /** pi `buildSessionContext` port: leaf→root walk with compaction replay. */
        fun rebuildPath() {
            val path = ArrayList<Map<String, Any?>>()
            var cur = leafId?.let { byId[it] }
            while (cur != null) {
                path.add(cur)
                cur = (cur["parentId"] as? String)?.let { byId[it] }
            }
            path.reverse()

            val compaction = path.lastOrNull { it["type"] == "compaction" }
            fun messageOf(e: Map<String, Any?>): Map<String, Any?>? =
                if (e["type"] == "message") e["message"] as? Map<String, Any?> else null

            pathMessages.clear()
            pathIds.clear()
            pathCanonical.clear()
            if (compaction != null) {
                val compactionIdx = path.indexOfFirst { it["id"] == compaction["id"] }
                pathMessages.add(
                    linkedMapOf(
                        "role" to "user",
                        "content" to listOf(
                            linkedMapOf(
                                "type" to "text",
                                "text" to COMPACTION_SUMMARY_PREFIX + (compaction["summary"] ?: "") + COMPACTION_SUMMARY_SUFFIX,
                            ),
                        ),
                        "timestamp" to entryMillis(compaction),
                    ),
                )
                pathIds.add(compaction["id"] as? String ?: "")
                var foundFirstKept = false
                for (i in 0 until compactionIdx) {
                    if (path[i]["id"] == compaction["firstKeptEntryId"]) foundFirstKept = true
                    if (foundFirstKept) {
                        messageOf(path[i])?.let {
                            pathMessages.add(it)
                            pathIds.add(path[i]["id"] as? String ?: "")
                        }
                    }
                }
                for (i in compactionIdx + 1 until path.size) {
                    messageOf(path[i])?.let {
                        pathMessages.add(it)
                        pathIds.add(path[i]["id"] as? String ?: "")
                    }
                }
            } else {
                for (e in path) {
                    messageOf(e)?.let {
                        pathMessages.add(it)
                        pathIds.add(e["id"] as? String ?: "")
                    }
                }
            }
            for (msg in pathMessages) pathCanonical.add(KiJson.write(msg))
        }

        fun pathId(index: Int): String? = pathIds.getOrNull(index)

        fun appendEntry(entry: Map<String, Any?>) {
            entries.add(entry)
            (entry["id"] as? String)?.let { byId[it] = entry }
            leafId = entry["id"] as? String
            dirty.add(entry)
        }

        fun newId(): String {
            while (true) {
                val id = UUID.randomUUID().toString().substring(0, 8)
                if (byId.containsKey(id)) continue
                // also avoid ids already handed out in this batch
                if (dirty.none { it["id"] == id }) return id
            }
        }

        fun flush() {
            if (dirty.isEmpty()) return
            Files.createDirectories(file.parent)
            if (!Files.exists(file)) writeHeader()
            Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { w ->
                for (entry in dirty) {
                    w.write(KiJson.write(entry))
                    w.write("\n")
                }
            }
            dirty.clear()
            rebuildPath()
        }

        private fun writeHeader() {
            val header = linkedMapOf<String, Any?>(
                "type" to "session",
                "version" to 3,
                "id" to conversationId,
                "timestamp" to isoNow(),
                "cwd" to headerCwd.toString(),
            )
            Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { w ->
                w.write(KiJson.write(header))
                w.write("\n")
            }
        }
    }
}

private fun isoNow(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

private fun entryMillis(entry: Map<String, Any?>): Long = runCatching {
    Instant.parse(entry["timestamp"] as? String ?: "").toEpochMilli()
}.getOrDefault(0L)

/** pi compaction summary framing — byte-identical to pi-coding-agent core/messages.js. */
private const val COMPACTION_SUMMARY_PREFIX =
    "The conversation history before this point was compacted into the following summary:\n\n<summary>\n"
private const val COMPACTION_SUMMARY_SUFFIX = "\n</summary>"
