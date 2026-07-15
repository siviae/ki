package dev.ki.tui

// Ported from pi packages/tui/test/editor.test.ts — the "Unicode text editing
// behavior" and "Kill ring" blocks, plus the whitespace Ctrl+W cases from
// "deletes words correctly", adapted to ki's simpler Editor API (flat cursor
// offset, `.text` instead of getText()/getCursor()).
//
// Deferred (skipped): prompt history, Intl-segmenter word boundaries (punctuation
// / CJK-aware nav, getCursor assertions), grapheme-cluster deletion of multi-code-
// point emoji, sticky column, autocomplete, char-jump, viewport wrapping,
// backslash+Enter, Alt+Y kill-ring cycling and Ctrl+W accumulation, and the Undo
// block (pi's word-coalescing undo + Kitty CSI-u undo key differ from ki's per-op
// undo). Tests marked "ki-specific" exercise ki behavior with no pi equivalent.

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorTest {
    private val esc = 27.toChar().toString()
    private val del = 0x7F.toChar().toString()
    private fun ctrl(c: Char) = (c.uppercaseChar().code - 64).toChar().toString()

    // ---- Unicode text editing behavior (ported) ----------------------------

    @Test fun `inserts mixed ASCII, umlauts, and emojis as literal text`() {
        val e = Editor()
        for (c in listOf("H", "e", "l", "l", "o", " ", "ä", "ö", "ü", " ", "😀")) e.handleInput(c)
        assertEquals("Hello äöü 😀", e.text)
    }

    @Test fun `deletes single-code-unit unicode characters with Backspace`() {
        val e = Editor()
        for (c in listOf("ä", "ö", "ü")) e.handleInput(c)
        e.handleInput(del)
        assertEquals("äö", e.text)
    }

    @Test fun `deletes a single-codepoint emoji with one Backspace`() {
        val e = Editor()
        e.handleInput("😀"); e.handleInput("👍")
        e.handleInput(del)
        assertEquals("😀", e.text)
    }

    @Test fun `inserts at correct position after cursor movement over umlauts`() {
        val e = Editor()
        for (c in listOf("ä", "ö", "ü")) e.handleInput(c)
        e.handleInput("$esc[D"); e.handleInput("$esc[D")
        e.handleInput("x")
        assertEquals("äxöü", e.text)
    }

    @Test fun `moves cursor across single-codepoint emojis with one arrow`() {
        val e = Editor()
        for (c in listOf("😀", "👍", "🎉")) e.handleInput(c)
        e.handleInput("$esc[D"); e.handleInput("$esc[D")
        e.handleInput("x")
        assertEquals("😀x👍🎉", e.text)
    }

    // pi's test 484 ("preserves umlauts across line breaks") sends a bare "\n" and
    // expects a newline — but that relies on the Kitty keyboard protocol being
    // active (LF -> shift+enter). ki defers Kitty, so a bare LF is Enter (submit),
    // matching pi's kitty-inactive behaviour; a newline comes from Shift+Enter.
    @Test fun `shift+enter inserts a newline into the buffer`() {
        val e = Editor()
        val shiftEnter = "${27.toChar()}[13;2u"
        for (c in listOf("ä", "ö", "ü", shiftEnter, "Ä", "Ö", "Ü")) e.handleInput(c)
        assertEquals("äöü\nÄÖÜ", e.text)
    }

    @Test fun `replaces the entire document via setText`() {
        val e = Editor()
        e.setText("Hällö Wörld! 😀 äöüÄÖÜß")
        assertEquals("Hällö Wörld! 😀 äöüÄÖÜß", e.text)
    }

    @Test fun `Ctrl+A moves to start and inserts at the beginning`() {
        val e = Editor()
        e.handleInput("a"); e.handleInput("b")
        e.handleInput(ctrl('a'))
        e.handleInput("x")
        assertEquals("xab", e.text)
    }

    @Test fun `Ctrl+W deletes trailing-whitespace word run`() {
        val e = Editor()
        e.setText("foo bar   ")
        e.handleInput(ctrl('w'))
        assertEquals("foo ", e.text)
    }

    @Test fun `Ctrl+W deletes across the current line only`() {
        val e = Editor()
        e.setText("line one\nline two")
        e.handleInput(ctrl('w'))
        assertEquals("line one\nline ", e.text)
    }

    @Test fun `Ctrl+W treats an emoji run as a word`() {
        val e = Editor()
        e.setText("foo 😀😀 bar")
        e.handleInput(ctrl('w'))
        assertEquals("foo 😀😀 ", e.text)
        e.handleInput(ctrl('w'))
        assertEquals("foo ", e.text)
    }

    // ---- Kill ring (ported; ki has a single-slot ring) ---------------------

    @Test fun `Ctrl+W saves deleted text and Ctrl+Y yanks it`() {
        val e = Editor()
        e.setText("foo bar baz")
        e.handleInput(ctrl('w'))
        assertEquals("foo bar ", e.text)
        e.handleInput(ctrl('a'))
        e.handleInput(ctrl('y'))
        assertEquals("bazfoo bar ", e.text)
    }

    @Test fun `Ctrl+U saves deleted text to kill ring`() {
        val e = Editor()
        e.setText("hello world")
        e.handleInput(ctrl('a'))
        repeat(6) { e.handleInput("$esc[C") } // after "hello "
        e.handleInput(ctrl('u'))
        assertEquals("world", e.text)
        e.handleInput(ctrl('y'))
        assertEquals("hello world", e.text)
    }

    @Test fun `Ctrl+K saves deleted text to kill ring`() {
        val e = Editor()
        e.setText("hello world")
        e.handleInput(ctrl('a'))
        e.handleInput(ctrl('k'))
        assertEquals("", e.text)
        e.handleInput(ctrl('y'))
        assertEquals("hello world", e.text)
    }

    @Test fun `Ctrl+Y does nothing when kill ring is empty`() {
        val e = Editor()
        e.setText("test")
        e.handleInput(ctrl('y'))
        assertEquals("test", e.text)
    }

    // ---- ki-specific (no pi equivalent) ------------------------------------

    @Test fun `ki-specific carriage return submits trimmed non-blank text`() {
        val e = Editor()
        var submitted: String? = null
        e.onSubmit = { submitted = it }
        e.setText("  hello  ")
        e.handleInput("\r")
        assertEquals("hello", submitted)
    }

    @Test fun `ki-specific carriage return does not submit blank`() {
        val e = Editor()
        var submitted: String? = null
        e.onSubmit = { submitted = it }
        e.setText("   ")
        e.handleInput("\r")
        assertNull(submitted)
    }

    @Test fun `ki-specific undo reverts last edit`() {
        val e = Editor()
        e.setText("foo")
        e.handleInput("!")
        e.handleInput(26.toChar().toString()) // Ctrl-Z
        assertEquals("foo", e.text)
    }

    @Test fun `ki-specific paste inserts body without markers`() {
        val e = Editor()
        e.handleInput(Keys.PASTE_START + "pasted text" + Keys.PASTE_END)
        assertEquals("pasted text", e.text)
    }

    @Test fun `ki-specific render never exceeds width and shows cursor`() {
        val e = Editor()
        e.setText("hello")
        val lines = e.render(20)
        assertTrue(lines.all { Width.visibleWidth(it) <= 20 }, "over-wide: $lines")
        assertTrue(lines.any { it.contains("hello") || it.contains("hell") })
    }

    @Test fun `ki-specific cursor on a wrap boundary renders exactly once`() {
        val e = Editor()
        e.setText("abcdef")
        repeat(3) { e.handleInput("$esc[D") } // cursor to offset 3 (chunk boundary at inner=3)
        val lines = e.render(5) // prompt width 2 -> inner 3 -> chunks "abc","def"
        val cursorCells = lines.sumOf { line -> "7m".toRegex().findAll(line).count() }
        assertEquals(1, cursorCells, "cursor rendered $cursorCells times: $lines")
    }
}
