package dev.ki.tui

/**
 * Multi-line prompt editor. Holds a text buffer and a cursor offset, renders a
 * prompt-prefixed, char-wrapped view with a visible (inverted) cursor, and
 * handles the standard emacs-style editing keys plus undo and a one-slot kill
 * ring. Enter submits; Shift+Enter (or a paste containing newlines) inserts a
 * newline.
 *
 * Text is stored as a single string; the cursor is a code-unit offset into it.
 * Display wrapping is character-based (not word-based) so the cursor position
 * maps exactly to a screen cell.
 */
class Editor(
    private val promptPrimary: String = "› ",
    private val promptCont: String = "  ",
) : Component {
    private val buf = StringBuilder()
    private var cursor = 0
    private var killRing = ""
    private val undoStack = ArrayDeque<Pair<String, Int>>()

    /** Called on Enter with the trimmed submitted text (only if non-blank). */
    var onSubmit: ((String) -> Unit)? = null

    /** Called whenever the buffer changes (e.g. to trigger a re-render). */
    var onChange: (() -> Unit)? = null

    val text: String get() = buf.toString()

    fun setText(value: String) {
        buf.setLength(0); buf.append(value); cursor = buf.length; changed()
    }

    fun clear() {
        if (buf.isEmpty()) return
        pushUndo(); buf.setLength(0); cursor = 0; changed()
    }

    // ---- rendering ----------------------------------------------------------

    override fun render(width: Int): List<String> {
        val promptWidth = maxOf(Width.visibleWidth(promptPrimary), Width.visibleWidth(promptCont))
        val inner = (width - promptWidth).coerceAtLeast(1)
        val out = ArrayList<String>()
        val logicalLines = buf.toString().split("\n")
        val (curLine, curCol) = cursorLineCol(logicalLines)

        var displayRow = 0
        for ((li, line) in logicalLines.withIndex()) {
            val chunks = charWrap(line, inner)
            val cursorHere = li == curLine
            var charBase = 0
            for ((ci, chunk) in chunks.withIndex()) {
                val prompt = if (displayRow == 0) promptPrimary else promptCont
                val chunkLen = chunk.length
                // A cursor sitting exactly on a wrap boundary belongs to the *start*
                // of the next chunk, so only the final chunk includes its end index —
                // otherwise the boundary matches two chunks and the cursor renders twice.
                val isLast = ci == chunks.lastIndex
                val hit = if (isLast) curCol in charBase..(charBase + chunkLen)
                          else curCol in charBase until (charBase + chunkLen)
                if (cursorHere && hit) {
                    val styled = styleCursor(chunk, curCol - charBase, inner)
                    if (styled != null) {
                        out.add(Width.padTo(prompt + styled, width)); displayRow++
                    } else {
                        // Cursor sits just past a full-width chunk → render on next row.
                        out.add(Width.padTo(prompt + chunk, width)); displayRow++
                        out.add(Width.padTo(promptCont + Ansi.invert(" "), width)); displayRow++
                    }
                } else {
                    out.add(Width.padTo(prompt + chunk, width)); displayRow++
                }
                charBase += chunkLen
            }
        }
        if (out.isEmpty()) out.add(Width.padTo(promptPrimary + Ansi.invert(" "), width))
        return out
    }

    /** Splice an inverted cursor cell at char column [col] within [chunk].
     *  Returns null if the cursor would fall exactly past a full-width chunk. */
    private fun styleCursor(chunk: String, col: Int, inner: Int): String? {
        if (col >= chunk.length) {
            // Cursor at end of this chunk: append inverted space if room, else overflow.
            if (Width.visibleWidth(chunk) >= inner) return null
            return chunk + Ansi.invert(" ")
        }
        val cp = chunk.codePointAt(col)
        val len = Character.charCount(cp)
        val before = chunk.substring(0, col)
        val at = chunk.substring(col, col + len)
        val after = chunk.substring(col + len)
        return before + Ansi.invert(at) + after
    }

    private fun cursorLineCol(lines: List<String>): Pair<Int, Int> {
        var rem = cursor
        for ((idx, line) in lines.withIndex()) {
            if (rem <= line.length) return idx to rem
            rem -= line.length + 1 // +1 for the '\n'
        }
        val last = lines.lastIndex.coerceAtLeast(0)
        return last to (lines.getOrNull(last)?.length ?: 0)
    }

    /** Character-wrap preserving every char; each chunk is at most [width] cells. */
    private fun charWrap(s: String, width: Int): List<String> {
        if (s.isEmpty()) return listOf("")
        val result = ArrayList<String>()
        val sb = StringBuilder()
        var w = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val cw = Width.charWidth(cp)
            if (w + cw > width && sb.isNotEmpty()) { result.add(sb.toString()); sb.setLength(0); w = 0 }
            sb.appendCodePoint(cp); w += cw; i += Character.charCount(cp)
        }
        result.add(sb.toString())
        return result
    }

    // ---- input --------------------------------------------------------------

    override fun handleInput(data: String) {
        // Bracketed paste: strip markers, insert body verbatim (may contain newlines).
        if (data.startsWith(Keys.PASTE_START)) {
            val body = data.removePrefix(Keys.PASTE_START).removeSuffix(Keys.PASTE_END)
            pushUndo(); insert(body); return
        }
        when (Keys.parse(data)) {
            // Both CR and LF are Enter → submit (pi's behaviour with the Kitty
            // keyboard protocol inactive; ki defers Kitty). A literal newline is
            // inserted via Shift+Enter or a bracketed paste, not a bare LF.
            Key.ENTER -> submit()
            Key.SHIFT_ENTER -> { pushUndo(); insert("\n") }
            Key.BACKSPACE -> backspace()
            Key.DELETE -> deleteForward()
            Key.LEFT -> moveCursor(-1)
            Key.RIGHT -> moveCursor(1)
            Key.HOME, Key.CTRL_A -> cursor = lineStart()
            Key.END, Key.CTRL_E -> cursor = lineEnd()
            Key.WORD_LEFT -> cursor = prevWord()
            Key.WORD_RIGHT -> cursor = nextWord()
            Key.CTRL_K -> killToLineEnd()
            Key.CTRL_U -> killToLineStart()
            Key.CTRL_W -> killPrevWord()
            Key.CTRL_Y -> { if (killRing.isNotEmpty()) { pushUndo(); insert(killRing) } }
            Key.PRINTABLE -> { pushUndo(); insert(data) }
            else -> {
                if (data.length == 1 && data[0].code == 26) undo() // Ctrl-Z
            }
        }
    }

    private fun submit() {
        val t = buf.toString().trim()
        if (t.isEmpty()) return
        onSubmit?.invoke(t)
    }

    private fun insert(s: String) {
        buf.insert(cursor, s); cursor += s.length; changed()
    }

    private fun backspace() {
        if (cursor == 0) return
        pushUndo()
        val start = cursor - Character.charCount(buf.codePointBefore(cursor))
        buf.delete(start, cursor); cursor = start; changed()
    }

    private fun deleteForward() {
        if (cursor >= buf.length) return
        pushUndo()
        val end = cursor + Character.charCount(buf.codePointAt(cursor))
        buf.delete(cursor, end); changed()
    }

    private fun moveCursor(delta: Int) {
        if (delta < 0 && cursor > 0) cursor -= Character.charCount(buf.codePointBefore(cursor))
        else if (delta > 0 && cursor < buf.length) cursor += Character.charCount(buf.codePointAt(cursor))
    }

    private fun lineStart(): Int {
        var i = cursor
        while (i > 0 && buf[i - 1] != '\n') i--
        return i
    }

    private fun lineEnd(): Int {
        var i = cursor
        while (i < buf.length && buf[i] != '\n') i++
        return i
    }

    private fun prevWord(): Int {
        var i = cursor
        while (i > 0 && buf[i - 1].isWhitespace()) i--
        while (i > 0 && !buf[i - 1].isWhitespace()) i--
        return i
    }

    private fun nextWord(): Int {
        var i = cursor
        while (i < buf.length && buf[i].isWhitespace()) i++
        while (i < buf.length && !buf[i].isWhitespace()) i++
        return i
    }

    private fun killToLineEnd() {
        val end = lineEnd()
        if (end == cursor) return
        pushUndo(); killRing = buf.substring(cursor, end); buf.delete(cursor, end); changed()
    }

    private fun killToLineStart() {
        val start = lineStart()
        if (start == cursor) return
        pushUndo(); killRing = buf.substring(start, cursor); buf.delete(start, cursor); cursor = start; changed()
    }

    private fun killPrevWord() {
        val start = prevWord()
        if (start == cursor) return
        pushUndo(); killRing = buf.substring(start, cursor); buf.delete(start, cursor); cursor = start; changed()
    }

    // ---- undo ---------------------------------------------------------------

    private fun pushUndo() {
        undoStack.addLast(buf.toString() to cursor)
        while (undoStack.size > 100) undoStack.removeFirst()
    }

    private fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        buf.setLength(0); buf.append(prev.first); cursor = prev.second.coerceIn(0, buf.length); changed()
    }

    private fun changed() { onChange?.invoke() }
}
