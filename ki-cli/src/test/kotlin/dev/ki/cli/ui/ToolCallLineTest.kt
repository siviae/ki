package dev.ki.cli.ui

import dev.ki.tui.Width
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tool-call row (M9.2) is a visible deliverable, so its render is checked directly:
 * a full-width background stripe (so the color spans the row per the [dev.ki.tui.Component]
 * width contract), the pi background hexes per lifecycle phase, and the bold `⏺ name(args)`
 * title surviving under the escapes.
 */
class ToolCallLineTest {

    private fun onlyLine(line: ToolCallLine, width: Int = 40): String {
        val lines = line.render(width)
        assertEquals(1, lines.size, "tool line should render as a single row")
        return lines.single()
    }

    @Test fun `renders bold title with args on a full-width stripe`() {
        val line = ToolCallLine("bash", "cmd=ls -la")
        val rendered = onlyLine(line, width = 40)

        assertEquals("⏺ bash(cmd=ls -la)", Width.stripAnsi(rendered).trimEnd())
        assertEquals(40, Width.visibleWidth(rendered), "stripe must fill the full width")
        assertTrue(rendered.contains("[1m") && rendered.contains("[22m"), "title should be bold via SGR 1/22")
    }

    @Test fun `background color tracks lifecycle phase (pi hexes)`() {
        val line = ToolCallLine("read", "path=/x")
        assertTrue(onlyLine(line).contains("48;2;40;40;50"), "pending should use toolPendingBg #282832")
        line.set(ToolCallLine.Phase.SUCCESS)
        assertTrue(onlyLine(line).contains("48;2;40;50;40"), "success should use toolSuccessBg #283228 (green)")
        line.set(ToolCallLine.Phase.ERROR)
        assertTrue(onlyLine(line).contains("48;2;60;40;40"), "error should use toolErrorBg #3c2828")
    }

    @Test fun `no-arg call omits the parens`() {
        assertEquals("⏺ ls", Width.stripAnsi(onlyLine(ToolCallLine("ls", ""))).trimEnd())
    }

    @Test fun `narrow viewport never overflows the width contract`() {
        val line = ToolCallLine("bash", "a very long argument string that must be clipped")
        for (width in 1..8) {
            line.render(width).forEach { l ->
                assertTrue(Width.visibleWidth(l) <= width, "overflow at width=$width: ${Width.visibleWidth(l)} cells")
            }
        }
    }
}
