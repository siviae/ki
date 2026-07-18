package dev.ki.cluster

import dev.ki.store.SessionOwnership
import dev.ki.store.SessionTurnRunner
import dev.ki.store.SteeringInbox
import dev.ki.store.SteeringMessage
import dev.ki.store.TurnReplySink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SessionWorker loop mechanics offline, with in-memory fakes. Ownership is modelled by a shared
 * [Arbiter] two [FakeOwnership] instances consult — the in-memory analogue of two nodes over one
 * Postgres advisory-lock namespace — so cross-node exclusion is exercised without Docker. The
 * real advisory-lock + steering integration is covered by CoordinationIT against Postgres.
 */
class SessionWorkerTest {

    /** Single-owner arbiter shared by "nodes" — mimics one Postgres advisory-lock space. */
    private class Arbiter {
        private val owners = HashMap<String, String>()
        @Synchronized fun claim(s: String, node: String): Boolean {
            val cur = owners[s]
            if (cur == null) { owners[s] = node; return true }
            return cur == node
        }
        @Synchronized fun release(s: String, node: String) { if (owners[s] == node) owners.remove(s) }
        @Synchronized fun owner(s: String): String? = owners[s]
    }

    private class FakeOwnership(private val arbiter: Arbiter, private val node: String) : SessionOwnership {
        override fun tryClaim(sessionId: String) = arbiter.claim(sessionId, node)
        override fun release(sessionId: String) = arbiter.release(sessionId, node)
        override fun isOwner(sessionId: String) = arbiter.owner(sessionId) == node
        override fun owned(): Set<String> = emptySet()
    }

    private class FakeInbox : SteeringInbox {
        private data class Row(val seq: Long, val session: String, val payload: String, var consumed: Boolean)
        private val rows = ArrayList<Row>()
        private var next = 1L
        @Synchronized override fun write(sessionId: String, payload: String) {
            rows.add(Row(next++, sessionId, payload, false))
        }
        @Synchronized override fun peek(sessionId: String) =
            rows.filter { it.session == sessionId && !it.consumed }.map { SteeringMessage(it.seq, it.payload) }
        @Synchronized override fun markConsumed(sessionId: String, throughSeq: Long) {
            rows.forEach { if (it.session == sessionId && !it.consumed && it.seq <= throughSeq) it.consumed = true }
        }
        @Synchronized override fun pendingSessions(limit: Int) =
            rows.filter { !it.consumed }.map { it.session }.distinct().take(limit)
    }

    private fun echoRunner() = SessionTurnRunner { _, input -> "answer:$input" }
    private fun sink(into: MutableList<Pair<String, String>>) = TurnReplySink { s, r -> into.add(s to r) }

    @Test fun `processSession claims, runs, replies, consumes, releases`() {
        val arbiter = Arbiter()
        val own = FakeOwnership(arbiter, "A")
        val inbox = FakeInbox().apply { write("S", "hello") }
        val replies = mutableListOf<Pair<String, String>>()
        val worker = SessionWorker(own, inbox, echoRunner(), sink(replies))

        val processed = runBlocking { worker.processSession("S") }

        assertTrue(processed)
        assertEquals(listOf("S" to "answer:hello"), replies)
        assertTrue(inbox.peek("S").isEmpty(), "consumed after a successful turn")
        assertFalse(own.isOwner("S"), "lock released after the turn")
    }

    @Test fun `a failed turn leaves messages unconsumed and releases the lock`() {
        val arbiter = Arbiter()
        val own = FakeOwnership(arbiter, "A")
        val inbox = FakeInbox().apply { write("S", "x") }
        val boom = SessionTurnRunner { _, _ -> throw RuntimeException("boom") }
        val worker = SessionWorker(own, inbox, boom, sink(mutableListOf()))

        assertFailsWith<RuntimeException> { runBlocking { worker.processSession("S") } }

        assertEquals(1, inbox.peek("S").size, "a crashed turn must NOT consume its message (at-least-once)")
        assertFalse(own.isOwner("S"), "lock released even on failure so another node can retry")
    }

    @Test fun `only one node processes a session another node already owns`() {
        val arbiter = Arbiter()
        val ownA = FakeOwnership(arbiter, "A")
        val ownB = FakeOwnership(arbiter, "B")
        val inbox = FakeInbox().apply { write("S", "x") }
        assertTrue(ownB.tryClaim("S"), "B takes ownership first")

        val processedByA = runBlocking { SessionWorker(ownA, inbox, echoRunner(), sink(mutableListOf())).processSession("S") }

        assertFalse(processedByA, "A must not process a session B owns")
        assertEquals(1, inbox.peek("S").size, "A left the message untouched")
    }

    @Test fun `sweep launches at most the concurrency cap`() {
        runBlocking {
            val inbox = FakeInbox()
            repeat(5) { inbox.write("S$it", "x") }
            val gate = CompletableDeferred<Unit>()
            val blocking = SessionTurnRunner { _, _ -> gate.await(); "done" }
            val worker = SessionWorker(FakeOwnership(Arbiter(), "A"), inbox, blocking, sink(mutableListOf()), concurrency = 2)

            val launched = worker.sweep(this)
            assertEquals(2, launched, "a node at cap stops claiming — the rest flow to other nodes")

            gate.complete(Unit) // let the two held turns finish so runBlocking can join them
        }
    }
}
