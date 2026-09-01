package dev.ki.cli.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmClasspathTest {
    @Test
    fun `jvm classpath parses from the manifest`() {
        val dir = Files.createTempDirectory("ki-jvm")
        val cfg = dir.resolve("ki.toml")
        cfg.writeText(
            """
            [llm]
            base_url = "http://localhost:4000"
            api_key_env = "LITELLM_API_KEY"
            model = "gpt-4o"
            [jvm]
            classpath = ["libs/a.jar", "dir/*"]
            """.trimIndent(),
        )
        val m = ManifestLoader.load(listOf(cfg)).manifest
        assertEquals(listOf("libs/a.jar", "dir/*"), m.jvm.classpath)
    }
}
