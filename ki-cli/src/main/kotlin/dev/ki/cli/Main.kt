package dev.ki.cli

import dev.ki.cli.config.Bootstrap
import dev.ki.cli.config.CliArgs
import dev.ki.cli.config.ManifestException
import dev.ki.cli.ui.KiScreen
import dev.ki.tui.ProcessTerminal
import dev.ki.tui.Tui
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private val SYSTEM_PROMPT = """
    You are ki, a terse coding agent. Use the provided tools to work in the repo:
    read and write files, edit with exact text replacement, list directories, grep,
    and run bash commands. Keep answers minimal and direct.
""".trimIndent()

fun main(argv: Array<String>) {
    val args = CliArgs.parse(argv)

    val session = try {
        Bootstrap.build(args, SYSTEM_PROMPT)
    } catch (e: ManifestException) {
        System.err.println("ki: ${e.message}")
        exitProcess(2)
    }

    val controller = KiController(session)

    session.store.use {
        // One-shot mode: run a single prompt, print the reply, exit.
        session.oneShotPrompt?.let { prompt ->
            println(runBlocking { controller.run(prompt) })
            return
        }

        val tui = Tui(ProcessTerminal())
        KiScreen(tui, controller)
        tui.start()
        tui.awaitStop()
    }
}
