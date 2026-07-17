package dev.ki.cli.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BootstrapTest {
    private fun writeManifest(toml: String): Path {
        val dir = Files.createTempDirectory("ki-boot")
        return dir.resolve("ki.toml").also { it.writeText(toml) }
    }

    @Test fun `builds only the tools listed in the manifest`() {
        val cfg = writeManifest(
            """
            [db]
            path = "ki.db"
            [tools.bash]
            [tools.read]
            """.trimIndent()
        )
        val session = Bootstrap.build(CliArgs(configPath = cfg), "SYS")
        session.store.use {
            assertEquals(2, session.tools.size)
        }
    }

    @Test fun `an unlisted builtin is simply absent`() {
        val cfg = writeManifest("[tools.bash]\n")
        val session = Bootstrap.build(CliArgs(configPath = cfg), "SYS")
        session.store.use { assertEquals(1, session.tools.size) }
    }

    @Test fun `unknown non-builtin tool without a script errors`() {
        val cfg = writeManifest("[tools.frobnicate]\n")
        val e = assertFailsWith<ManifestException> { Bootstrap.build(CliArgs(configPath = cfg), "SYS") }
        assertTrue(e.message!!.contains("frobnicate"), e.message)
    }

    @Test fun `context files are appended to the system prompt`() {
        val dir = Files.createTempDirectory("ki-ctx")
        dir.resolve("KI.md").writeText("Project rule: be terse.")
        val cfg = dir.resolve("ki.toml")
        cfg.writeText("[context]\nfiles = [\"KI.md\"]\n[tools.bash]\n")
        val session = Bootstrap.build(CliArgs(configPath = cfg), "SYS")
        session.store.use {
            assertTrue(session.systemPrompt.startsWith("SYS"))
            assertTrue(session.systemPrompt.contains("Project rule: be terse."))
        }
    }

    @Test fun `--continue resumes the most recent session`() {
        val cfg = writeManifest("[db]\npath = \"ki.db\"\n[tools.bash]\n")
        // Seed two sessions directly in the store the bootstrap will open.
        val first = Bootstrap.build(CliArgs(configPath = cfg), "SYS")
        first.store.use { s ->
            s.save("old", listOf(dev.ki.store.StoredMessage(0, "User", "{}")))
            Thread.sleep(5)
            s.save("recent", listOf(dev.ki.store.StoredMessage(0, "User", "{}")))
        }
        val resumed = Bootstrap.build(CliArgs(configPath = cfg, continueLatest = true), "SYS")
        resumed.store.use { assertEquals("recent", resumed.sessionId) }
    }
}
