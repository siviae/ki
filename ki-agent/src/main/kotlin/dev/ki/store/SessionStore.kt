package dev.ki.store

/**
 * One persisted message row. [role] is koog `Message.Role.name` (kept as a plain
 * column for cheap filtering / debugging); [json] is the opaque serialized koog
 * `Message` — implementations never parse it.
 */
data class StoredMessage(val seq: Int, val role: String, val json: String)

/** Summary of a stored session. [SessionStore.listSessions] returns these newest-first. */
data class SessionInfo(val conversationId: String, val updatedAt: Long, val messageCount: Int)

/**
 * Storage SPI for chat sessions — the compatibility seam between deployments.
 *
 * The agent owns this contract; each deployment supplies an implementation: the CLI
 * backs it with embedded SQLite (`sqlite-jdbc`, no Spring), a host Spring app backs
 * it with Postgres via `JdbcTemplate`. Implementations deal only in [StoredMessage]
 * and never touch koog.
 *
 * [save] replaces the whole conversation: koog's chat-memory feature hands the full
 * message list on every store, so persistence is a wholesale replace, not an append.
 */
interface SessionStore {
    /** Load a conversation's messages ordered by `seq`, or empty if none. */
    fun load(conversationId: String): List<StoredMessage>

    /** Replace all messages for [conversationId] and mark the session updated now. */
    fun save(conversationId: String, messages: List<StoredMessage>)

    /** All known sessions, newest [SessionInfo.updatedAt] first (for `--continue`). */
    fun listSessions(): List<SessionInfo>
}
