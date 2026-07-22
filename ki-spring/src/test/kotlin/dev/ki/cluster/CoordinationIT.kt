package dev.ki.cluster

import org.postgresql.ds.PGSimpleDataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The M10 coordination contract against a **real Postgres** (advisory locks and steering are
 * Postgres-only, so the offline SQLite tests can't cover them). Self-skips unless `KI_IT=1`
 * and Docker is reachable — same discipline as the other live-wire tests.
 *
 *   KI_IT=1 ./gradlew :ki-spring:test
 */
class CoordinationIT {

    private fun enabled() = System.getenv("KI_IT") == "1"

    /** A DataSource that yields a fresh connection per `getConnection` (each claim needs its own). */
    private fun dataSource(c: PostgreSQLContainer<*>): DataSource = PGSimpleDataSource().apply {
        setUrl(c.jdbcUrl); user = c.username; password = c.password
    }

    private inline fun withPostgres(block: (PostgreSQLContainer<*>) -> Unit) {
        PostgreSQLContainer("postgres:16-alpine").use { c ->
            c.start()
            block(c)
        }
    }

    @Test fun `advisory lock is mutually exclusive and releases on demand`() {
        if (!enabled()) return
        withPostgres { c ->
            val ds = dataSource(c)
            val nodeA = AdvisoryLockSessionOwnership(ds)
            val nodeB = AdvisoryLockSessionOwnership(ds)
            try {
                assertTrue(nodeA.tryClaim("S"), "A should win the unheld session")
                assertFalse(nodeB.tryClaim("S"), "B must not claim a session A holds")
                assertTrue(nodeA.isOwner("S"))
                assertFalse(nodeB.isOwner("S"))

                nodeA.release("S")
                assertTrue(nodeB.tryClaim("S"), "after A releases, B can claim")
                assertEquals(setOf("S"), nodeB.owned())
            } finally {
                nodeA.close(); nodeB.close()
            }
        }
    }

    @Test fun `a dropped owner connection auto-releases the lock (failover primitive)`() {
        if (!enabled()) return
        withPostgres { c ->
            val ds = dataSource(c)
            val key = AdvisoryKeys.of("S")

            // Simulate the owning node: a raw connection holding the lock, then "crash" (close).
            val ownerConn = ds.connection.apply { autoCommit = true }
            ownerConn.prepareStatement("SELECT pg_advisory_lock(?)").use { ps ->
                ps.setLong(1, key); ps.executeQuery().use { it.next() }
            }
            // Another node cannot claim while the owner holds it.
            val taker = AdvisoryLockSessionOwnership(ds)
            assertFalse(taker.tryClaim("S"), "lock held by the live owner")

            ownerConn.close() // process/connection death → Postgres releases the lock

            assertTrue(taker.tryClaim("S"), "takeover: the dropped owner's lock is auto-released")
            taker.close()
        }
    }

    @Test fun `steering inbox peeks in order and marks consumed only through the given seq`() {
        if (!enabled()) return
        withPostgres { c ->
            val jdbc = JdbcTemplate(dataSource(c))
            val inbox = JdbcSteeringInbox(jdbc)

            inbox.write("S", "one")
            inbox.write("S", "two")
            inbox.write("other", "nope")

            val peeked = inbox.peek("S")
            assertEquals(listOf("one", "two"), peeked.map { it.payload })
            assertTrue(peeked[0].seq < peeked[1].seq, "ordered by seq")
            // Peek does NOT consume — a re-peek still sees them (crash-safety).
            assertEquals(2, inbox.peek("S").size, "peek must not mark rows consumed")

            // A message that arrives during the turn (seq after what we peeked) survives markConsumed.
            inbox.write("S", "three")
            inbox.markConsumed("S", peeked.last().seq)
            assertEquals(listOf("three"), inbox.peek("S").map { it.payload }, "only rows <= throughSeq consumed")

            assertEquals(listOf("nope"), inbox.peek("other").map { it.payload }, "other session untouched")
            assertEquals(setOf("S", "other"), inbox.pendingSessions(10).toSet(), "both have pending work")
        }
    }
}
