package dev.ki.cluster

import dev.ki.store.SessionOwnership
import dev.ki.store.SessionTurnRunner
import dev.ki.store.SteeringInbox
import dev.ki.store.TurnReplySink
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * The M10 per-node **orchestration loop** — the backbone that turns the coordination primitives
 * into a running distributed agent. One sweep:
 *
 *   pendingSessions → tryClaim (atomic arbiter) → peek → runTurn → reply → markConsumed → release
 *
 * This single loop is simultaneously **new-work distribution**, **dead-owner failover** (a
 * crashed owner's advisory lock auto-releases, so the next sweep on any node claims and re-runs
 * from the M9 checkpoint), and **lost-wakeup recovery** (a message landing between drain and
 * release is caught next sweep). `SKIP LOCKED`-style fairness comes from the per-node
 * concurrency cap: a saturated node stops claiming, so work flows to nodes with free slots.
 *
 * Depends only on the ki-agent seams — no Postgres of its own — so it is unit-testable with
 * in-memory fakes and validated end-to-end against Postgres by the coordination IT.
 *
 * @param ownership per-turn advisory-lock ownership (the arbiter).
 * @param inbox the per-session message queue.
 * @param runner host-supplied "run one turn" (builds a checkpointing KiAgent).
 * @param replySink where a turn's reply goes (e.g. back to a RocketChat thread).
 * @param concurrency max sessions this node runs at once (the capacity knob / fair-queue lever).
 * @param pollInterval sweep cadence when running as a background loop.
 * @param batchLimit how many pending sessions to consider per sweep.
 */
class SessionWorker(
    private val ownership: SessionOwnership,
    private val inbox: SteeringInbox,
    private val runner: SessionTurnRunner,
    private val replySink: TurnReplySink,
    private val concurrency: Int = 4,
    private val pollIntervalMs: Long = 1000,
    private val batchLimit: Int = concurrency * 4,
) {
    private val slots = Semaphore(concurrency)
    private val runningHere = ConcurrentHashMap.newKeySet<String>()
    private var loop: Job? = null

    /** Start the background sweep loop in [scope]; idempotent. */
    fun start(scope: CoroutineScope) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                runCatching { sweep(scope) }.onFailure { logger.warn(it) { "sweep failed" } }
                delay(pollIntervalMs)
            }
        }
    }

    /** Stop the background loop (does not interrupt in-flight turns). */
    fun stop() { loop?.cancel(); loop = null }

    /**
     * One sweep pass: consider pending sessions not already running here, and launch processing
     * for each until the concurrency cap is reached. Returns the number of sessions launched.
     */
    fun sweep(scope: CoroutineScope): Int {
        var launched = 0
        for (sessionId in inbox.pendingSessions(batchLimit)) {
            if (sessionId in runningHere) continue
            if (!slots.tryAcquire()) break // node at capacity — leave the rest for other nodes
            if (!runningHere.add(sessionId)) { slots.release(); continue }
            launched++
            scope.launch {
                try {
                    processSession(sessionId)
                } catch (e: Throwable) {
                    logger.warn(e) { "turn failed for session $sessionId; left unconsumed for retry" }
                } finally {
                    runningHere.remove(sessionId)
                    slots.release()
                }
            }
        }
        return launched
    }

    /**
     * Claim [sessionId], run one turn over its pending messages, reply, then mark consumed and
     * release. Returns false without side effects if another node owns it. **markConsumed runs
     * only after a successful turn** — a crash (or throw) leaves the messages unconsumed and the
     * lock auto-releases, so another node re-runs from the M9 checkpoint (at-least-once).
     */
    suspend fun processSession(sessionId: String): Boolean {
        if (!ownership.tryClaim(sessionId)) return false
        try {
            val pending = inbox.peek(sessionId)
            if (pending.isEmpty()) return true // already consumed (e.g. a lost-wakeup false positive)
            val input = pending.joinToString("\n") { it.payload }
            val reply = runner.runTurn(sessionId, input)
            replySink.onReply(sessionId, reply)
            inbox.markConsumed(sessionId, pending.last().seq) // AFTER the turn — never before
            return true
        } finally {
            ownership.release(sessionId)
        }
    }
}
