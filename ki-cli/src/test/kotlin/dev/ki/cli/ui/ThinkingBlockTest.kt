package dev.ki.cli.ui

import dev.ki.tui.Width
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The streamed reasoning region (M9.1) is the actual deliverable surface, so its render
 * is verified directly: empty renders nothing, the `💭` marker sits only on the first
 * line with continuation lines indented, and — per the [dev.ki.tui.Component] contract —
 * no returned line exceeds the viewport width in display cells, even when narrow.
 */
class ThinkingBlockTest {

    @Test fun `blank text renders nothing`() {
        assertEquals(emptyList(), ThinkingBlock().render(40))
    }

    @Test fun `marker on first line only, continuation indented, all lines fit width`() {
        val block = ThinkingBlock()
        block.set("the quick brown fox jumps over the lazy dog again and again and again")
        val width = 20
        val lines = block.render(width)

        assertTrue(lines.size > 1, "long text should wrap to multiple lines")
        assertTrue(Width.stripAnsi(lines.first()).startsWith("💭 "), "first line missing marker")
        lines.drop(1).forEach { l ->
            assertTrue(Width.stripAnsi(l).startsWith("   "), "continuation line not indented: '${Width.stripAnsi(l)}'")
        }
        lines.forEach { l ->
            assertEquals(width, Width.visibleWidth(l), "line exceeds/underfills width: '${Width.stripAnsi(l)}'")
        }
    }

    @Test fun `narrow viewport never overflows the width contract`() {
        val block = ThinkingBlock()
        block.set("reasoning about the problem")
        // Widths at/below the 3-cell marker are the overflow trap the fold must survive.
        for (width in 1..6) {
            block.render(width).forEach { l ->
                assertTrue(Width.visibleWidth(l) <= width, "overflow at width=$width: ${Width.visibleWidth(l)} cells")
            }
        }
    }
}
