package dev.ki.cli.ui

import dev.ki.cli.KiController
import dev.ki.tui.Ansi
import dev.ki.tui.Component
import dev.ki.tui.Container
import dev.ki.tui.Editor
import dev.ki.tui.Key
import dev.ki.tui.Keys
import dev.ki.tui.Spacer
import dev.ki.tui.Text
import dev.ki.tui.Tui
import dev.ki.tui.Width
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The pi-style coding-agent UI, on our own [Tui]: an inline, scrollback-friendly
 * transcript with a fixed prompt editor and a status line. Slash commands are
 * dispatched before a line reaches the agent; an in-flight turn is cancellable
 * (Esc / Ctrl-C); the status line shows model, context usage, running cost, and the
 * currently executing tool.
 */
class KiScreen(
    private val tui: Tui,
    private val controller: KiController,
) {
    private val transcript = Container()
    private val editor = Editor()
    private val status = StatusLine("")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridge = AgentBridge(scope, tui::post, controller::run)
    private var busy = false

    init {
        tui.addChild(transcript)
        tui.addChild(Spacer(1))
        tui.addChild(editor)
        tui.addChild(status)
        tui.setFocus(editor)

        editor.onChange = { tui.requestRender() }
        editor.onSubmit = { onSubmit(it) }

        // Raw mode swallows signals; drive quit/cancel from keys.
        tui.addInputListener { data ->
            when {
                Keys.matchesKey(data, Key.CTRL_Q) -> { tui.stop(); true }
                Keys.matchesKey(data, Key.CTRL_C) -> { if (busy) cancel() else tui.stop(); true }
                Keys.matchesKey(data, Key.ESCAPE) && busy -> { cancel(); true }
                else -> false
            }
        }

        status.set(idleStatus())
        appendLine("Welcome to ki. Type a prompt, or /help for commands.")
    }

    private fun onSubmit(text: String) {
        if (busy) return
        editor.clear()
        if (text.isBlank()) return

        when (val action = SlashCommands.dispatch(text, controller)) {
            is SlashAction.NotACommand -> { runTurn(action.text); return }
            is SlashAction.Show -> { echo(text); appendLine(action.text) }
            is SlashAction.Clear -> { transcript.clear() }
            is SlashAction.Quit -> { tui.stop(); return }
            is SlashAction.SwitchModel -> { echo(text); appendLine(controller.switchModel(action.name)); status.set(idleStatus()) }
            is SlashAction.Resume -> { echo(text); appendLine(controller.resume(action.id)); status.set(idleStatus()) }
            is SlashAction.Unknown -> { echo(text); appendLine("Unknown command /${action.name}. Try /help.") }
        }
        tui.requestRender()
    }

    private fun runTurn(text: String) {
        echo(text)
        busy = true
        status.set("ki • thinking… (Esc to cancel)")
        tui.requestRender()
        bridge.submit(text) { result ->
            appendLine(result)
            busy = false
            status.set(idleStatus())
            tui.requestRender()
        }
    }

    private fun cancel() {
        bridge.cancel {
            appendLine("⨯ cancelled")
            busy = false
            status.set(idleStatus())
            tui.requestRender()
        }
    }

    private fun echo(text: String) = appendLine("› $text")

    /** Append a transcript entry (may contain newlines). Runs on the UI thread. */
    fun appendLine(s: String) {
        transcript.add(Text(s))
        tui.requestRender()
    }

    /** "ki • gpt-4o · 1.2k/128k (1%) · $0.0021 · bash" — pieces present only when known. */
    private fun idleStatus(): String {
        val parts = ArrayList<String>()
        parts.add("ki • ${controller.model()}")
        controller.usage()?.let { u ->
            val mark = if (u.reported) "" else "~"
            parts.add("$mark${u.tokens}/${u.window} (${u.percent}%)")
        }
        controller.costUsd()?.let { parts.add("$" + String.format("%.4f", it)) }
        controller.currentTool()?.let { parts.add(it) }
        return parts.joinToString(" · ")
    }
}

/** A single full-width inverted status bar line. */
private class StatusLine(text: String) : Component {
    private var text = text
    fun set(value: String) { text = value }
    override fun render(width: Int): List<String> =
        listOf(Ansi.invert(Width.padTo(" $text", width)))
}
