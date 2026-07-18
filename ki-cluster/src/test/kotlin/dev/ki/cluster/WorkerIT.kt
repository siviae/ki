package dev.ki.cluster

import dev.ki.store.SessionTurnRunner
import dev.ki.store.TurnReplySink
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The M10 orchestration loop over **real** Postgres coordination — `SessionWorker` on top of
 * `AdvisoryLockSessionOwnership` + `JdbcSteeringInbox`, proving the loop integrates with the
 * primitives (not just the in-memory fakes of SessionWorkerTest). Turns are run by a fake
 * `SessionTurnRunner` (no LLM); real KiAgent turns are covered by CheckpointRecoveryTest.
 * Self-skips unless `KI_IT=1` + Docker.
 */
class WorkerIT {

    private fun enabled() = System.getenv("KI_IT") == "1"

    private fun dataSource(c: PostgreSQLContainer<*>): DataSource = PGSimpleDataSource().apply {
        setUrl(c.jdbcUrl); user = c.username; password = c.password
    }

    private inline fun withPostgres(block: (PostgreSQLContainer<*>) -> Unit) {
        PostgreSQLContainer("postgres:16-alpine").use { c -> c.start(); block(c) }
    }

    private fun echoRunner(calls: ConcurrentLinkedQueue<String>) =
        SessionTurnRunner { sid, input -> calls.add("$sid:$input"); "answer:$input" }

    private val noReply = TurnReplySink { _, _ -> }

    @Test fun `worker runs a pending session end-to-end over real coordination`() {
        if (!enabled()) return
        withPostgres { c ->
            val ds = dataSource(c)
            val inbox = JdbcSteeringInbox(JdbcTemplate(ds))
            val own = AdvisoryLockSessionOwnership(ds)
            val calls = ConcurrentLinkedQueue<String>()
            val replies = ConcurrentLinkedQueue<String>()
            val worker = SessionWorker(own, inbox, echoRunner(calls), { s, r -> replies.add("$s=$r") })

            inbox.write("S", "hi")
            val processed = runBlocking { worker.processSession("S") }

            assertTrue(processed)
            assertEquals(listOf("S:hi"), calls.toList())
            assertEquals(listOf("S=answer:hi"), replies.toList())
            assertTrue(inbox.peek("S").isEmpty(), "message consumed after the turn")
            assertFalse(own.isOwner("S"), "advisory lock released after the turn")
            own.close()
        }
    }

    @Test fun `two nodes race a session and exactly one runs it`() {
        if (!enabled()) return
        withPostgres { c ->
            val ds = dataSource(c)
            val inbox = JdbcSteeringInbox(JdbcTemplate(ds))
            val ownA = AdvisoryLockSessionOwnership(ds)
            val ownB = AdvisoryLockSessionOwnership(ds)
            val calls = ConcurrentLinkedQueue<String>()
            val wA = SessionWorker(ownA, inbox, echoRunner(calls), noReply)
            val wB = SessionWorker(ownB, inbox, echoRunner(calls), noReply)

            inbox.write("S", "once")
            // Race both nodes concurrently: the advisory lock must let only one hold the session,
            // so the turn runs exactly once (the other's tryClaim fails, or it finds nothing left).
            runBlocking {
                val a = async { wA.processSession("S") }
                val b = async { wB.processSession("S") }
                a.await(); b.await()
            }

            assertEquals(1, calls.size, "the turn must run exactly once across both nodes")
            assertTrue(inbox.peek("S").isEmpty(), "message consumed exactly once")
            ownA.close(); ownB.close()
        }
    }

    @Test fun `a message left by a crashed owner is picked up by another node (failover)`() {
        if (!enabled()) return
        withPostgres { c ->
            val ds = dataSource(c)
            val inbox = JdbcSteeringInbox(JdbcTemplate(ds))
            val calls = ConcurrentLinkedQueue<String>()

            inbox.write("S", "work")

            // Node A claims the session and "crashes" (connection dropped) before consuming.
            val crashedOwner = ds.connection.apply { autoCommit = true }
            crashedOwner.prepareStatement("SELECT pg_advisory_lock(?)").use { ps ->
                ps.setLong(1, AdvisoryKeys.of("S")); ps.executeQuery().use { it.next() }
            }
            assertFalse(inbox.peek("S").isEmpty(), "message still pending — A never consumed it")
            crashedOwner.close() // crash → lock auto-released

            // Node B's worker takes over and completes it.
            val ownB = AdvisoryLockSessionOwnership(ds)
            val processed = runBlocking { SessionWorker(ownB, inbox, echoRunner(calls), noReply).processSession("S") }

            assertTrue(processed, "B takes over the released session")
            assertEquals(listOf("S:work"), calls.toList())
            assertTrue(inbox.peek("S").isEmpty(), "consumed by B after takeover")
            ownB.close()
        }
    }
}
