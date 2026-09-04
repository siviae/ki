package dev.ki.cli.config

import dev.ki.agent.config.ManifestException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1.1 hat-wins loading: root `ki.toml` + `.pi/hats/<name>/ki.toml` resolve with pi's
 * semantics — hat wins on concrete keys, `[context].files` appended, the hat's tool list
 * is the active allowlist, and the hat's `[pi]` subtree stays available for skills.
 */
class ManifestLoaderHatTest {
    private fun dir() = Files.createTempDirectory("ki-hat")
    private fun file(path: Path, toml: String) = path.also { it.writeText(toml.trimIndent()) }

    private val rootToml = """
        [llm]
        base_url = "http://proxy:4000"
        api_key_env = "MY_KEY"
        model = "fast"

        [db]
        path = "sessions"

        [context]
        files = ["ROOT.md"]

        [tools.bash]

        [tools.read]

        [tools.grep]
        script = "tools/grep.ki.kts"

        [extensions.memory]
        script = "tools/memory.ki.kts"
    """

    private fun hatToml(tools: String = "") = """
        [llm]
        model = "deep"

        [agent]
        systemPrompt = "You are a hat."

        [context]
        files = ["HAT.md"]

        [pi]
        hatDescription = "test hat"
        skills = ["shifts-schema", "problem-investigation"]

        [extensions.interceptors]
        script = "tools/interceptors.ki.kts"
        $tools
    """

    private fun load(rootToml: String = this.rootToml, hatToml: String = this.hatToml()): LoadedManifest {
        val dir = dir()
        val root = file(dir.resolve("ki.toml"), rootToml)
        val hatDir = dir.resolve(".pi/hats/bot").also { Files.createDirectories(it) }
        val hat = file(hatDir.resolve("ki.toml"), hatToml)
        return ManifestLoader.loadHat(root, hat)
    }

    // --- hat wins on concrete keys -------------------------------------------

    @Test fun `hat llm model overrides root, other llm keys stay`() {
        val m = load().manifest
        assertEquals("deep", m.llm.model)
        assertEquals("http://proxy:4000", m.llm.baseUrl)
        assertEquals("MY_KEY", m.llm.apiKeyEnv)
    }

    @Test fun `hat systemPrompt lands in agent section`() {
        assertEquals("You are a hat.", load().manifest.agent.systemPrompt)
    }

    @Test fun `plain manifest has no agent section`() {
        val dir = dir()
        val root = file(dir.resolve("ki.toml"), """
            [llm]
            base_url = "http://proxy:4000"
            api_key_env = "MY_KEY"
            model = "fast"
        """)
        assertNull(ManifestLoader.load(root).manifest.agent.systemPrompt)
    }

    // --- context.files append --------------------------------------------------

    @Test fun `context files are appended root first then hat`() {
        assertEquals(listOf("ROOT.md", "HAT.md"), load().manifest.context.files)
    }

    @Test fun `context files work when only one side defines them`() {
        val hatNoContext = """
            [llm]
            model = "deep"

            [agent]
            systemPrompt = "You are a hat."

            [pi]
            hatDescription = "test hat"
            skills = ["shifts-schema", "problem-investigation"]

            [extensions.interceptors]
            script = "tools/interceptors.ki.kts"
        """
        val rootNoContext = """
            [llm]
            base_url = "http://proxy:4000"
            api_key_env = "MY_KEY"
            model = "fast"

            [db]
            path = "sessions"

            [tools.bash]

            [tools.read]

            [tools.grep]
            script = "tools/grep.ki.kts"

            [extensions.memory]
            script = "tools/memory.ki.kts"
        """
        assertEquals(listOf("ROOT.md"), load(rootToml = rootToml, hatToml = hatNoContext).manifest.context.files)
        assertEquals(listOf("HAT.md"), load(rootToml = rootNoContext).manifest.context.files)
    }

    // --- tools: merged map + hat allowlist --------------------------------------

    @Test fun `tool map unions root and hat entries`() {
        val loaded = load(hatToml = hatToml(tools = """
            [tools.shifts_db_query]
            script = "tools/shifts-db.ki.kts"

            [tools.read]

            [tools.edit]
        """))
        val names = loaded.manifest.tools.keys
        assertTrue("bash" in names && "grep" in names, "root tools kept: $names")
        assertTrue("shifts_db_query" in names && "edit" in names, "hat tools added: $names")
    }

    @Test fun `hat tool list is the active allowlist and same-name hat entry wins`() {
        val loaded = load(hatToml = hatToml(tools = """
            [tools.grep]
            script = "hat/grep.ki.kts"

            [tools.edit]
        """))
        assertEquals(listOf("grep", "edit"), loaded.hatToolNames)
        // same tool in both files: the hat's entry replaces the root's wholesale
        assertEquals("hat/grep.ki.kts", loaded.manifest.tools["grep"]?.script)
    }

    @Test fun `hat extensions replace nothing at merge level and are exposed`() {
        val loaded = load()
        assertEquals(setOf("interceptors"), loaded.hatExtensionNames)
        // merged map still holds root extensions (the caller filters by hatExtensionNames)
        assertTrue("memory" in loaded.manifest.extensions.keys)
        assertTrue("interceptors" in loaded.manifest.extensions.keys)
    }

    // --- pi section exposure ------------------------------------------------------

    @Test fun `pi section carries skills and description for the skills system`() {
        val loaded = load()
        assertEquals(listOf("shifts-schema", "problem-investigation"), loaded.piSection?.get("skills")?.map { it.asText() })
        assertEquals("test hat", loaded.piSection?.get("hatDescription")?.asText())
    }

    @Test fun `hat without pi section exposes null piSection`() {
        val loaded = load(hatToml = """
            [llm]
            model = "deep"
        """)
        assertNull(loaded.piSection)
        assertEquals("deep", loaded.manifest.llm.model)
    }

    // --- errors -------------------------------------------------------------------

    @Test fun `missing hat file names the expected path`() {
        val dir = dir()
        val root = file(dir.resolve("ki.toml"), rootToml)
        val e = assertFailsWith<ManifestException> {
            ManifestLoader.loadHat(root, dir.resolve(".pi/hats/nope/ki.toml"))
        }
        assertTrue(e.message!!.contains(".pi/hats/nope/ki.toml"), e.message)
    }

    @Test fun `missing root file is still an error`() {
        val e = assertFailsWith<ManifestException> {
            ManifestLoader.loadHat(dir().resolve("ki.toml"), dir().resolve("hat.toml"))
        }
        assertTrue(e.message!!.contains("No ki.toml manifest"), e.message)
    }

    // --- plain load is unchanged (deep-union hard error) ---------------------------

    @Test fun `plain multi-file load still errors on duplicate scalar keys`() {
        val dir = dir()
        val a = file(dir.resolve("ki.toml"), rootToml)
        val b = file(dir.resolve("ki.hat.toml"), "[llm]\nmodel = \"deep\"")
        val e = assertFailsWith<ManifestException> { ManifestLoader.load(listOf(a, b)) }
        assertTrue(e.message!!.contains("llm.model"), e.message)
    }
}
