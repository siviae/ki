package dev.ki.cli.store

import dev.ki.store.SessionInfo
import dev.ki.store.SessionStore
import dev.ki.store.StoredMessage
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Local [SessionStore] backed by an embedded SQLite file via `sqlite-jdbc` — the
 * lightweight deployment: a single `.db` file, no server, no Spring, no external DB.
 * Raw JDBC (no JDBI/ORM); the schema is two small tables created on first open.
 *
 * The DDL is deliberately portable (plain `TEXT`/`INTEGER`) so the Spring/Postgres
 * reference impl shares the exact same shape.
 */
class SqliteSessionStore(dbPath: Path) : SessionStore, AutoCloseable {

    private val connection: Connection

    init {
        dbPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        connection.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS ki_message (
                    conversation_id TEXT    NOT NULL,
                    seq             INTEGER NOT NULL,
                    role            TEXT    NOT NULL,
                    message_json    TEXT    NOT NULL,
                    PRIMARY KEY (conversation_id, seq)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS ki_session (
                    conversation_id TEXT    NOT NULL PRIMARY KEY,
                    created_at      INTEGER NOT NULL,
                    updated_at      INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    @Synchronized
    override fun load(conversationId: String): List<StoredMessage> {
        val out = ArrayList<StoredMessage>()
        connection.prepareStatement(
            "SELECT seq, role, message_json FROM ki_message WHERE conversation_id = ? ORDER BY seq"
        ).use { ps ->
            ps.setString(1, conversationId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(StoredMessage(rs.getInt("seq"), rs.getString("role"), rs.getString("message_json")))
                }
            }
        }
        return out
    }

    @Synchronized
    override fun save(conversationId: String, messages: List<StoredMessage>) {
        val now = System.currentTimeMillis()
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement("DELETE FROM ki_message WHERE conversation_id = ?").use { ps ->
                ps.setString(1, conversationId); ps.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO ki_message (conversation_id, seq, role, message_json) VALUES (?, ?, ?, ?)"
            ).use { ps ->
                for (m in messages) {
                    ps.setString(1, conversationId); ps.setInt(2, m.seq)
                    ps.setString(3, m.role); ps.setString(4, m.json)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            connection.prepareStatement(
                """
                INSERT INTO ki_session (conversation_id, created_at, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(conversation_id) DO UPDATE SET updated_at = excluded.updated_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, conversationId); ps.setLong(2, now); ps.setLong(3, now)
                ps.executeUpdate()
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback(); throw e
        } finally {
            connection.autoCommit = autoCommit
        }
    }

    @Synchronized
    override fun listSessions(): List<SessionInfo> {
        val out = ArrayList<SessionInfo>()
        connection.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT s.conversation_id AS cid, s.updated_at AS updated_at,
                       (SELECT COUNT(*) FROM ki_message m WHERE m.conversation_id = s.conversation_id) AS n
                FROM ki_session s
                ORDER BY s.updated_at DESC
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) {
                    out.add(SessionInfo(rs.getString("cid"), rs.getLong("updated_at"), rs.getInt("n")))
                }
            }
        }
        return out
    }

    override fun close() = connection.close()
}
