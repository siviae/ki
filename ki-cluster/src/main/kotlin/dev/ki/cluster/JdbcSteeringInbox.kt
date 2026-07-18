package dev.ki.cluster

import dev.ki.store.SteeringInbox
import dev.ki.store.SteeringMessage
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Postgres [SteeringInbox] over Spring [JdbcTemplate] — a durable inbox table any node writes
 * to and the session's owner drains. Every operation is a **single statement** (short, no
 * explicit transaction), consistent with M10's "avoid long-running transactions".
 *
 * Consume ordering is **peek-then-mark** (not take-and-mark): [peek] reads without marking, and
 * [markConsumed] runs only after the turn succeeds — so a mid-turn crash leaves the rows
 * unconsumed for another node to re-run (at-least-once). Correct without an atomic take because
 * [dev.ki.store.SessionOwnership] guarantees only the owner touches a given session.
 *
 * Postgres-only (identity column); covered by the Testcontainers IT, not the offline SQLite tests.
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

    override fun peek(sessionId: String): List<SteeringMessage> =
        jdbc.query(
            "SELECT seq, payload FROM ki_steering " +
                "WHERE session_id = ? AND consumed_at IS NULL ORDER BY seq",
            { rs, _ -> SteeringMessage(rs.getLong("seq"), rs.getString("payload")) },
            sessionId,
        )

    override fun markConsumed(sessionId: String, throughSeq: Long) {
        jdbc.update(
            "UPDATE ki_steering SET consumed_at = ? " +
                "WHERE session_id = ? AND consumed_at IS NULL AND seq <= ?",
            System.currentTimeMillis(), sessionId, throughSeq,
        )
    }

    override fun pendingSessions(limit: Int): List<String> =
        jdbc.query(
            "SELECT session_id FROM ki_steering WHERE consumed_at IS NULL " +
                "GROUP BY session_id ORDER BY MIN(seq) LIMIT ?",
            { rs, _ -> rs.getString("session_id") },
            limit,
        )
}
