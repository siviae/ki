package dev.ki.store.spring

import dev.ki.store.CheckpointStore
import dev.ki.store.StoredCheckpoint
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Remote/reference [CheckpointStore] for the Spring deployment: the same checkpoint
 * schema as the local SQLite store, over Spring [JdbcTemplate] on the host app's
 * `DataSource` (typically Postgres). This is the store M10 fails over across nodes —
 * checkpoints written by the node owning a session, replayed by whichever node takes
 * over. SQL is the portable subset shared with `SqliteCheckpointStore`.
 *
 * Writes are single-statement (append / delete) so no explicit transaction is needed —
 * consistent with M10's "avoid long-running transactions" discipline.
 */
open class JdbcTemplateCheckpointStore(
    private val jdbc: JdbcTemplate,
    createSchema: Boolean = true,
) : CheckpointStore {

    init {
        if (createSchema) {
            jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS ki_checkpoint (
                    conversation_id VARCHAR NOT NULL,
                    checkpoint_id   VARCHAR NOT NULL,
                    version         BIGINT  NOT NULL,
                    created_at      BIGINT  NOT NULL,
                    checkpoint_json TEXT    NOT NULL,
                    PRIMARY KEY (conversation_id, checkpoint_id)
                )
                """.trimIndent()
            )
        }
    }

    override fun load(sessionId: String): List<StoredCheckpoint> =
        jdbc.query(
            "SELECT checkpoint_id, version, created_at, checkpoint_json " +
                "FROM ki_checkpoint WHERE conversation_id = ? ORDER BY version",
            { rs, _ ->
                StoredCheckpoint(
                    checkpointId = rs.getString("checkpoint_id"),
                    version = rs.getLong("version"),
                    createdAt = rs.getLong("created_at"),
                    json = rs.getString("checkpoint_json"),
                )
            },
            sessionId,
        )

    override fun save(sessionId: String, checkpoint: StoredCheckpoint) {
        jdbc.update(
            """
            INSERT INTO ki_checkpoint (conversation_id, checkpoint_id, version, created_at, checkpoint_json)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (conversation_id, checkpoint_id) DO UPDATE SET
                version = EXCLUDED.version,
                created_at = EXCLUDED.created_at,
                checkpoint_json = EXCLUDED.checkpoint_json
            """.trimIndent(),
            sessionId, checkpoint.checkpointId, checkpoint.version, checkpoint.createdAt, checkpoint.json,
        )
    }

    override fun latest(sessionId: String): StoredCheckpoint? =
        jdbc.query(
            "SELECT checkpoint_id, version, created_at, checkpoint_json " +
                "FROM ki_checkpoint WHERE conversation_id = ? ORDER BY version DESC LIMIT 1",
            { rs, _ ->
                StoredCheckpoint(
                    checkpointId = rs.getString("checkpoint_id"),
                    version = rs.getLong("version"),
                    createdAt = rs.getLong("created_at"),
                    json = rs.getString("checkpoint_json"),
                )
            },
            sessionId,
        ).firstOrNull()

    override fun delete(sessionId: String) {
        jdbc.update("DELETE FROM ki_checkpoint WHERE conversation_id = ?", sessionId)
    }
}
