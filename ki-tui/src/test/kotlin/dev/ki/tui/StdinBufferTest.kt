package dev.ki.tui

// Ported from pi packages/tui/test/stdin-buffer.test.ts. ki has no full StdinBuffer
// (deferred, PLAN M2 backlog); the synchronous splitting contract lives in
// [Keys.splitInput], so the buffering-free cases are ported against it: Regular
// Characters, Complete Escape Sequences, and Mixed Content. Deferred (skipped):
// partial-sequence buffering across chunks, timeout flush, Kitty protocol, mouse
// events, and the separate bracketed-paste event API (ki keeps a paste block as a
// single segment instead).

import kotlin.test.Test
import kotlin.test.assertEquals

class StdinBufferTest {
    private val esc = 27.toChar().toString()

    // ---- Regular Characters ------------------------------------------------

    @Test fun `should pass through regular characters immediately`() {
        assertEquals(listOf("a"), Keys.splitInput("a"))
    }

    @Test fun `should pass through multiple regular characters`() {
        assertEquals(listOf("a", "b", "c"), Keys.splitInput("abc"))
    }

    @Test fun `should handle unicode characters`() {
        assertEquals(listOf("h", "e", "l", "l", "o", " ", "世", "界"), Keys.splitInput("hello 世界"))
    }

    // ---- Complete Escape Sequences -----------------------------------------

    @Test fun `should pass through complete mouse SGR sequences`() {
        val mouseSeq = "$esc[<35;20;5m"
        assertEquals(listOf(mouseSeq), Keys.splitInput(mouseSeq))
    }

    @Test fun `should pass through complete arrow key sequences`() {
        assertEquals(listOf("$esc[A"), Keys.splitInput("$esc[A"))
    }

    @Test fun `should pass through complete function key sequences`() {
        assertEquals(listOf("$esc[11~"), Keys.splitInput("$esc[11~"))
    }

    @Test fun `should pass through meta key sequences`() {
        assertEquals(listOf("${esc}a"), Keys.splitInput("${esc}a"))
    }

    @Test fun `should pass through SS3 sequences`() {
        assertEquals(listOf("${esc}OA"), Keys.splitInput("${esc}OA"))
    }

    // ---- Mixed Content -----------------------------------------------------

    @Test fun `should handle characters followed by escape sequence`() {
        assertEquals(listOf("a", "b", "c", "$esc[A"), Keys.splitInput("abc$esc[A"))
    }

    @Test fun `should handle escape sequence followed by characters`() {
        assertEquals(listOf("$esc[A", "a", "b", "c"), Keys.splitInput("$esc[Aabc"))
    }

    @Test fun `should handle multiple complete sequences`() {
        assertEquals(listOf("$esc[A", "$esc[B", "$esc[C"), Keys.splitInput("$esc[A$esc[B$esc[C"))
    }

    // ---- Bracketed paste (ki behaviour: kept as one segment) ---------------

    @Test fun `keeps a bracketed paste block whole`() {
        val paste = Keys.PASTE_START + "line1\nline2" + Keys.PASTE_END
        assertEquals(listOf(paste), Keys.splitInput(paste))
    }
}
