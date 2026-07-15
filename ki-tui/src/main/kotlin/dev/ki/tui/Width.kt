package dev.ki.tui

/**
 * Terminal display-width utilities. Terminal columns are counted in *cells*, not
 * Unicode code points: CJK ideographs and most emoji occupy two cells, combining
 * marks and control characters occupy zero, and a tab occupies three (matching
 * pi). ANSI escape sequences are invisible and must not count toward width. All
 * rendering length math goes through here.
 *
 * [truncateToWidth] is a faithful port of pi's `truncateToWidth` (packages/tui
 * `src/utils.ts`): it keeps a contiguous visible prefix, brackets the kept text
 * and the ellipsis with SGR resets so styling never bleeds, and can pad to width.
 * Grapheme clustering is approximated per-code-point (deferred: full clustering).
 */
object Width {
    private const val ESC = 27 // 0x1B
    private const val BEL = 7 // 0x07
    private const val TAB = 9 // 0x09
    private const val TAB_WIDTH = 3
    private const val RESET = "[0m"

    /** Display width of one Unicode code point in terminal cells (wcwidth-style). */
    fun charWidth(cp: Int): Int = when {
        cp == TAB -> TAB_WIDTH
        cp == 0 -> 0
        cp < 32 || cp in 0x7F..0x9F -> 0 // C0/C1 control
        isZeroWidth(cp) -> 0
        isWide(cp) -> 2
        else -> 1
    }

    private fun isZeroWidth(cp: Int): Boolean =
        cp in 0x0300..0x036F ||   // combining diacritical marks
        cp in 0x1AB0..0x1AFF ||
        cp in 0x1DC0..0x1DFF ||
        cp in 0x20D0..0x20FF ||   // combining marks for symbols
        cp in 0xFE00..0xFE0F ||   // variation selectors
        cp in 0xFE20..0xFE2F ||
        cp == 0x200B ||           // zero-width space
        cp in 0x200C..0x200F      // ZWNJ/ZWJ/marks

    private fun isWide(cp: Int): Boolean =
        cp in 0x1100..0x115F ||   // Hangul Jamo
        cp == 0x2329 || cp == 0x232A ||
        cp in 0x2E80..0x303E ||   // CJK radicals, Kangxi
        cp in 0x3041..0x33FF ||   // Hiragana .. CJK compat
        cp in 0x3400..0x4DBF ||   // CJK ext A
        cp in 0x4E00..0x9FFF ||   // CJK unified
        cp in 0xA000..0xA4CF ||   // Yi
        cp in 0xAC00..0xD7A3 ||   // Hangul syllables
        cp in 0xF900..0xFAFF ||   // CJK compat ideographs
        cp in 0xFE30..0xFE4F ||   // CJK compat forms
        cp in 0xFF00..0xFF60 ||   // fullwidth forms
        cp in 0xFFE0..0xFFE6 ||
        cp in 0x1F1E6..0x1F1FF || // regional indicators (rendered wide)
        cp in 0x1F300..0x1FAFF || // emoji & pictographs
        cp in 0x1F900..0x1F9FF ||
        cp in 0x20000..0x3FFFD    // CJK ext B+

    private fun isPrintableAscii(s: String): Boolean {
        for (c in s) if (c.code < 0x20 || c.code > 0x7E) return false
        return true
    }

    /**
     * Length in chars of an ANSI escape sequence starting at [i], or 0 if none.
     * Covers CSI (`ESC [ … final`), OSC/APC/DCS/PM/SOS (`ESC ]/_/P/^/X … BEL/ST`),
     * and a lone `ESC` + single byte.
     */
    fun extractAnsi(s: String, i: Int): Int {
        if (i >= s.length || s[i].code != ESC) return 0
        var j = i + 1
        if (j >= s.length) return 1
        return when (s[j]) {
            '[' -> {
                j++
                while (j < s.length && s[j].code !in 0x40..0x7E) j++
                if (j < s.length) j++
                j - i
            }
            ']', '_', 'P', '^', 'X' -> {
                j++
                while (j < s.length) {
                    if (s[j].code == BEL) { j++; break }
                    if (s[j].code == ESC && j + 1 < s.length && s[j + 1] == '\\') { j += 2; break }
                    j++
                }
                j - i
            }
            else -> 2 // ESC + single byte
        }
    }

