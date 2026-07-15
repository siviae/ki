package dev.ki.tui

// Ported from pi packages/tui/test/truncate-to-width.test.ts (and the plain-width
// cases of tab-width.test.ts). Cases depending on deferred features are omitted:
// normalizeTerminalOutput (Thai/Lao AM normalization) and the sliceWithWidth /
// extractSegments helpers behind tab-width.test.ts.

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidthTest {
    private val esc = 27.toChar().toString()
    private val bel = 7.toChar().toString()
    private val reset = "$esc[0m"

    // ---- truncateToWidth (ported) ------------------------------------------

    @Test fun `keeps output within width for very large unicode input`() {
        val text = "🙂界".repeat(100_000)
        val truncated = Width.truncateToWidth(text, 40, "…")
        assertTrue(Width.visibleWidth(truncated) <= 40)
        assertTrue(truncated.endsWith("…$reset"))
    }

    @Test fun `preserves ANSI styling for kept text and resets before and after ellipsis`() {
        val text = "$esc[31m" + "hello ".repeat(1000) + "$esc[0m"
        val truncated = Width.truncateToWidth(text, 20, "…")
        assertTrue(Width.visibleWidth(truncated) <= 20)
        assertTrue(truncated.contains("$esc[31m"))
        assertTrue(truncated.endsWith("$esc[0m…$esc[0m"))
    }

    @Test fun `handles malformed ANSI escape prefixes without hanging`() {
        val text = "abc$esc" + "not-ansi " + "🙂".repeat(1000)
        val truncated = Width.truncateToWidth(text, 20, "…")
        assertTrue(Width.visibleWidth(truncated) <= 20)
    }

    @Test fun `clips wide ellipsis safely and brackets it with resets`() {
        assertEquals("", Width.truncateToWidth("abcdef", 1, "🙂"))
        assertEquals("$esc[0m🙂$esc[0m", Width.truncateToWidth("abcdef", 2, "🙂"))
        assertTrue(Width.visibleWidth(Width.truncateToWidth("abcdef", 2, "🙂")) <= 2)
    }

    @Test fun `returns the original text when it already fits even if ellipsis is too wide`() {
        assertEquals("a", Width.truncateToWidth("a", 2, "🙂"))
        assertEquals("界", Width.truncateToWidth("界", 2, "🙂"))
    }

    @Test fun `pads truncated output to requested width`() {
        val truncated = Width.truncateToWidth("🙂界🙂界🙂界", 8, "…", true)
        assertEquals(8, Width.visibleWidth(truncated))
    }

    @Test fun `adds a trailing reset when truncating without an ellipsis`() {
        val truncated = Width.truncateToWidth("$esc[31m" + "hello".repeat(100), 10, "")
        assertTrue(Width.visibleWidth(truncated) <= 10)
        assertTrue(truncated.endsWith("$esc[0m"))
    }

    @Test fun `keeps a contiguous prefix instead of skipping a wide grapheme and resuming later`() {
        val truncated = Width.truncateToWidth("🙂\t界 $esc" + "_abc" + bel, 7, "…", true)
        assertEquals("🙂\t$esc[0m…$esc[0m ", truncated)
    }

    // ---- visibleWidth (ported) ---------------------------------------------

    @Test fun `counts tabs inline and skips ANSI inline`() {
        assertEquals(5, Width.visibleWidth("\t$esc[31m界$esc[0m"))
    }

    @Test fun `keeps Thai and Lao AM clusters at their normal cell width`() {
        assertEquals(1, Width.visibleWidth("ำ"))
        assertEquals(1, Width.visibleWidth("ຳ"))
        assertEquals(2, Width.visibleWidth("กำ"))
        assertEquals(2, Width.visibleWidth("ກຳ"))
    }
}
