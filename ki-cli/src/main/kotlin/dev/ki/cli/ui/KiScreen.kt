package dev.ki.cli.ui

import dev.ki.agent.ToolCallEvent
import dev.ki.agent.ToolPhase
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

    // Every tool-call row ever added this session (across turns), so Ctrl-O can expand/collapse
    // all of them at once — pi-style. Cleared by /clear along with the rest of the transcript.
    private val allToolLines = mutableListOf<ToolCallLine>()
    private var toolsExpanded = false

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
                Keys.matchesKey(data, Key.CTRL_O) -> { toggleToolExpansion(); true }
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
            is SlashAction.Clear -> { transcript.clear(); allToolLines.clear() }
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

        // Live reasoning region: a dimmed block filled by streamed thinking deltas (M9.1).
        val thinking = ThinkingBlock()
        val reasoning = StringBuilder()
        transcript.add(thinking)
        tui.requestRender()

        // Tool-call lines, keyed by koog's call id so completion recolors the same row (M9.2).
        val toolLines = HashMap<String, ToolCallLine>()

        bridge.submit(
            text,
            onReasoning = { delta ->
                reasoning.append(delta)
                thinking.set(reasoning.toString())
                tui.requestRender()
            },
            onTool = { event -> onToolEvent(event, toolLines) },
        ) { result ->
            appendLine(result)
            busy = false
            status.set(idleStatus())
            tui.requestRender()
        }
    }

    /** Add or recolor the transcript's tool-call line as a call moves STARTING → OK/ERROR. */
    private fun onToolEvent(event: ToolCallEvent, lines: MutableMap<String, ToolCallLine>) {
        when (event.phase) {
            ToolPhase.STARTING -> {
                val line = ToolCallLine(event.name, event.args)
                line.setExpanded(toolsExpanded)
                lines[event.id] = line
                allToolLines.add(line)
                transcript.add(line)
            }
            ToolPhase.OK -> lines[event.id]?.set(ToolCallLine.Phase.SUCCESS, event.result)
            ToolPhase.ERROR -> lines[event.id]?.set(ToolCallLine.Phase.ERROR, event.result)
        }
        tui.requestRender()
    }

    /** Ctrl-O: expand/collapse every tool-call row's result preview, this turn and past ones. */
    private fun toggleToolExpansion() {
        toolsExpanded = !toolsExpanded
        allToolLines.forEach { it.setExpanded(toolsExpanded) }
        tui.requestRender()
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

/**
 * A dimmed, word-wrapped "thinking" region filled incrementally by streamed reasoning
 * deltas (M9.1). Empty text renders nothing; each line is padded to full width (so a
 * differential redraw overwrites cleanly) then dimmed, with a `💭 ` first-line marker.
 */
internal class ThinkingBlock : Component {
    @Volatile
    private var text: String = ""

    fun set(value: String) { text = value }

    override fun render(width: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val wrapped = Width.wrapText(text.replace("\t", "   "), (width - MARKER_CELLS).coerceAtLeast(1))
        return wrapped.mapIndexed { i, line ->
            val prefix = if (i == 0) "💭 " else "   "
            // truncateToWidth(pad=true) guarantees exactly `width` cells even when the
            // marker alone would overflow a very narrow viewport (Component contract).
            Ansi.dim(Width.truncateToWidth(prefix + line, width, ellipsis = "", pad = true))
        }
    }

    private companion object { const val MARKER_CELLS = 3 } // "💭 " = 2-cell emoji + space
}

/**
 * A pi-style tool-call row: a bold tool name + compact args on a full-width background
 * stripe whose color tracks the call's lifecycle (M9.2) — pending blue-gray while running,
 * dark green on success, dark red on error. Ported from pi's `tool-execution.ts` /
 * `dark.json` (`toolPendingBg` #282832 / `toolSuccessBg` #283228 / `toolErrorBg` #3c2828),
 * emitted as truecolor. The bold title uses SGR-22 so it nests inside the stripe.
 *
 * Once the call lands (OK/ERROR) and carries a [result], a dimmed preview of up to
 * [MAX_RESULT_LINES] wrapped lines is rendered below the stripe — the actual tool output,
 * not just the pass/fail color. A result longer than that is capped with a
 * "N more lines (ctrl-o to expand)" hint; [setExpanded] (wired to Ctrl-O in [KiScreen])
 * shows the full output instead, pi-style.
 */
internal class ToolCallLine(private val name: String, private val args: String) : Component {
    enum class Phase { PENDING, SUCCESS, ERROR }

    @Volatile
    private var phase = Phase.PENDING

    @Volatile
    private var result: String? = null

    @Volatile
    private var expanded = false

    fun set(p: Phase, result: String? = null) {
        phase = p
        this.result = result
    }

    /** Toggle between the capped preview and the full result text (Ctrl-O). */
    fun setExpanded(value: Boolean) { expanded = value }

    override fun render(width: Int): List<String> {
        val title = if (args.isBlank()) name else "$name($args)"
        // Pad/truncate to exactly `width` so the color stripe spans the full row.
        val body = Width.truncateToWidth("⏺ $title", width, ellipsis = "…", pad = true)
        val (r, g, b) = when (phase) {
            Phase.PENDING -> Triple(40, 40, 50)
            Phase.SUCCESS -> Triple(40, 50, 40)
            Phase.ERROR -> Triple(60, 40, 40)
        }
        val stripe = Ansi.bgRgb(r, g, b, Ansi.boldIn(body))

        val preview = result?.trim()?.takeIf { it.isNotEmpty() } ?: return listOf(stripe)
        val wrapped = Width.wrapText(preview.replace("\t", "  "), (width - RESULT_INDENT.length).coerceAtLeast(1))

        if (expanded || wrapped.size <= MAX_RESULT_LINES) {
            return listOf(stripe) + wrapped.map { dimIndented(it, width) }
        }

        val shown = wrapped.take(MAX_RESULT_LINES - 1)
        val hidden = wrapped.size - shown.size
        val hint = "… $hidden more line${if (hidden == 1) "" else "s"} (ctrl-o to expand)"
        return listOf(stripe) + shown.map { dimIndented(it, width) } + dimIndented(hint, width)
    }

    private fun dimIndented(text: String, width: Int): String =
        Ansi.dim(Width.truncateToWidth(RESULT_INDENT + text, width, ellipsis = "…", pad = false))

    private companion object {
        const val RESULT_INDENT = "  "
        const val MAX_RESULT_LINES = 6
    }
}

/** A single full-width inverted status bar line. */
private class StatusLine(text: String) : Component {
    private var text = text
    fun set(value: String) { text = value }
    override fun render(width: Int): List<String> =
        listOf(Ansi.invert(Width.padTo(" $text", width)))
}
