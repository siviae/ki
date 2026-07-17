package dev.ki.cli.store

import dev.ki.store.StoredMessage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqliteSessionStoreTest {
    private fun store() = SqliteSessionStore(Files.createTempDirectory("ki-db").resolve("ki.db"))

    private fun msgs(vararg texts: String) =
        texts.mapIndexed { i, t -> StoredMessage(i, "User", """{"t":"$t"}""") }

    @Test fun `round-trips messages in seq order`() {
        val s = store()
        s.save("S", msgs("a", "b", "c"))
        assertEquals(listOf(0, 1, 2), s.load("S").map { it.seq })
        assertEquals("""{"t":"b"}""", s.load("S")[1].json)
    }

    @Test fun `save replaces the whole conversation`() {
        val s = store()
        s.save("S", msgs("a", "b", "c"))
        s.save("S", msgs("x"))
        assertEquals(1, s.load("S").size)
        assertEquals("""{"t":"x"}""", s.load("S").single().json)
    }

    @Test fun `unknown conversation loads empty`() {
        assertTrue(store().load("nope").isEmpty())
    }

    @Test fun `listSessions returns newest first with counts`() {
        val s = store()
        s.save("first", msgs("a"))
        Thread.sleep(5)
        s.save("second", msgs("a", "b"))
        val sessions = s.listSessions()
        assertEquals(listOf("second", "first"), sessions.map { it.conversationId })
        assertEquals(2, sessions.first().messageCount)
    }

    @Test fun `data survives reopening the file`() {
        val dir = Files.createTempDirectory("ki-db-reopen").resolve("ki.db")
        SqliteSessionStore(dir).use { it.save("S", msgs("a", "b")) }
        SqliteSessionStore(dir).use { assertEquals(2, it.load("S").size) }
    }
}
