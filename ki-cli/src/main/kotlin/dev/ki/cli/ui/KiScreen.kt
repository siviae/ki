package dev.ki.cli.ui

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
 * The pi-style coding-agent UI, now on our own [Tui]: an inline, scrollback-
 * friendly transcript (grows downward) with a fixed prompt editor and status line
 * pinned at the bottom. The differential renderer only repaints the changed rows,
 * so streaming updates and edits don't flicker. Casciian is gone.
 */
class KiScreen(
    private val tui: Tui,
    runner: suspend (String) -> String,
) {
    private val transcript = Container()
    private val editor = Editor()
    private val status = StatusLine("ki • ready — Enter to send, Ctrl-Q to quit")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridge = AgentBridge(scope, tui::post, runner)
    private var busy = false

    init {
        tui.addChild(transcript)
        tui.addChild(Spacer(1))
        tui.addChild(editor)
        tui.addChild(status)
        tui.setFocus(editor)

        editor.onChange = { tui.requestRender() }
        editor.onSubmit = { onSubmit(it) }

        // Raw mode swallows SIGINT; intercept Ctrl-C / Ctrl-Q to quit cleanly.
        tui.addInputListener { data ->
            if (Keys.matchesKey(data, Key.CTRL_C) || Keys.matchesKey(data, Key.CTRL_Q)) {
                tui.stop(); true
            } else {
                false
            }
        }

        appendLine("Welcome to ki. Type a prompt and press Enter.")
    }

    private fun onSubmit(text: String) {
        if (busy) return
        editor.clear()
        appendLine("› $text")
        busy = true
        status.set("ki • thinking…")
        tui.requestRender()
        bridge.submit(text) { result ->
            appendLine(result)
            busy = false
            status.set("ki • ready")
            tui.requestRender()
        }
    }

    /** Append a transcript entry (may contain newlines). Runs on the UI thread. */
    fun appendLine(s: String) {
        transcript.add(Text(s))
        tui.requestRender()
    }
}

/** A single full-width inverted status bar line. */
private class StatusLine(text: String) : Component {
    private var text = text
    fun set(value: String) { text = value }
    override fun render(width: Int): List<String> =
        listOf(Ansi.invert(Width.padTo(" $text", width)))
}