    /** Strip ANSI escape sequences. Everything else (including tabs) passes through. */
    fun stripAnsi(s: String): String {
        if (s.indexOf(ESC.toChar()) < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val a = extractAnsi(s, i)
            if (a > 0) { i += a; continue }
            out.append(s[i]); i++
        }
        return out.toString()
    }

    /** Visible display width of a string in terminal cells (ANSI-stripped). */
    fun visibleWidth(s: String): Int {
        var w = 0
        var i = 0
        while (i < s.length) {
            val a = extractAnsi(s, i)
            if (a > 0) { i += a; continue }
            val cp = s.codePointAt(i)
            w += charWidth(cp)
            i += Character.charCount(cp)
        }
        return w
    }

    /** Port of pi's `truncateFragmentToWidth`: keep as much of [text] as fits. */
    private fun truncateFragment(text: String, maxWidth: Int): Pair<String, Int> {
        if (maxWidth <= 0 || text.isEmpty()) return "" to 0
        if (isPrintableAscii(text)) {
            val clipped = text.take(maxWidth)
            return clipped to clipped.length
        }
        val result = StringBuilder()
        var width = 0
        var i = 0
        val pendingAnsi = StringBuilder()
        while (i < text.length) {
            val a = extractAnsi(text, i)
            if (a > 0) { pendingAnsi.append(text, i, i + a); i += a; continue }
            val cp = text.codePointAt(i)
            val cc = Character.charCount(cp)
            val w = charWidth(cp)
            if (width + w > maxWidth) break
            if (pendingAnsi.isNotEmpty()) { result.append(pendingAnsi); pendingAnsi.setLength(0) }
            result.append(text, i, i + cc)
            width += w
            i += cc
        }
        return result.toString() to width
    }

    private fun finalize(prefix: String, prefixWidth: Int, ellipsis: String, ellipsisWidth: Int, maxWidth: Int, pad: Boolean): String {
        val vw = prefixWidth + ellipsisWidth
        val result = if (ellipsis.isNotEmpty()) "$prefix$RESET$ellipsis$RESET" else "$prefix$RESET"
        return if (pad) result + " ".repeat(maxOf(0, maxWidth - vw)) else result
    }

