package dev.ki.store

/**
 * Distributed single-owner coordination for a session — the M10 seam ki-agent exposes and
 * the `ki-spring` Postgres module implements (advisory locks). Local/CLI deployments never
 * touch this; it exists so a **multi-node** deployment runs each session on exactly one node
 * and, when that node dies, lets another take over from the last M9 checkpoint.
 *
 * Contract (Postgres impl uses **session-level advisory locks on a dedicated connection**):
 * - [tryClaim] is a non-blocking race — at most one node wins a given [sessionId] at a time.
 * - Ownership is held until [release] **or the owning node's process/connection drops** — a
 *   crash auto-releases the lock, so another node's [tryClaim] then succeeds (failover).
 * - No long-running transaction backs a claim; the lock guards ownership, and the actual
 *   message/checkpoint writes happen in separate short transactions while it is held.
 *
 * A held claim **pins a connection** (advisory locks are connection-scoped), so the number of
 * sessions a node owns concurrently is bounded by its dedicated ownership connections — a real
 * capacity knob, not a defect.
 */
interface SessionOwnership {
    /** Attempt to become the owner of [sessionId]; true if this node now holds it (or already did). */
    fun tryClaim(sessionId: String): Boolean

    /** Release ownership so another node may claim it. No-op if not owned here. */
    fun release(sessionId: String)

    /** True if this node currently owns [sessionId]. */
    fun isOwner(sessionId: String): Boolean

    /** Session ids this node currently owns (for capacity checks / draining steering). */
    fun owned(): Set<String>
}
