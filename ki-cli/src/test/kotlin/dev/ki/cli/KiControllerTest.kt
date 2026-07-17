package dev.ki.cli

import dev.ki.cli.config.Bootstrap
import dev.ki.cli.config.CliArgs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KiControllerTest {
    private fun manifest(): Path {
        val dir = Files.createTempDirectory("ki-ctrl")
        return dir.resolve("ki.toml").also {
            it.writeText(
                """
                [llm]
                model = "a"
                [db]
                path = "ki.db"
                [tools.bash]
                [tools.read]
                [models.a]
                id = "gpt-4o"
                [models.b]
                id = "gpt-4o-mini"
                context_window = 8000
                """.trimIndent()
            )
        }
    }

    @Test fun `switch model rebuilds against the new model, tools unchanged`() {
        val session = Bootstrap.build(CliArgs(configPath = manifest()), "SYS")
        session.store.use {
            val c = KiController(session)
            assertEquals("gpt-4o", c.model())
            val toolsBefore = c.tools()

            val msg = c.switchModel("b")

            assertTrue(msg.contains("gpt-4o-mini"))
            assertEquals("gpt-4o-mini", c.model())
            assertEquals(toolsBefore, c.tools(), "tools should carry across a model switch")
        }
    }

    @Test fun `config summary shows model and never leaks the api key`() {
        val session = Bootstrap.build(CliArgs(configPath = manifest()), "SYS")
        session.store.use {
            val summary = KiController(session).configSummary()
            assertTrue(summary.contains("gpt-4o"))
            assertTrue(!summary.contains(session.config.apiKey), "api key must not appear in /config")
        }
    }
}
