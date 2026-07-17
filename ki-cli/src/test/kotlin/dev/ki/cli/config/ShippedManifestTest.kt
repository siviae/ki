package dev.ki.cli.config

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boots the repo's actual `ki.toml` through the real assembly path — parses the
 * manifest, selects the builtins, **compiles the grep script tool**, and opens the
 * SQLite store. Guards that the shipped default config is valid end to end (short of
 * the live LLM call). Self-skips if the repo manifest isn't at the expected location.
 */
class ShippedManifestTest {
    // Gradle runs ki-cli tests with the module dir as cwd; the repo manifest is one up.
    private val repoManifest: Path = Path.of("..", "ki.toml").toAbsolutePath().normalize()

    @Test fun `repo ki_toml assembles all tools and a working store`() {
        if (!repoManifest.exists()) return // not running from a checkout; skip

        val session = Bootstrap.build(CliArgs(configPath = repoManifest), "SYS")
        session.store.use {
            // 5 builtins + grep script tool.
            assertEquals(6, session.tools.size)
            assertTrue(session.sessionId.isNotBlank())
        }
    }
}
