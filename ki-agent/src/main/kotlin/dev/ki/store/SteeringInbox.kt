package dev.ki.store

/** One steering message: extra input for a running session, written by any node, keyed by session. */
data class SteeringMessage(val seq: Long, val payload: String)

/**
 * Out-of-band input delivery for a running session — the M10 seam ki-agent exposes and the
 * `ki-cluster` Postgres module implements (a durable inbox table). **Any** node [write]s a
 * steering row for a session; the node that currently **owns** that session (see
 * [SessionOwnership]) [drain]s and applies it to the model at the next step boundary.
 *
 * This is the one mechanism behind two M11 cases: a mid-session user follow-up, and a
 * RocketChat thread continuation (both are just steering for an existing session).
 *
 * Delivery is **at the next step/node boundary**, not mid-token — koog has no seam to inject
 * into an already-running node. Writes and the drain-and-mark are **short transactions**
 * (insert / `... consumed_at IS NULL` update); an optional LISTEN/NOTIFY signal can replace
 * polling but stays a signal, never the payload.
 */
interface SteeringInbox {
    /** Append a steering message for [sessionId] (callable from any node). */
    fun write(sessionId: String, payload: String)

    /**
     * Atomically take and mark-consumed all pending steering for [sessionId], in order.
     * Returns empty when there is none. Only the session's owner should call this.
     */
    fun drain(sessionId: String): List<SteeringMessage>
}
