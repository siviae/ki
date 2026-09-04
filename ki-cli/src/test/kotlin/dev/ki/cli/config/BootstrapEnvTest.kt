package dev.ki.cli.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1.1 env precedence (`KI_MODEL` / `LITELLM_BASE_URL` / `LITELLM_API_KEY`) and the
 * `--print-resolved` view. Env reads go through an injectable lookup — tests never touch
 * the process environment.
 */
class BootstrapEnvTest {
    private fun writeManifest(toml: String): Path {
        val dir = Files.createTempDirectory("ki-env")
        return dir.resolve("ki.toml").also { it.writeText(toml.trimIndent()) }
    }

    private val manifestToml = """
        [llm]
        base_url = "http://manifest:4000"
        api_key_env = "MY_KEY"
        model = "manifest-model"
        [tools.bash]
    """

    private fun parts(args: CliArgs = CliArgs(configPath = writeManifest(manifestToml)), env: Map<String, String>) =
        Bootstrap.resolveConfigParts(args, ManifestLoader.load(args.configPath).manifest) { env[it] }

    @Test fun `manifest wins when no env is set`() {
        val (model, key, url) = parts(env = mapOf("MY_KEY" to "manifest-key-value"))
        assertEquals("manifest-model", model)
        assertEquals("manifest-key-value", key)
        assertEquals("http://manifest:4000", url)
    }

    @Test fun `ki_model env overrides the manifest model`() {
        val (model, _, _) = parts(env = mapOf("KI_MODEL" to "env-model", "MY_KEY" to "k"))
        assertEquals("env-model", model)
    }

    @Test fun `cli flag beats ki_model env and manifest`() {
        val args = CliArgs(configPath = writeManifest(manifestToml), model = "cli-model")
        val (model, _, _) = parts(args, env = mapOf("KI_MODEL" to "env-model", "MY_KEY" to "k"))
        assertEquals("cli-model", model)
    }

    @Test fun `blank ki_model env is ignored`() {
        val (model, _, _) = parts(env = mapOf("KI_MODEL" to "  ", "MY_KEY" to "k"))
        assertEquals("manifest-model", model)
    }

    @Test fun `litellm_base_url env overrides the manifest base url`() {
        val (_, _, url) = parts(env = mapOf("LITELLM_BASE_URL" to "http://env:4000", "MY_KEY" to "k"))
        assertEquals("http://env:4000", url)
    }

    @Test fun `manifest key env wins over the litellm fallback`() {
        val (_, key, _) = parts(env = mapOf("MY_KEY" to "primary", "LITELLM_API_KEY" to "fallback"))
        assertEquals("primary", key)
    }

    @Test fun `litellm_api_key is the fallback when the manifest env var is unset`() {
        val (_, key, _) = parts(env = mapOf("LITELLM_API_KEY" to "fallback"))
        assertEquals("fallback", key)
    }

    @Test fun `no key anywhere is an error naming the manifest env var`() {
        val e = assertFailsWith<IllegalStateException> { parts(env = emptyMap()) }
        assertTrue(e.message!!.contains("MY_KEY"), e.message)
    }

    // --- resolveForPrint (the --print-resolved view) ---------------------------

    @Test fun `print view reflects hat merge env precedence and skills`() {
        val dir = Files.createTempDirectory("ki-print")
        dir.resolve("ki.toml").writeText(manifestToml.trimIndent())
        val hatDir = dir.resolve(".pi/hats/bot").also { Files.createDirectories(it) }
        hatDir.resolve("ki.toml").writeText(
            """
            [llm]
            model = "hat-model"

            [agent]
            systemPrompt = "You are a hat."

            [pi]
            hatDescription = "test hat"
            skills = ["shifts-schema"]

            [tools.edit]
            """.trimIndent()
        )
        val args = CliArgs(configPath = dir.resolve("ki.toml"), hat = "bot")
        val node = Bootstrap.resolveForPrint(args) { k -> mapOf("MY_KEY" to "k")[k] }

        assertEquals("bot", node.get("hat").asText())
        assertEquals("hat-model", node.get("model").asText())
        assertEquals("http://manifest:4000", node.get("baseUrl").asText())
        assertEquals("You are a hat.", node.get("systemPrompt").asText())
        assertEquals("test hat", node.get("hatDescription").asText())
        assertEquals(listOf("edit"), node.get("toolNames").map { it.asText() }, "hat list is the active allowlist")
        assertEquals(listOf("shifts-schema"), node.get("skills").map { it.asText() })
        assertTrue(node.get("apiKeySet").asBoolean())
    }

    @Test fun `print view without a hat keeps the root allowlist and no systemPrompt`() {
        val args = CliArgs(configPath = writeManifest(manifestToml))
        val node = Bootstrap.resolveForPrint(args) { k -> mapOf("MY_KEY" to "k")[k] }
        assertTrue(node.get("hat").isNull)
        assertTrue(node.get("systemPrompt").isNull)
        assertEquals(listOf("bash"), node.get("toolNames").map { it.asText() })
    }

    @Test fun `print view lists hat script tools before build validation`() {
        val dir = Files.createTempDirectory("ki-print-bad")
        dir.resolve("ki.toml").writeText(manifestToml.trimIndent())
        val hatDir = dir.resolve(".pi/hats/bot").also { Files.createDirectories(it) }
        hatDir.resolve("ki.toml").writeText("[tools.frobnicate]\n")
        // The print view shows the resolved allowlist; whether frobnicate can actually be
        // built (builtin or script) is validated later, by buildTools.
        val node = Bootstrap.resolveForPrint(CliArgs(configPath = dir.resolve("ki.toml"), hat = "bot")) { "k" }
        assertEquals(listOf("frobnicate"), node.get("toolNames").map { it.asText() })
    }
}
