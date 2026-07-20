package dev.ki.tui

/**
 * ANSI/VT escape-sequence constants and small SGR helpers used across the
 * framework. Kept dependency-free — just String building. ESC is written as
 * the Unicode escape  so the source stays plain ASCII.
 */
object Ansi {
    const val ESC = ""
    const val CSI = "["

    // Synchronized output (CSI 2026) — atomic, flicker-free frames.
    const val SYNC_BEGIN = "[?2026h"
    const val SYNC_END = "[?2026l"

    const val CLEAR_LINE = "[2K" // Clear entire current line
    const val CLEAR_TO_END = "[0J" // Clear from cursor to end of screen
    const val CLEAR_SCREEN = "[2J[H[3J" // Clear screen, home, clear scrollback
    const val HIDE_CURSOR = "[?25l"
    const val SHOW_CURSOR = "[?25h"
    const val BRACKETED_PASTE_ON = "[?2004h"
    const val BRACKETED_PASTE_OFF = "[?2004l"

    const val RESET = "[0m"

    /** Per-line reset the renderer appends so styles/hyperlinks never bleed across
     *  lines: SGR reset + OSC-8 hyperlink reset (BEL-terminated). Mirrors pi's
     *  SEGMENT_RESET. */
    val SEGMENT_RESET: String = RESET + ESC + "]8;;" + 7.toChar()

    fun moveUp(n: Int): String = if (n > 0) "$CSI${n}A" else ""
    fun moveDown(n: Int): String = if (n > 0) "$CSI${n}B" else ""

    fun sgr(vararg codes: Int): String = codes.joinToString(";", prefix = CSI, postfix = "m")

    fun bold(s: String): String = CSI + "1m" + s + RESET
    fun dim(s: String): String = CSI + "2m" + s + RESET
    fun invert(s: String): String = CSI + "7m" + s + RESET

    /** Bold [s] using SGR 22 (bold-off) rather than a full reset, so it nests inside a
     *  background stripe without clearing the background. */
    fun boldIn(s: String): String = CSI + "1m" + s + CSI + "22m"

    /** Wrap [s] in a 24-bit (truecolor) background, resetting only the background (`49m`)
     *  so surrounding styles survive. Mirrors pi's `bgAnsi` truecolor path; the 256-color
     *  fallback for non-truecolor terminals is deferred (ki has no capability detection yet). */
    fun bgRgb(r: Int, g: Int, b: Int, s: String): String = CSI + "48;2;$r;$g;${b}m" + s + CSI + "49m"

    /** Set terminal window title (OSC 2). */
    fun title(t: String): String = "$ESC]2;$t"
}
