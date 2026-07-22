package dev.ki.cli.config

import dev.ki.agent.config.Manifest
import dev.ki.agent.config.ManifestException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestTest {
    // The model moved to ki-agent; parsing lives in ManifestLoader. These shims keep the
    // tests focused on the parsed [Manifest] (the merged tree is exercised via extension-config).
    private fun load(path: Path): Manifest = ManifestLoader.load(path).manifest
    private fun load(paths: List<Path>): Manifest = ManifestLoader.load(paths).manifest

    private fun manifest(toml: String) = Files.createTempDirectory("ki-mf").resolve("ki.toml")
        .also { it.writeText(toml) }

    private val sample = """
        [llm]
        base_url = "http://proxy:4000"
        api_key_env = "MY_KEY"
        model = "fast"

        [db]
        path = "sess.db"

        [tools.bash]

        [tools.jira]
        script = "tools/jira.ki.kts"
        base_url = "https://acme.atlassian.net"
        token_env = "JIRA_TOKEN"

        [models.fast]
        id = "gpt-4o-mini"
        context_window = 64000
    """.trimIndent()

    @Test fun `parses llm db and models sections`() {
        val m = load(manifest(sample))
        assertEquals("http://proxy:4000", m.llm.baseUrl)
        assertEquals("MY_KEY", m.llm.apiKeyEnv)
        assertEquals("fast", m.llm.model)
        assertEquals("sess.db", m.db.path)
        assertEquals("gpt-4o-mini", m.models["fast"]?.id)
        assertEquals(64000, m.models["fast"]?.contextWindow)
    }

    @Test fun `builtin entry has no script and tool config lands in settings`() {
        val m = load(manifest(sample))
        assertNull(m.tools["bash"]?.script)
        val jira = m.tools["jira"]!!
        assertEquals("tools/jira.ki.kts", jira.script)
        assertEquals("https://acme.atlassian.net", jira.settings["base_url"])
        assertEquals("JIRA_TOKEN", jira.settings["token_env"])
    }

    @Test fun `parses extensions section as script entries with settings`() {
        val m = load(
            manifest(
                """
                [llm]
                base_url = "http://proxy:4000"
                api_key_env = "MY_KEY"
                model = "fast"

                [extensions.guards]
                script = "tools/guards.ki.kts"
                strict = true
                """.trimIndent(),
            ),
        )
        val guards = m.extensions["guards"]!!
        assertEquals("tools/guards.ki.kts", guards.script)
        assertEquals(true, guards.settings["strict"])
    }

    @Test fun `missing manifest is a clear error`() {
        val e = assertFailsWith<ManifestException> {
            load(Files.createTempDirectory("ki-none").resolve("ki.toml"))
        }
        assertTrue(e.message!!.contains("No ki.toml"), e.message)
    }

    // --- M17: multi-file deep-union merge -----------------------------------

    private fun file(dir: java.nio.file.Path, name: String, toml: String) =
        dir.resolve(name).also { it.writeText(toml.trimIndent()) }

    @Test fun `disjoint files merge into one manifest`() {
        val dir = Files.createTempDirectory("ki-merge")
        val a = file(dir, "ki.toml", """
            [llm]
            base_url = "http://proxy:4000"
            api_key_env = "MY_KEY"
            model = "fast"
        """)
        val b = file(dir, "ki.tools.toml", """
            [tools.bash]

            [models.fast]
            id = "gpt-4o-mini"
        """)
        val m = load(listOf(a, b))
        assertEquals("http://proxy:4000", m.llm.baseUrl)
        assertTrue(m.tools.containsKey("bash"))
        assertEquals("gpt-4o-mini", m.models["fast"]?.id)
    }

    @Test fun `a shared section is assembled from disjoint keys across files`() {
        val dir = Files.createTempDirectory("ki-merge")
        val a = file(dir, "ki.toml", """
            [llm]
            base_url = "http://proxy:4000"
            api_key_env = "MY_KEY"
            model = "fast"

            [tools.bash]
        """)
        val b = file(dir, "ki.more.toml", """
            [tools.jira]
            script = "tools/jira.ki.kts"
        """)
        val m = load(listOf(a, b))
        assertTrue(m.tools.containsKey("bash"))
        assertEquals("tools/jira.ki.kts", m.tools["jira"]?.script)
    }

    @Test fun `same scalar key in two files errors and names the path and both files`() {
        val dir = Files.createTempDirectory("ki-merge")
        val a = file(dir, "ki.toml", """
            [llm]
            base_url = "http://proxy:4000"
            api_key_env = "MY_KEY"
            model = "fast"
        """)
        val b = file(dir, "ki.dup.toml", """
            [llm]
            model = "slow"
        """)
        val e = assertFailsWith<ManifestException> { load(listOf(a, b)) }
        assertTrue(e.message!!.contains("llm.model"), e.message)
        assertTrue(e.message!!.contains("ki.toml"), e.message)
        assertTrue(e.message!!.contains("ki.dup.toml"), e.message)
    }

    @Test fun `array key in two files is a conflict, not a concatenation`() {
        val dir = Files.createTempDirectory("ki-merge")
        val a = file(dir, "ki.toml", """
            [llm]
            base_url = "http://proxy:4000"
            api_key_env = "MY_KEY"
            model = "fast"

            [context]
            files = ["a.md"]
        """)
        val b = file(dir, "ki.ctx.toml", """
            [context]
            files = ["b.md"]
        """)
        val e = assertFailsWith<ManifestException> { load(listOf(a, b)) }
        assertTrue(e.message!!.contains("context.files"), e.message)
    }
}
