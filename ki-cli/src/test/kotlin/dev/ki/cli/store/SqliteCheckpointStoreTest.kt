package dev.ki.cli.store

import dev.ki.store.StoredCheckpoint
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteCheckpointStoreTest {

    /** A session store + a checkpoint store over its shared connection (as Bootstrap wires them). */
    private fun stores(): Pair<SqliteSessionStore, SqliteCheckpointStore> {
        val session = SqliteSessionStore(Files.createTempDirectory("ki-ckpt").resolve("ki.db"))
        return session to SqliteCheckpointStore(session.connection)
    }

    private fun ckpt(id: String, version: Long) =
        StoredCheckpoint(id, version, createdAt = version, json = """{"id":"$id","v":$version}""")

    @Test fun `saves and loads checkpoints in version order`() {
        val (_, c) = stores()
        c.save("S", ckpt("b", 1))
        c.save("S", ckpt("a", 0))
        c.save("S", ckpt("c", 2))
        assertEquals(listOf(0L, 1L, 2L), c.load("S").map { it.version })
    }

    @Test fun `latest returns the highest version, tombstones included`() {
        val (_, c) = stores()
        c.save("S", ckpt("a", 0))
        c.save("S", ckpt("tomb", 1)) // no filtering — koog versions off this and no-ops the restore
        assertEquals("tomb", c.latest("S")?.checkpointId)
        assertEquals(1L, c.latest("S")?.version)
    }

    @Test fun `latest is null and load empty for an unknown session`() {
        val (_, c) = stores()
        assertNull(c.latest("nope"))
        assertTrue(c.load("nope").isEmpty())
    }

    @Test fun `save upserts on the same checkpoint id`() {
        val (_, c) = stores()
        c.save("S", ckpt("a", 0))
        c.save("S", StoredCheckpoint("a", 5, 5, """{"updated":true}"""))
        assertEquals(1, c.load("S").size)
        assertEquals(5L, c.latest("S")?.version)
        assertTrue(c.latest("S")!!.json.contains("updated"))
    }

    @Test fun `delete drops a session's checkpoints only`() {
        val (_, c) = stores()
        c.save("S", ckpt("a", 0))
        c.save("T", ckpt("b", 0))
        c.delete("S")
        assertTrue(c.load("S").isEmpty())
        assertEquals(1, c.load("T").size)
    }

    @Test fun `checkpoint and session writes coexist on the shared connection`() {
        val (session, c) = stores()
        // Interleave writes the way a live run does (session at turn end, checkpoints per node)
        // — the shared connection + busy_timeout must not throw SQLITE_BUSY.
        repeat(20) { i ->
            c.save("S", ckpt("k$i", i.toLong()))
            session.save("S", listOf(dev.ki.store.StoredMessage(i, "User", """{"n":$i}""")))
        }
        assertEquals(20, c.load("S").size)
        assertEquals(19L, c.latest("S")?.version)
    }
}
