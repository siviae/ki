package dev.ki.tui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * The framework core: owns the component tree, the input loop, and the
 * differential renderer. A Kotlin port of pi's `tui.ts` render strategy — render
 * the whole component tree to lines, diff against the previous frame, and emit
 * only the changed rows, all wrapped in synchronized-output (CSI 2026) so each
 * frame lands atomically without flicker.
 *
 * Threading: all component mutation and rendering happen on a single UI thread
 * (a single-thread executor). Terminal input and resize events are marshaled onto
 * it, as is [post] — the seam an async agent uses to update the UI safely.
 *
 * Deferred vs. pi (see PLAN M2 backlog): inline images, overlays, Kitty keyboard
 * protocol, IME hardware-cursor positioning.
 */
class Tui(private val terminal: Terminal) {
    private val root = Container()
    private var focus: Component? = null
    private val inputListeners = ArrayList<(String) -> Boolean>()
    private val ui = Executors.newSingleThreadExecutor { r -> Thread(r, "ki-tui-ui").apply { isDaemon = true } }
    private val stopLatch = CountDownLatch(1)

    @Volatile private var stopped = false
    private var renderRequested = false

    // Differential-render state (buffer-absolute row coordinates).
    private var previousLines: List<String> = emptyList()
    private var previousWidth = 0
    private var previousHeight = 0
    private var previousViewportTop = 0
    private var cursorRow = 0
    private var hardwareCursorRow = 0
    private var maxLinesRendered = 0

    /** Count of full redraws performed — observable for tests/diagnostics. */
    @Volatile var fullRedraws = 0
        private set

    /** When content shrinks below the high-water mark, do a full redraw to clear
     *  the now-stale rows (this clears scrollback). Defaults to true, matching pi. */
    var clearOnShrink = true

    // ---- public API ---------------------------------------------------------

    /** Add a child. Mutation is marshaled onto the UI thread to keep the
     *  component tree single-threaded (renders run there too); the render is
     *  requested *after* the add so it reflects this and any earlier queued adds. */
    fun addChild(c: Component) { post(Runnable { root.add(c); requestRender() }) }

    fun setFocus(c: Component?) { focus = c }

    /** Register a global input listener. Return true to consume (stop dispatch). */
    fun addInputListener(l: (String) -> Boolean) { inputListeners.add(l) }

    /** Marshal [r] onto the UI thread (used by async producers). */
    fun post(r: Runnable) { if (!stopped) ui.execute(r) }

    /** Request a coalesced re-render on the UI thread. */
    fun requestRender() {
        if (stopped || renderRequested) return
        renderRequested = true
        ui.execute { renderRequested = false; doRender() }
    }

    fun start() {
        terminal.start(
            onInput = { data -> post(Runnable { dispatchInput(data) }) },
            onResize = { post(Runnable { forceRedraw(); doRender() }) },
        )
        requestRender()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        terminal.stop()
        ui.shutdown()
        stopLatch.countDown()
    }

    /** Block the caller until [stop] is invoked (e.g. from a quit keybinding). */
    fun awaitStop() = stopLatch.await()

    /** Block until all queued UI work (renders) has drained. Test/diagnostic aid. */
    fun awaitIdle() {
        if (stopped) return
        val latch = CountDownLatch(1)
        ui.execute { latch.countDown() }
        latch.await()
    }

    // ---- input --------------------------------------------------------------

    private fun dispatchInput(data: String) {
        // Split batched input so a glued "text\r" or escape+text delivers each key.
        for (seg in Keys.splitInput(data)) {
            if (inputListeners.any { it(seg) }) continue
            focus?.handleInput(seg)
        }
        requestRender()
    }

    private fun forceRedraw() {
        previousLines = emptyList()
        previousWidth = 0
        previousHeight = 0
        root.invalidate()
    }

    // ---- differential renderer (port of pi doRender, images/overlays elided) --

