package dev.ki.store.spring

import dev.ki.store.StoredMessage
import org.springframework.jdbc.core.JdbcTemplate
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the JdbcTemplate impl offline against an embedded SQLite `DataSource`.
 * The portable DDL/SQL is shared with production Postgres (verified there behind
 * `KI_IT`); this proves the JdbcTemplate wiring and the replace/list semantics.
 */
class JdbcTemplateSessionStoreTest {
    private fun store(): JdbcTemplateSessionStore {
        val db = Files.createTempDirectory("ki-spring").resolve("ki.db")
        val ds = SQLiteDataSource().apply { url = "jdbc:sqlite:$db" }
        return JdbcTemplateSessionStore(JdbcTemplate(ds))
    }

    private fun msgs(vararg t: String) = t.mapIndexed { i, s -> StoredMessage(i, "User", """{"t":"$s"}""") }

    @Test fun `round-trips and replaces`() {
        val s = store()
        s.save("S", msgs("a", "b"))
        assertEquals(2, s.load("S").size)
        s.save("S", msgs("x"))
        assertEquals(1, s.load("S").size)
        assertEquals("""{"t":"x"}""", s.load("S").single().json)
    }

    @Test fun `listSessions is newest first with counts`() {
        val s = store()
        s.save("a", msgs("1"))
        Thread.sleep(5)
        s.save("b", msgs("1", "2"))
        val sessions = s.listSessions()
        assertEquals(listOf("b", "a"), sessions.map { it.conversationId })
        assertEquals(2, sessions.first().messageCount)
    }

    @Test fun `unknown conversation is empty`() {
        assertTrue(store().load("nope").isEmpty())
    }
}
