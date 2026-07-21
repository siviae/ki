package dev.ki.agent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the extension script contract: `loadExtension` accepts an `extension { ... }`
 * block (tools + hooks) and also lifts a bare `tool("...") { ... }` script into a
 * single-tool, zero-hook extension — so every existing `[tools.*]` script is a valid
 * extension. Compiles real scripts, so it runs the Kotlin scripting host.
 */
class ScriptExtensionLoaderTest {

    private fun tempScript(body: String): Pair<ScriptToolLoader, File> {
        val dir = File.createTempFile("ki-ext", "").apply { delete(); mkdirs() }
        val script = File(dir, "ext.ki.kts").apply { writeText(body.trimIndent()) }
        return ScriptToolLoader(File(dir, "cache")) to script
    }

    @Test fun `a bare tool script is lifted into a single-tool extension`() {
        val (loader, script) = tempScript(
            """
            tool("echo") {
                description = "Echo."
                param("v", "value", ParamType.STRING, required = true)
                execute { args -> args.string("v") }
            }
            """,
        )

        val ext = loader.loadExtension(script)
        assertEquals(1, ext.tools.size)
        assertEquals("echo", ext.tools.single().name)
        assertTrue(ext.toolCallHooks.isEmpty() && ext.toolResultHooks.isEmpty())
        assertTrue(ext.providerRequestHooks.isEmpty() && ext.sessionStartHooks.isEmpty())
    }

    @Test fun `an extension block registers both tools and hooks`() {
        val (loader, script) = tempScript(
            """
            extension {
                tool("safe") {
                    description = "A tool."
                    execute { "ok" }
                }
                onToolCall("bash") { _, args ->
                    if (args.stringOrNull("cmd")?.startsWith("rm") == true)
                        InterceptionResult.Block("rm blocked")
                    else InterceptionResult.Permit
                }
                onToolResult("read") { _, r -> r.trim() }
            }
            """,
        )

        val ext = loader.loadExtension(script)
        assertEquals(listOf("safe"), ext.tools.map { it.name })
        assertEquals(1, ext.toolCallHooks.size)
        assertEquals(1, ext.toolResultHooks.size)

        val hook = ext.toolCallHooks.single()
        assertTrue(hook.appliesTo("bash"))
        val blocked = runBlocking { hook.fn("bash", ToolArgs(buildJsonObject { })) }
        // No `cmd` arg → not an rm → permitted.
        assertEquals(InterceptionResult.Permit, blocked)
    }
}
