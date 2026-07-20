package dev.ki.tui

// Ported from pi packages/tui/test/keys.test.ts — the "Legacy key matching" and
// "Legacy key parsing" describe blocks, adapted from pi's string keyIds to ki's
// [Key] enum. Deferred (skipped): Kitty keyboard protocol, xterm modifyOtherKeys,
// Cyrillic base-layout matching, rxvt/alt-modifier and function-key ids that ki's
// minimal Key set does not model.

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeysTest {
    private val esc = 27.toChar().toString()

    // ---- Legacy key matching (ported) --------------------------------------

    @Test fun `should match legacy Ctrl+c`() { // Ctrl+c sends ASCII 3 (ETX)
        assertTrue(Keys.matchesKey(3.toChar().toString(), Key.CTRL_C))
    }

    @Test fun `should match legacy Ctrl+d`() { // Ctrl+d sends ASCII 4 (EOT)
        assertTrue(Keys.matchesKey(4.toChar().toString(), Key.CTRL_D))
    }

    @Test fun `should match legacy Ctrl+o`() { // Ctrl+o sends ASCII 15 (SI) — pi-style expand toggle
        assertTrue(Keys.matchesKey(15.toChar().toString(), Key.CTRL_O))
    }

    @Test fun `should match escape key`() {
        assertTrue(Keys.matchesKey(esc, Key.ESCAPE))
    }

    @Test fun `should match legacy linefeed as enter`() {
        assertTrue(Keys.matchesKey("\n", Key.ENTER))
        assertEquals(Key.ENTER, Keys.parse("\n"))
    }

    @Test fun `should treat raw 0x08 and 0x7f as backspace`() {
        assertTrue(Keys.matchesKey(0x7F.toChar().toString(), Key.BACKSPACE))
        assertEquals(Key.BACKSPACE, Keys.parse(0x7F.toChar().toString()))
        assertTrue(Keys.matchesKey(0x08.toChar().toString(), Key.BACKSPACE))
        assertEquals(Key.BACKSPACE, Keys.parse(0x08.toChar().toString()))
    }

    @Test fun `should match arrow keys`() {
        assertTrue(Keys.matchesKey("$esc[A", Key.UP))
        assertTrue(Keys.matchesKey("$esc[B", Key.DOWN))
        assertTrue(Keys.matchesKey("$esc[C", Key.RIGHT))
        assertTrue(Keys.matchesKey("$esc[D", Key.LEFT))
    }

    @Test fun `should match SS3 arrows and home end`() {
        assertTrue(Keys.matchesKey("${esc}OA", Key.UP))
        assertTrue(Keys.matchesKey("${esc}OB", Key.DOWN))
        assertTrue(Keys.matchesKey("${esc}OC", Key.RIGHT))
        assertTrue(Keys.matchesKey("${esc}OD", Key.LEFT))
        assertTrue(Keys.matchesKey("${esc}OH", Key.HOME))
        assertTrue(Keys.matchesKey("${esc}OF", Key.END))
    }

    // ---- Legacy key parsing (ported) ---------------------------------------

    @Test fun `should parse legacy Ctrl+letter`() {
        assertEquals(Key.CTRL_C, Keys.parse(3.toChar().toString()))
        assertEquals(Key.CTRL_D, Keys.parse(4.toChar().toString()))
    }

    @Test fun `should parse special keys`() {
        assertEquals(Key.ESCAPE, Keys.parse(esc))
        assertEquals(Key.TAB, Keys.parse("\t"))
        assertEquals(Key.ENTER, Keys.parse("\r"))
        assertEquals(Key.ENTER, Keys.parse("\n"))
    }

    @Test fun `should parse arrow keys`() {
        assertEquals(Key.UP, Keys.parse("$esc[A"))
        assertEquals(Key.DOWN, Keys.parse("$esc[B"))
        assertEquals(Key.RIGHT, Keys.parse("$esc[C"))
        assertEquals(Key.LEFT, Keys.parse("$esc[D"))
    }

    @Test fun `should parse SS3 arrows and home end`() {
        assertEquals(Key.UP, Keys.parse("${esc}OA"))
        assertEquals(Key.DOWN, Keys.parse("${esc}OB"))
        assertEquals(Key.RIGHT, Keys.parse("${esc}OC"))
        assertEquals(Key.LEFT, Keys.parse("${esc}OD"))
        assertEquals(Key.HOME, Keys.parse("${esc}OH"))
        assertEquals(Key.END, Keys.parse("${esc}OF"))
    }
}