    /**
     * Truncate to at most [maxWidth] display cells, appending [ellipsis] (bracketed
     * by SGR resets) when the input is wider; optionally [pad] to exactly [maxWidth].
     * Faithful port of pi's `truncateToWidth`.
     */
    fun truncateToWidth(text: String, maxWidth: Int, ellipsis: String = "...", pad: Boolean = false): String {
        if (maxWidth <= 0) return ""
        if (text.isEmpty()) return if (pad) " ".repeat(maxWidth) else ""

        val ellipsisWidth = visibleWidth(ellipsis)
        if (ellipsisWidth >= maxWidth) {
            val textWidth = visibleWidth(text)
            if (textWidth <= maxWidth) return if (pad) text + " ".repeat(maxWidth - textWidth) else text
            val (clipText, clipWidth) = truncateFragment(ellipsis, maxWidth)
            if (clipWidth == 0) return if (pad) " ".repeat(maxWidth) else ""
            return finalize("", 0, clipText, clipWidth, maxWidth, pad)
        }

        if (isPrintableAscii(text)) {
            if (text.length <= maxWidth) return if (pad) text + " ".repeat(maxWidth - text.length) else text
            val targetWidth = maxWidth - ellipsisWidth
            return finalize(text.substring(0, targetWidth), targetWidth, ellipsis, ellipsisWidth, maxWidth, pad)
        }

        val targetWidth = maxWidth - ellipsisWidth
        val result = StringBuilder()
        val pendingAnsi = StringBuilder()
        var visibleSoFar = 0
        var keptWidth = 0
        var keepContiguous = true
        var overflowed = false
        var i = 0
        while (i < text.length) {
            val a = extractAnsi(text, i)
            if (a > 0) { pendingAnsi.append(text, i, i + a); i += a; continue }
            val cp = text.codePointAt(i)
            val cc = Character.charCount(cp)
            val w = charWidth(cp)
            if (keepContiguous && keptWidth + w <= targetWidth) {
                if (pendingAnsi.isNotEmpty()) { result.append(pendingAnsi); pendingAnsi.setLength(0) }
                result.append(text, i, i + cc)
                keptWidth += w
            } else {
                keepContiguous = false
                pendingAnsi.setLength(0)
            }
            visibleSoFar += w
            if (visibleSoFar > maxWidth) { overflowed = true; break }
            i += cc
        }
        val exhaustedInput = i >= text.length
        if (!overflowed && exhaustedInput) {
            return if (pad) text + " ".repeat(maxOf(0, maxWidth - visibleSoFar)) else text
        }
        return finalize(result.toString(), keptWidth, ellipsis, ellipsisWidth, maxWidth, pad)
    }

    /**
     * Word-wrap [text] to lines no wider than [width] cells. Wraps on spaces;
     * words longer than the width are hard-split. Existing newlines force breaks.
     * Note: ANSI styling is not carried across wrap boundaries (deferred — the
     * agent transcript is largely plain text). Never emits a line wider than [width].
     */
    fun wrapText(text: String, width: Int): List<String> {
        if (width <= 0) return listOf(text)
        val result = ArrayList<String>()
        for (rawLine in text.split("\n")) {
            if (visibleWidth(rawLine) <= width) {
                result.add(rawLine)
                continue
            }
            var current = StringBuilder()
            var currentWidth = 0
            for (word in rawLine.split(" ")) {
                val wWidth = visibleWidth(word)
                when {
                    currentWidth == 0 && wWidth <= width -> {
                        current.append(word); currentWidth = wWidth
                    }
                    currentWidth + 1 + wWidth <= width -> {
                        current.append(' ').append(word); currentWidth += 1 + wWidth
                    }
                    else -> {
                        if (currentWidth > 0) { result.add(current.toString()); current = StringBuilder(); currentWidth = 0 }
                        if (wWidth <= width) {
                            current.append(word); currentWidth = wWidth
                        } else {
                            for (piece in hardSplit(word, width)) {
                                if (visibleWidth(piece) == width) result.add(piece)
                                else { current.append(piece); currentWidth = visibleWidth(piece) }
                            }
                        }
                    }
                }
            }
            if (currentWidth > 0 || current.isNotEmpty()) result.add(current.toString())
        }
        return result
    }

    /** Split a too-long word into pieces each at most [width] cells wide. */
    private fun hardSplit(word: String, width: Int): List<String> {
        val pieces = ArrayList<String>()
        val out = StringBuilder()
        var w = 0
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            val cw = charWidth(cp)
            if (w + cw > width) { pieces.add(out.toString()); out.setLength(0); w = 0 }
            out.appendCodePoint(cp)
            w += cw
            i += Character.charCount(cp)
        }
        if (out.isNotEmpty()) pieces.add(out.toString())
        return pieces
    }

    /** Pad [s] on the right with spaces to exactly [width] cells (truncating if wider). */
    fun padTo(s: String, width: Int): String {
        val vw = visibleWidth(s)
        return when {
            vw == width -> s
            vw < width -> s + " ".repeat(width - vw)
            else -> truncateToWidth(s, width, pad = true)
        }
    }
}
