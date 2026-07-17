package dev.ki.cli

import dev.ki.cli.config.Bootstrap
import dev.ki.cli.config.CliArgs
import dev.ki.cli.config.ManifestException
import dev.ki.cli.ui.KiScreen
import dev.ki.tui.ProcessTerminal
import dev.ki.tui.Tui
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private val log = KotlinLogging.logger {}

private val SYSTEM_PROMPT = """
    You are ki, a terse coding agent. Use the provided tools to work in the repo:
    read and write files, edit with exact text replacement, list directories, grep,
    and run bash commands. Keep answers minimal and direct.
""".trimIndent()

fun main(argv: Array<String>) {
    val args = CliArgs.parse(argv)
    // Must precede any logger use so logback picks up level + dir on first init.
    Logging.configure(args, args.dbPath)

    val session = try {
        Bootstrap.build(args, SYSTEM_PROMPT)
    } catch (e: ManifestException) {
        System.err.println("ki: ${e.message}")
        exitProcess(2)
    }
    log.info { "ki session ${session.sessionId} started (model=${session.config.defaultModelId}, oneShot=${session.oneShotPrompt != null})" }

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
