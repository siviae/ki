package dev.ki.store

/** One steering message: extra input for a running session, written by any node, keyed by session. */
data class SteeringMessage(val seq: Long, val payload: String)

/**
 * Out-of-band input delivery for a session — the M10 seam ki-agent exposes and the
 * `ki-cluster` Postgres module implements (a durable inbox table). It is the **per-session
 * message queue** for every message after the first: **any** node [write]s a row, and the
 * node that runs the session's next turn drains it. This one mechanism covers both M11 cases —
 * a mid-session user follow-up and a RocketChat thread continuation.
 *
 * **Consume ordering is peek-then-run-then-mark, not take-and-mark.** [peek] reads unconsumed
 * rows **without** marking them; the owner runs the turn; only on success does it call
 * [markConsumed]. If the node crashes mid-turn nothing is marked, the advisory lock
 * auto-releases, and another node re-peeks — at-least-once, reconciled with the M9 checkpoint
 * (which captures the message into history during the turn). Marking at read time instead
 * would lose the message on a mid-turn crash. Safe without atomic take because
 * [SessionOwnership] guarantees only the owner peeks/marks a given session.
 *
 * All operations are **short transactions** (single statements). [pendingSessions] feeds the
 * M10 sweeper; an optional LISTEN/NOTIFY signal can accelerate it but stays a signal, never
 * the payload. Delivery is at the **turn boundary** (v1), not mid-token.
 */
interface SteeringInbox {
    /** Append a steering message for [sessionId] (callable from any node). */
    fun write(sessionId: String, payload: String)

    /**
     * Unconsumed messages for [sessionId], ordered by [SteeringMessage.seq], **not** marked.
     * The caller must own the session (see [SessionOwnership]).
     */
    fun peek(sessionId: String): List<SteeringMessage>

    /**
     * Mark every unconsumed message for [sessionId] with `seq <= `[throughSeq] consumed.
     * Call **after** the turn completes; newer rows that arrived during the turn are left for
     * the next sweep. The caller must own the session.
     */
    fun markConsumed(sessionId: String, throughSeq: Long)

    /**
     * Up to [limit] distinct session ids that have unconsumed messages — the sweeper's work
     * list. Callable from any node; ownership is arbitrated by [SessionOwnership.tryClaim].
     */
    fun pendingSessions(limit: Int): List<String>
}
