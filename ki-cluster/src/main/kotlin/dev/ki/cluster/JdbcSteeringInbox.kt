package dev.ki.cluster

import dev.ki.store.SteeringInbox
import dev.ki.store.SteeringMessage
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Postgres [SteeringInbox] over Spring [JdbcTemplate] — a durable inbox table any node writes
 * to and the session's owner drains. Both operations are **single statements** (short, no
 * explicit transaction), consistent with M10's "avoid long-running transactions".
 *
 * [drain] is an atomic take-and-mark via `UPDATE … WHERE consumed_at IS NULL RETURNING`: the
 * UPDATE row-locks matched rows, so even a racing second drain (or a stray non-owner) sees them
 * already consumed and gets nothing — the owner still applies each message exactly once.
 * `RETURNING` order is unspecified, so results are sorted by [SteeringMessage.seq] here.
 *
 * Postgres-only (identity column, `RETURNING`); covered by the Testcontainers IT, not the
 * offline SQLite tests.
 */
open class JdbcSteeringInbox(
    private val jdbc: JdbcTemplate,
    createSchema: Boolean = true,
) : SteeringInbox {

    init {
        if (createSchema) {
            jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS ki_steering (
                    seq         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    session_id  VARCHAR NOT NULL,
                    payload     TEXT    NOT NULL,
                    consumed_at BIGINT
                )
                """.trimIndent()
            )
            jdbc.execute(
                "CREATE INDEX IF NOT EXISTS ki_steering_pending " +
                    "ON ki_steering (session_id) WHERE consumed_at IS NULL"
            )
        }
    }

    override fun write(sessionId: String, payload: String) {
        jdbc.update("INSERT INTO ki_steering (session_id, payload) VALUES (?, ?)", sessionId, payload)
    }

    override fun drain(sessionId: String): List<SteeringMessage> =
        jdbc.query(
            """
            UPDATE ki_steering SET consumed_at = ?
            WHERE session_id = ? AND consumed_at IS NULL
            RETURNING seq, payload
            """.trimIndent(),
            { rs, _ -> SteeringMessage(rs.getLong("seq"), rs.getString("payload")) },
            System.currentTimeMillis(), sessionId,
        ).sortedBy { it.seq }
}