    private fun doRender() {
        if (stopped) return
        val width = terminal.columns
        val height = terminal.rows
        val widthChanged = previousWidth != 0 && previousWidth != width
        val heightChanged = previousHeight != 0 && previousHeight != height
        val previousBufferLength = if (previousHeight > 0) previousViewportTop + previousHeight else height
        var prevViewportTop = if (heightChanged) maxOf(0, previousBufferLength - height) else previousViewportTop
        var viewportTop = prevViewportTop
        var hwCursor = hardwareCursorRow

        // Defensive: never emit a line wider than the viewport (renderer contract),
        // then append a per-line reset so styles/hyperlinks never bleed into the
        // next row (pi's applyLineResets).
        val newLines = root.render(width).map {
            val clamped = if (Width.visibleWidth(it) > width) Width.truncateToWidth(it, width) else it
            clamped + Ansi.SEGMENT_RESET
        }

        fun computeLineDiff(targetRow: Int): Int {
            val currentScreenRow = hwCursor - prevViewportTop
            val targetScreenRow = targetRow - viewportTop
            return targetScreenRow - currentScreenRow
        }

        fun fullRender(clear: Boolean) {
            fullRedraws++
            val sb = StringBuilder(Ansi.SYNC_BEGIN)
            if (clear) sb.append(Ansi.CLEAR_SCREEN)
            for (i in newLines.indices) {
                if (i > 0) sb.append("\r\n")
                sb.append(newLines[i])
            }
            sb.append(Ansi.SYNC_END)
            terminal.write(sb.toString())
            cursorRow = maxOf(0, newLines.size - 1)
            hardwareCursorRow = cursorRow
            maxLinesRendered = if (clear) newLines.size else maxOf(maxLinesRendered, newLines.size)
            val bufferLength = maxOf(height, newLines.size)
            previousViewportTop = maxOf(0, bufferLength - height)
            previousLines = newLines
            previousWidth = width
            previousHeight = height
        }

        // First render — assume a clean area, draw inline without clearing scrollback.
        if (previousLines.isEmpty() && !widthChanged && !heightChanged) { fullRender(false); return }
        // Width change re-wraps everything; height change realigns the viewport.
        if (widthChanged || heightChanged) { fullRender(true); return }
        if (clearOnShrink && newLines.size < maxLinesRendered) { fullRender(true); return }

        // Find the changed row range.
        var firstChanged = -1
        var lastChanged = -1
        val maxLines = maxOf(newLines.size, previousLines.size)
        for (i in 0 until maxLines) {
            val oldL = previousLines.getOrElse(i) { "" }
            val newL = newLines.getOrElse(i) { "" }
            if (oldL != newL) {
                if (firstChanged == -1) firstChanged = i
                lastChanged = i
            }
        }
        val appendedLines = newLines.size > previousLines.size
        if (appendedLines) {
            if (firstChanged == -1) firstChanged = previousLines.size
            lastChanged = newLines.size - 1
        }
        val appendStart = appendedLines && firstChanged == previousLines.size && firstChanged > 0

        // No change: nothing to write.
        if (firstChanged == -1) { previousViewportTop = prevViewportTop; previousHeight = height; return }
        // Only tail deletions with no surviving-row change, or a change above the
        // visible viewport — safest to fully repaint.
        if (firstChanged >= newLines.size || firstChanged < prevViewportTop) { fullRender(true); return }

        val sb = StringBuilder(Ansi.SYNC_BEGIN)
        val prevViewportBottom = prevViewportTop + height - 1
        val moveTargetRow = if (appendStart) firstChanged - 1 else firstChanged

        // Scroll the viewport if the change is below the current bottom.
        if (moveTargetRow > prevViewportBottom) {
            val currentScreenRow = maxOf(0, minOf(height - 1, hwCursor - prevViewportTop))
            val moveToBottom = height - 1 - currentScreenRow
            if (moveToBottom > 0) sb.append(Ansi.moveDown(moveToBottom))
            val scroll = moveTargetRow - prevViewportBottom
            repeat(scroll) { sb.append("\r\n") }
            prevViewportTop += scroll
            viewportTop += scroll
            hwCursor = moveTargetRow
        }

        // Move to the first changed row, column 0.
        val lineDiff = computeLineDiff(moveTargetRow)
        if (lineDiff > 0) sb.append(Ansi.moveDown(lineDiff)) else if (lineDiff < 0) sb.append(Ansi.moveUp(-lineDiff))
        sb.append(if (appendStart) "\r\n" else "\r")

        // Rewrite only the changed rows (reduces flicker for single-line updates).
        val renderEnd = minOf(lastChanged, newLines.size - 1)
        for (i in firstChanged..renderEnd) {
            if (i > firstChanged) sb.append("\r\n")
            sb.append(Ansi.CLEAR_LINE).append(newLines[i])
        }

        var finalCursorRow = renderEnd
        // Clear rows that existed before but are gone now.
        if (previousLines.size > newLines.size) {
            if (renderEnd < newLines.size - 1) {
                sb.append(Ansi.moveDown(newLines.size - 1 - renderEnd))
                finalCursorRow = newLines.size - 1
            }
            val extra = previousLines.size - newLines.size
            repeat(extra) { sb.append("\r\n").append(Ansi.CLEAR_LINE) }
            sb.append(Ansi.moveUp(extra))
        }

        sb.append(Ansi.SYNC_END)
        terminal.write(sb.toString())

        cursorRow = finalCursorRow
        hardwareCursorRow = finalCursorRow
        maxLinesRendered = maxOf(maxLinesRendered, newLines.size)
        val bufferLength = maxOf(height, newLines.size)
        previousViewportTop = maxOf(0, bufferLength - height)
        previousLines = newLines
        previousWidth = width
        previousHeight = height
    }
}
