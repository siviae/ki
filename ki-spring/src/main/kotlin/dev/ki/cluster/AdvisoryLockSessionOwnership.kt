package dev.ki.cluster

import dev.ki.store.SessionOwnership
import java.sql.Connection
import javax.sql.DataSource

/**
 * Postgres [SessionOwnership] via **session-level advisory locks** — the M10 failover primitive.
 *
 * Each owned session pins a **dedicated [Connection] in autocommit** holding
 * `pg_try_advisory_lock(key)`. This deliberately avoids `pg_advisory_xact_lock`, which would
 * need an open transaction for the whole ownership lifetime (the long transaction M10 forbids).
 * Session-level locks give the two properties failover needs:
 *  - **mutual exclusion** — only one node's connection holds a session's lock;
 *  - **auto-release on crash** — if the owning node dies, Postgres drops the connection and the
 *    lock, so another node's [tryClaim] then succeeds. No heartbeat/liveness table required.
 *
 * The held connection is *not* returned to the pool while owned (a pooled connection could be
 * handed to another thread that would appear to hold the lock). So concurrent ownership is
 * bounded by available connections — pass a dedicated ownership [DataSource], sized to the
 * node's target sessions-per-node, distinct from the app's main pool.
 *
 * Claims/releases are serialized ([lock]) so two threads never open two connections for the same
 * session on this node; per-node throughput here is low (ownership changes, not per-message).
 */
class AdvisoryLockSessionOwnership(
    private val dataSource: DataSource,
) : SessionOwnership, AutoCloseable {

    private val held = HashMap<String, Connection>()
    private val lock = Any()

    override fun tryClaim(sessionId: String): Boolean = synchronized(lock) {
        if (held.containsKey(sessionId)) return true
        val conn = dataSource.connection
        try {
            conn.autoCommit = true
            val acquired = conn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { ps ->
                ps.setLong(1, AdvisoryKeys.of(sessionId))
                ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
            if (acquired) {
                held[sessionId] = conn
                true
            } else {
                conn.close()
                false
            }
        } catch (e: Exception) {
            runCatching { conn.close() }
            throw e
        }
    }

    override fun release(sessionId: String): Unit = synchronized(lock) {
        val conn = held.remove(sessionId) ?: return
        try {
            conn.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                ps.setLong(1, AdvisoryKeys.of(sessionId))
                ps.executeQuery().use { /* drain result */ it.next() }
            }
        } finally {
            runCatching { conn.close() }
        }
    }

    override fun isOwner(sessionId: String): Boolean = synchronized(lock) { held.containsKey(sessionId) }

    override fun owned(): Set<String> = synchronized(lock) { held.keys.toSet() }

    /** Release every held session (unlocks + returns all ownership connections). */
    override fun close(): Unit = synchronized(lock) {
        for (sessionId in held.keys.toList()) release(sessionId)
    }
}
