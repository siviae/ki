package dev.ki.cli.config

import dev.ki.agent.tools.InterceptionResult
import dev.ki.agent.tools.ScriptToolLoader
import dev.ki.agent.tools.ToolArgs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end for `extension-config`: an extension declares its own config data classes, the
 * loader fills them from the **merged manifest tree** (no re-parse), and a hook reads the typed
 * value. Compiles a real script, so it also verifies the load-bearing risk — Jackson
 * `treeToValue` into a *script-compiled* class (loaded by the scripting host's classloader).
 */
class ExtensionConfigTest {

    /** Mirror of Bootstrap.buildExtensions' fill step (kept private there). */
    private fun loadAndFill(scriptsDir: File, tree: com.fasterxml.jackson.databind.node.ObjectNode) =
        ScriptToolLoader(File(scriptsDir, "cache")).loadExtension(File(scriptsDir, "guards.ki.kts")).also { ext ->
            for (req in ext.configRequests) {
                val node = if (req.section == null) tree else tree.get(req.section)
                req.fill(ManifestLoader.decode(node, req.type.java))
            }
        }

    private val guardsScript = """
        data class BashRules(val unrestricted: Boolean = false, val commands: Map<String, Any?> = emptyMap())
        data class FileRules(val edit: List<String> = emptyList())
        data class GuardConfig(val bash: BashRules = BashRules(), val files: FileRules = FileRules())

        extension {
            val cfg = config<GuardConfig>()                      // section = null -> whole manifest root
            onToolCall("bash") { _, args ->
                val name = args.string("cmd").trim().substringBefore(' ')
                val bash = cfg().bash
                if (!bash.unrestricted && name !in bash.commands.keys)
                    InterceptionResult.Block("blocked: " + name)
                else InterceptionResult.Permit
            }
        }
    """.trimIndent()

    @Test fun `extension reads typed config assembled from the merged manifest root`() {
        val dir = Files.createTempDirectory("ki-extcfg")
        File(dir.toFile(), "guards.ki.kts").writeText(guardsScript)
        dir.resolve("ki.toml").writeText(
            """
            [llm]
            base_url = "http://proxy:4000"
            api_key_env = "MY_KEY"
            model = "fast"

            [bash]
            unrestricted = false
              [bash.commands.git]
              [bash.commands.ls]

            [files]
            edit = ["**/todos/**"]

            [extensions.guards]
            script = "guards.ki.kts"
            """.trimIndent(),
        )

        val loaded = ManifestLoader.load(dir.resolve("ki.toml"))
        val ext = loadAndFill(dir.toFile(), loaded.tree)
        val hook = ext.toolCallHooks.single()

        runBlocking {
            // 'git' is in [bash.commands] -> permitted; 'rm' is not -> blocked with reason.
            assertEquals(InterceptionResult.Permit, hook.fn("bash", ToolArgs(buildJsonObject { put("cmd", "git status") })))
            val blocked = hook.fn("bash", ToolArgs(buildJsonObject { put("cmd", "rm -rf /") }))
            assertTrue(blocked is InterceptionResult.Block && blocked.reason.contains("rm"), "got: $blocked")
        }
    }

    @Test fun `a missing section fills the config with data-class defaults`() {
        val dir = Files.createTempDirectory("ki-extcfg-def")
        File(dir.toFile(), "guards.ki.kts").writeText(guardsScript)
        dir.resolve("ki.toml").writeText(
            """
            [llm]
            base_url = "http://proxy:4000"
            api_key_env = "MY_KEY"
            model = "fast"

            [extensions.guards]
            script = "guards.ki.kts"
            """.trimIndent(),
        )

        val loaded = ManifestLoader.load(dir.resolve("ki.toml"))
        val ext = loadAndFill(dir.toFile(), loaded.tree)
        val hook = ext.toolCallHooks.single()

        // No [bash] section -> BashRules() defaults: unrestricted=false, empty commands -> everything blocked.
        runBlocking {
            val blocked = hook.fn("bash", ToolArgs(buildJsonObject { put("cmd", "git") }))
            assertTrue(blocked is InterceptionResult.Block, "defaults should block; got: $blocked")
        }
    }
}
