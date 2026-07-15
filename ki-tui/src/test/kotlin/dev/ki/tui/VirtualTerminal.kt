package dev.ki.tui

// Test harness ported in spirit from pi packages/tui/test/virtual-terminal.ts.
// pi uses @xterm/headless; here we implement a small VT-spec interpreter covering
// exactly the sequences ki's renderer emits (CR, LF+scroll, CSI cursor up/down,
// CSI 2K, CSI 2J/H/3J, SGR italic, private-mode set/reset, OSC) into a character
// grid, so tests can assert on the rendered viewport rather than raw bytes. It is
// authored to the terminal spec, NOT to ki's output, so the assertions stay honest.

/** In-memory VT-spec screen used to test the differential renderer. */
open class VirtualTerminal(cols: Int = 80, rows: Int = 24) : Terminal {
    private var nCols = cols
    private var nRows = rows
    private var grid = Array(nRows) { CharArray(nCols) { ' ' } }
    private var italicGrid = Array(nRows) { BooleanArray(nCols) }
    private var row = 0
    private var col = 0
    private var italic = false
    private var resizeHandler: (() -> Unit)? = null

    override fun start(onInput: (String) -> Unit, onResize: () -> Unit) { resizeHandler = onResize }
    override fun stop() {}
    override val columns: Int get() = nCols
    override val rows: Int get() = nRows
    // Renderer builds its own ANSI and only calls write(); these are unused.
    override fun moveBy(lines: Int) {}
    override fun hideCursor() {}
    override fun showCursor() {}
    override fun clearLine() {}
    override fun clearScreen() {}
    override fun setTitle(title: String) {}

    fun resize(newCols: Int, newRows: Int) {
        nCols = newCols; nRows = newRows
        grid = Array(nRows) { CharArray(nCols) { ' ' } }
        italicGrid = Array(nRows) { BooleanArray(nCols) }
        row = 0; col = 0
        resizeHandler?.invoke()
    }

    /** Each screen row as a string, trailing spaces trimmed (empty row -> ""). */
    fun getViewport(): List<String> = (0 until nRows).map { r ->
        String(grid[r]).trimEnd()
    }

    fun cellItalic(r: Int, c: Int): Boolean = italicGrid.getOrNull(r)?.getOrNull(c) ?: false

    override fun write(data: String) {
        var i = 0
        while (i < data.length) {
            val ch = data[i]
            when (ch.code) {
                27 -> i += consumeEscape(data, i) // ESC
                13 -> { col = 0; i++ }             // CR
                10 -> { lineFeed(); i++ }          // LF
                7 -> i++                            // stray BEL
                else -> { putChar(data.codePointAt(i)); i += Character.charCount(data.codePointAt(i)) }
            }
        }
    }

    private fun putChar(cp: Int) {
        val w = Width.charWidth(cp)
        if (w == 0) return
        if (col in 0 until nCols) {
            grid[row][col] = if (Character.isBmpCodePoint(cp)) cp.toChar() else '?'
            italicGrid[row][col] = italic
        }
        col += w
    }

    private fun lineFeed() {
        row++
        if (row >= nRows) { scrollUp(); row = nRows - 1 }
    }

    private fun scrollUp() {
        for (r in 0 until nRows - 1) { grid[r] = grid[r + 1]; italicGrid[r] = italicGrid[r + 1] }
        grid[nRows - 1] = CharArray(nCols) { ' ' }
        italicGrid[nRows - 1] = BooleanArray(nCols)
    }

    /** Consume one escape sequence starting at [start]; return its length. */
    private fun consumeEscape(s: String, start: Int): Int {
        var j = start + 1
        if (j >= s.length) return 1
        return when (s[j]) {
            '[' -> {
                j++
                val paramsStart = j
                while (j < s.length && s[j].code !in 0x40..0x7E) j++
                if (j >= s.length) return j - start
                val finalByte = s[j]
                val params = s.substring(paramsStart, j)
                handleCsi(finalByte, params)
                j - start + 1
            }
            ']' -> { // OSC: until BEL or ST
                j++
                while (j < s.length) {
                    if (s[j].code == 7) { j++; break }
                    if (s[j].code == 27 && j + 1 < s.length && s[j + 1] == '\\') { j += 2; break }
                    j++
                }
                j - start
            }
            else -> 2
        }
    }

    private fun handleCsi(finalByte: Char, params: String) {
        if (params.startsWith("?")) return // private mode (h/l) — ignore
        val nums = params.split(";").map { it.toIntOrNull() }
        val n = nums.firstOrNull() ?: null
        when (finalByte) {
            'A' -> row = maxOf(0, row - (n ?: 1))
            'B' -> row = minOf(nRows - 1, row + (n ?: 1))
            'H' -> { row = (nums.getOrNull(0) ?: 1) - 1; col = (nums.getOrNull(1) ?: 1) - 1 }
            'J' -> if ((n ?: 0) == 2) clearAll() // 3 (scrollback) is a no-op here
            'K' -> if ((n ?: 0) == 2) clearRow(row)
            'm' -> applySgr(nums)
            else -> {}
        }
    }

    private fun applySgr(nums: List<Int?>) {
        val codes = if (nums.isEmpty() || nums.all { it == null }) listOf(0) else nums
        for (c in codes) when (c) {
            0, null -> italic = false
            3 -> italic = true
            23 -> italic = false
            else -> {}
        }
    }

    private fun clearRow(r: Int) {
        if (r in 0 until nRows) { grid[r] = CharArray(nCols) { ' ' }; italicGrid[r] = BooleanArray(nCols) }
    }

    private fun clearAll() {
        grid = Array(nRows) { CharArray(nCols) { ' ' } }
        italicGrid = Array(nRows) { BooleanArray(nCols) }
    }
}

/** Test component that renders a mutable list of lines verbatim. */
class LinesComponent(@Volatile var lines: List<String> = emptyList()) : Component {
    override fun render(width: Int): List<String> = lines
}
