package dev.ki.cli.store

import dev.ki.store.CheckpointStore
import dev.ki.store.StoredCheckpoint
import java.sql.Connection

/**
 * Local [CheckpointStore] over the **same** SQLite connection as [SqliteSessionStore]
 * (passed in, not opened here) — a second connection to one `.ki/ki.db` would contend,
 * since checkpoints are written after every graph node. Both stores synchronize on
 * [connection], so it is the single write monitor; the shape mirrors the message store.
 *
 * The DDL is deliberately portable (plain `TEXT`/`INTEGER`) so the Spring/Postgres
 * reference impl (`JdbcTemplateCheckpointStore`) shares the exact same shape.
 */
class SqliteCheckpointStore(private val connection: Connection) : CheckpointStore {

    init {
        synchronized(connection) {
            connection.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS ki_checkpoint (
                        conversation_id TEXT    NOT NULL,
                        checkpoint_id   TEXT    NOT NULL,
                        version         INTEGER NOT NULL,
                        created_at      INTEGER NOT NULL,
                        checkpoint_json TEXT    NOT NULL,
                        PRIMARY KEY (conversation_id, checkpoint_id)
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override fun load(sessionId: String): List<StoredCheckpoint> = synchronized(connection) {
        val out = ArrayList<StoredCheckpoint>()
        connection.prepareStatement(
            "SELECT checkpoint_id, version, created_at, checkpoint_json " +
                "FROM ki_checkpoint WHERE conversation_id = ? ORDER BY version"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(
                        StoredCheckpoint(
                            checkpointId = rs.getString("checkpoint_id"),
                            version = rs.getLong("version"),
                            createdAt = rs.getLong("created_at"),
                            json = rs.getString("checkpoint_json"),
                        )
                    )
                }
            }
        }
        return out
    }

    override fun save(sessionId: String, checkpoint: StoredCheckpoint): Unit = synchronized(connection) {
        connection.prepareStatement(
            """
            INSERT INTO ki_checkpoint (conversation_id, checkpoint_id, version, created_at, checkpoint_json)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(conversation_id, checkpoint_id) DO UPDATE SET
                version = excluded.version,
                created_at = excluded.created_at,
                checkpoint_json = excluded.checkpoint_json
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, checkpoint.checkpointId)
            ps.setLong(3, checkpoint.version)
            ps.setLong(4, checkpoint.createdAt)
            ps.setString(5, checkpoint.json)
            ps.executeUpdate()
        }
    }

    override fun latest(sessionId: String): StoredCheckpoint? = synchronized(connection) {
        connection.prepareStatement(
            "SELECT checkpoint_id, version, created_at, checkpoint_json " +
                "FROM ki_checkpoint WHERE conversation_id = ? ORDER BY version DESC LIMIT 1"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) StoredCheckpoint(
                    checkpointId = rs.getString("checkpoint_id"),
                    version = rs.getLong("version"),
                    createdAt = rs.getLong("created_at"),
                    json = rs.getString("checkpoint_json"),
                ) else null
            }
        }
    }

    override fun delete(sessionId: String): Unit = synchronized(connection) {
        connection.prepareStatement("DELETE FROM ki_checkpoint WHERE conversation_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeUpdate()
        }
    }
}
