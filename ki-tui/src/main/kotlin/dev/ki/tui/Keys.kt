package dev.ki.tui

/**
 * Minimal keyboard-input decoding. Terminal input in raw mode arrives as bytes:
 * printable UTF-8 for ordinary keys, and escape sequences for control/navigation
 * keys. This maps the common sequences to stable [Key] ids and offers
 * [matchesKey] for keybinding checks.
 *
 * Scope note: the full Kitty keyboard protocol (key-release/repeat, exhaustive
 * modifier encoding) is deferred (see PLAN M2 backlog). This covers the standard
 * xterm/VT sequences a coding-agent prompt needs. All control/escape bytes are
 * built from code points so the source stays plain ASCII.
 */
enum class Key {
    ENTER, SHIFT_ENTER, TAB, SHIFT_TAB, BACKSPACE, DELETE, ESCAPE,
    LEFT, RIGHT, UP, DOWN, HOME, END, PAGE_UP, PAGE_DOWN,
    WORD_LEFT, WORD_RIGHT,
    CTRL_A, CTRL_B, CTRL_C, CTRL_D, CTRL_E, CTRL_F, CTRL_K, CTRL_L,
    CTRL_N, CTRL_O, CTRL_P, CTRL_Q, CTRL_U, CTRL_W, CTRL_Y,
    PASTE_START, PASTE_END,
    PRINTABLE, UNKNOWN,
}

object Keys {
    private const val ESC_CP = 27
    private val ESC = ESC_CP.toChar().toString()

    /** Control byte for a letter, e.g. ctrl('a') == 0x01. */
    private fun ctrl(c: Char): String = (c.uppercaseChar().code - 64).toChar().toString()

    /** CSI sequence: ESC '[' + body. */
    private fun csi(body: String): String = ESC + "[" + body

    /** SS3 sequence: ESC 'O' + body. */
    private fun ss3(body: String): String = ESC + "O" + body

    val PASTE_START: String = csi("200~")
    val PASTE_END: String = csi("201~")

    private val SEQUENCE: Map<String, Key> = buildMap {
        put("\r", Key.ENTER)
        put("\n", Key.ENTER)
        put("\t", Key.TAB)
        put(csi("Z"), Key.SHIFT_TAB)
        put("", Key.BACKSPACE)
        put(ctrl('h'), Key.BACKSPACE) // 0x08
        put(csi("3~"), Key.DELETE)
        put(csi("13;2u"), Key.SHIFT_ENTER) // Apple Terminal / xterm Shift+Enter
        // Arrows (CSI and SS3 forms)
        put(csi("C"), Key.RIGHT); put(ss3("C"), Key.RIGHT)
        put(csi("D"), Key.LEFT); put(ss3("D"), Key.LEFT)
        put(csi("A"), Key.UP); put(ss3("A"), Key.UP)
        put(csi("B"), Key.DOWN); put(ss3("B"), Key.DOWN)
        // Word navigation: Ctrl+Arrow, Alt+Arrow, and Alt+b / Alt+f
        put(csi("1;5C"), Key.WORD_RIGHT); put(csi("1;3C"), Key.WORD_RIGHT); put(ESC + "f", Key.WORD_RIGHT)
        put(csi("1;5D"), Key.WORD_LEFT); put(csi("1;3D"), Key.WORD_LEFT); put(ESC + "b", Key.WORD_LEFT)
        // Home / End
        put(csi("H"), Key.HOME); put(ss3("H"), Key.HOME); put(csi("1~"), Key.HOME)
        put(csi("F"), Key.END); put(ss3("F"), Key.END); put(csi("4~"), Key.END)
        put(csi("5~"), Key.PAGE_UP); put(csi("6~"), Key.PAGE_DOWN)
        put(ESC, Key.ESCAPE)
        // Control chars
        put(ctrl('a'), Key.CTRL_A); put(ctrl('b'), Key.CTRL_B); put(ctrl('c'), Key.CTRL_C)
        put(ctrl('d'), Key.CTRL_D); put(ctrl('e'), Key.CTRL_E); put(ctrl('f'), Key.CTRL_F)
        put(ctrl('k'), Key.CTRL_K); put(ctrl('l'), Key.CTRL_L); put(ctrl('n'), Key.CTRL_N)
        put(ctrl('o'), Key.CTRL_O)
        put(ctrl('p'), Key.CTRL_P); put(ctrl('q'), Key.CTRL_Q); put(ctrl('u'), Key.CTRL_U)
        put(ctrl('w'), Key.CTRL_W); put(ctrl('y'), Key.CTRL_Y)
    }

    /** Classify a single input sequence. */
    fun parse(data: String): Key {
        SEQUENCE[data]?.let { return it }
        if (data.startsWith(PASTE_START)) return Key.PASTE_START
        if (data == PASTE_END) return Key.PASTE_END
        if (isPrintable(data)) return Key.PRINTABLE
        return Key.UNKNOWN
    }

    fun matchesKey(data: String, key: Key): Boolean = parse(data) == key

    /**
     * Split a raw input chunk into individual key sequences. Terminals batch fast
     * typing and pastes into one read (e.g. "hello\r" or an escape sequence glued
     * to text); dispatching the whole blob as one key loses the Enter. This yields
     * one segment per: bracketed-paste block, escape sequence, control byte, or run
     * of printable text.
     *
     * (Minimal StdinBuffer: it does not coalesce a single sequence split across
     * reads — deferred, see PLAN M2 backlog.)
     */
    fun splitInput(data: String): List<String> {
        val segs = ArrayList<String>()
        var i = 0
        while (i < data.length) {
            if (data.startsWith(PASTE_START, i)) {
                val end = data.indexOf(PASTE_END, i)
                if (end >= 0) { segs.add(data.substring(i, end + PASTE_END.length)); i = end + PASTE_END.length }
                else { segs.add(data.substring(i)); i = data.length }
                continue
            }
            val cp = data[i].code
            when {
                cp == ESC_CP -> { val seq = readEscape(data, i); segs.add(seq); i += seq.length }
                cp < 0x20 || cp == 0x7F -> { segs.add(data[i].toString()); i++ }
                else -> {
                    // One segment per printable code point (pi StdinBuffer contract),
                    // so per-key matching works and surrogate pairs stay whole.
                    val ncp = data.codePointAt(i)
                    val cc = Character.charCount(ncp)
                    segs.add(data.substring(i, i + cc)); i += cc
                }
            }
        }
        return segs
    }

    /** Read one escape sequence starting at [start] (assumes data[start] == ESC). */
    private fun readEscape(data: String, start: Int): String {
        var i = start + 1
        if (i >= data.length) return data.substring(start)
        return when (data[i]) {
            '[' -> { // CSI: params until a final byte in 0x40..0x7E
                i++
                while (i < data.length && data[i].code !in 0x40..0x7E) i++
                if (i < data.length) i++
                data.substring(start, i)
            }
            'O' -> data.substring(start, minOf(i + 2, data.length)) // SS3: ESC O <char>
            else -> data.substring(start, i + 1) // ESC + single byte (Alt-key, bare ESC)
        }
    }

    /** True if [data] is ordinary printable text (no control/escape bytes). */
    fun isPrintable(data: String): Boolean {
        if (data.isEmpty()) return false
        var i = 0
        while (i < data.length) {
            val cp = data.codePointAt(i)
            if (cp == ESC_CP) return false
            if (cp < 0x20 && cp != 0x09) return false // reject controls (tab handled elsewhere)
            if (cp == 0x7F) return false
            i += Character.charCount(cp)
        }
        return true
    }
}
