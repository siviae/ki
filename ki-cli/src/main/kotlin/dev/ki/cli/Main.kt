package dev.ki.cli

import dev.ki.agent.KiAgent
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

    val agent = KiAgent(
        llm = session.llm,
        systemPrompt = session.systemPrompt,
        tools = session.tools,
        historyProvider = session.historyProvider,
    )
    val runner: suspend (String) -> String = { input -> agent.run(input, session.sessionId) }

    session.store.use {
        // One-shot mode: run a single prompt, print the reply, exit.
        session.oneShotPrompt?.let { prompt ->
            println(runBlocking { runner(prompt) })
            return
        }

        val tui = Tui(ProcessTerminal())
        KiScreen(tui, runner, usage = { agent.lastUsage })
        tui.start()
        tui.awaitStop()
    }
}
