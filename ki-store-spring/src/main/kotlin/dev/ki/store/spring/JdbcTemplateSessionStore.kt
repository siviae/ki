package dev.ki.store.spring

import dev.ki.store.SessionInfo
import dev.ki.store.SessionStore
import dev.ki.store.StoredMessage
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

/**
 * Remote/reference [SessionStore] for the Spring deployment: the same session schema
 * as the local SQLite store, accessed through Spring [JdbcTemplate] over the host
 * application's `DataSource` (typically Postgres). ki adds one dependency and the
 * agent persists into the app's own database — no separate service.
 *
 * The DDL is created idempotently on construction so integration is drop-in; a host
 * that manages schema with its own Liquibase/Flyway can skip that (the
 * `CREATE TABLE IF NOT EXISTS` is then a harmless no-op). SQL is deliberately the
 * portable subset shared with the SQLite store.
 */
open class JdbcTemplateSessionStore(
    private val jdbc: JdbcTemplate,
    createSchema: Boolean = true,
) : SessionStore {

    init {
        if (createSchema) {
            jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS ki_message (
                    conversation_id VARCHAR NOT NULL,
                    seq             INTEGER NOT NULL,
                    role            VARCHAR NOT NULL,
                    message_json    TEXT    NOT NULL,
                    PRIMARY KEY (conversation_id, seq)
                )
                """.trimIndent()
            )
            jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS ki_session (
                    conversation_id VARCHAR NOT NULL PRIMARY KEY,
                    created_at      BIGINT  NOT NULL,
                    updated_at      BIGINT  NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    override fun load(conversationId: String): List<StoredMessage> =
        jdbc.query(
            "SELECT seq, role, message_json FROM ki_message WHERE conversation_id = ? ORDER BY seq",
            { rs, _ -> StoredMessage(rs.getInt("seq"), rs.getString("role"), rs.getString("message_json")) },
            conversationId,
        )

    @Transactional
    override fun save(conversationId: String, messages: List<StoredMessage>) {
        val now = System.currentTimeMillis()
        jdbc.update("DELETE FROM ki_message WHERE conversation_id = ?", conversationId)
        jdbc.batchUpdate(
            "INSERT INTO ki_message (conversation_id, seq, role, message_json) VALUES (?, ?, ?, ?)",
            messages.map { arrayOf<Any>(conversationId, it.seq, it.role, it.json) },
        )
        jdbc.update(
            """
            INSERT INTO ki_session (conversation_id, created_at, updated_at) VALUES (?, ?, ?)
            ON CONFLICT (conversation_id) DO UPDATE SET updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            conversationId, now, now,
        )
    }

    override fun listSessions(): List<SessionInfo> =
        jdbc.query(
            """
            SELECT s.conversation_id AS cid, s.updated_at AS updated_at,
                   (SELECT COUNT(*) FROM ki_message m WHERE m.conversation_id = s.conversation_id) AS n
            FROM ki_session s
            ORDER BY s.updated_at DESC
            """.trimIndent(),
        ) { rs, _ -> SessionInfo(rs.getString("cid"), rs.getLong("updated_at"), rs.getInt("n")) }
}
