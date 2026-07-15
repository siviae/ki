package dev.ki.cli

import ai.koog.agents.core.tools.ToolBase
import casciian.TApplication
import dev.ki.agent.KiAgent
import dev.ki.agent.tools.ScriptToolLoader
import dev.ki.ai.KiLlm
import dev.ki.cli.ui.KiScreen
import java.io.File

private val SYSTEM_PROMPT = """
    You are ki, a terse coding agent. Use the provided tools to read files and
    search the codebase. Keep answers minimal and direct.
""".trimIndent()

/** Bundled default tool scripts, extracted to .ki/tools on first run. */
private val BUNDLED_SCRIPTS = listOf("grep.ki.kts")

fun main() {
    val llm = KiLlm.fromEnv()

    val toolsDir = File(".ki/tools")
    extractBundledScripts(toolsDir)
    print("Compiling tool scripts… ")
    val scriptTools = ScriptToolLoader().loadAll(toolsDir)
    println("${scriptTools.size} loaded.")

    val tools: List<ToolBase<*, *>> = BuiltinTools.all() + scriptTools
    val agent = KiAgent(llm, SYSTEM_PROMPT, tools)

    val app = TApplication(TApplication.BackendType.XTERM)
    KiScreen(app, agent::run)
    app.run()
}

/** Copy bundled scripts from resources into [toolsDir] if not already present. */
private fun extractBundledScripts(toolsDir: File) {
    toolsDir.mkdirs()
    for (name in BUNDLED_SCRIPTS) {
        val dest = File(toolsDir, name)
        if (dest.exists()) continue
        val res = object {}.javaClass.getResourceAsStream("/tools/$name") ?: continue
        res.use { input -> dest.outputStream().use { input.copyTo(it) } }
    }
}
