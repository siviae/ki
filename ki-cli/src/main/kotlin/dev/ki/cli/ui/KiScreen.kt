package dev.ki.cli.ui

import casciian.TAction
import casciian.TApplication
import casciian.TField
import casciian.TText
import casciian.TWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The pi-style TUI on casciian: a single maximized window with a scrolling
 * transcript (top) and a single-line input (bottom), plus a status-bar footer.
 * Live token-by-token streaming and the colored thinking-depth editor border are
 * later milestones; M1 renders the final assistant text and tool activity.
 */
class KiScreen(
    private val app: TApplication,
    runner: suspend (String) -> String,
) {
    private val window = TWindow(app, "ki — coding agent", 0, 0, 100, 30)
    private val transcript: TText
    private val input: TField
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridge = AgentBridge(scope, app::invokeLater, runner)
    private var busy = false

    init {
        window.maximize()
        val w = window.width - 2
        val h = window.height - 2
        transcript = TText(window, "", 1, 1, w, h - 3)
        input = TField(
            window, 1, h - 1, w, false, "",
            object : TAction() {
                override fun DO() = onSubmit()
            },
        )
        window.newStatusBar("ki • ready — Enter to send, Ctrl-Q to quit")
        appendLine("Welcome to ki. Type a prompt and press Enter.")
    }

    private fun onSubmit() {
        if (busy) return
        val text = input.text.trim()
        if (text.isEmpty()) return
        input.text = ""
        appendLine("› $text")
        busy = true
        window.statusBar?.setText("ki • thinking…")
        bridge.submit(text) { result ->
            appendLine(result)
            busy = false
            window.statusBar?.setText("ki • ready")
        }
    }

    /** Append a line to the transcript. Must run on the UI thread. */
    fun appendLine(s: String) {
        s.split("\n").forEach { transcript.addLine(it) }
        transcript.reflowData()
    }
}
