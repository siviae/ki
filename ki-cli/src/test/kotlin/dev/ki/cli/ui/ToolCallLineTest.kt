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

    @Test fun `a completed call with a result renders the output beneath the stripe`() {
        val line = ToolCallLine("ls", "path=.")
        line.set(ToolCallLine.Phase.SUCCESS, "foo.txt\nbar.txt")

        val rendered = line.render(40)

        assertEquals(3, rendered.size, "stripe + 2 result lines")
        assertEquals("⏺ ls(path=.)", Width.stripAnsi(rendered[0]).trimEnd())
        assertEquals("  foo.txt", Width.stripAnsi(rendered[1]).trimEnd())
        assertEquals("  bar.txt", Width.stripAnsi(rendered[2]).trimEnd())
    }

    @Test fun `an OK call with no result stays a single row (backward compatible)`() {
        val line = ToolCallLine("ls", "path=.")
        line.set(ToolCallLine.Phase.SUCCESS)
        assertEquals(1, line.render(40).size)
    }

    @Test fun `a blank result renders no extra lines`() {
        val line = ToolCallLine("ls", "path=.")
        line.set(ToolCallLine.Phase.SUCCESS, "   \n  ")
        assertEquals(1, line.render(40).size)
    }

    @Test fun `long results are capped with a ctrl-o expand hint`() {
        val line = ToolCallLine("bash", "cmd=yes")
        line.set(ToolCallLine.Phase.SUCCESS, (1..20).joinToString("\n") { "line $it" })

        val rendered = line.render(40)

        assertEquals(7, rendered.size, "stripe + 6 preview lines")
        val hint = Width.stripAnsi(rendered.last()).trimEnd()
        assertEquals("  … 15 more lines (ctrl-o to expand)", hint)
    }

    @Test fun `setExpanded shows the full result, no hint line`() {
        val line = ToolCallLine("bash", "cmd=yes")
        line.set(ToolCallLine.Phase.SUCCESS, (1..20).joinToString("\n") { "line $it" })
        line.setExpanded(true)

        val rendered = line.render(40)

        assertEquals(21, rendered.size, "stripe + all 20 lines, no cap")
        assertEquals("  line 20", Width.stripAnsi(rendered.last()).trimEnd())
        assertTrue(rendered.none { Width.stripAnsi(it).contains("ctrl-o to expand") })
    }

    @Test fun `collapsing back after expand re-caps the preview`() {
        val line = ToolCallLine("bash", "cmd=yes")
        line.set(ToolCallLine.Phase.SUCCESS, (1..20).joinToString("\n") { "line $it" })
        line.setExpanded(true)
        line.setExpanded(false)

        assertEquals(7, line.render(40).size)
    }

    @Test fun `a short result is unaffected by expand`() {
        val line = ToolCallLine("ls", "path=.")
        line.set(ToolCallLine.Phase.SUCCESS, "foo.txt\nbar.txt")
        line.setExpanded(true)

        assertEquals(3, line.render(40).size, "already fits; expand is a no-op")
    }

    @Test fun `expanded result preview never overflows the width contract`() {
        val line = ToolCallLine("bash", "cmd=x")
        line.set(ToolCallLine.Phase.SUCCESS, (1..20).joinToString("\n") { "a fairly long line of tool output number $it" })
        line.setExpanded(true)
        for (width in 1..8) {
            line.render(width).forEach { l ->
                assertTrue(Width.visibleWidth(l) <= width, "overflow at width=$width: ${Width.visibleWidth(l)} cells")
            }
        }
    }

    @Test fun `result preview never overflows the width contract either`() {
        val line = ToolCallLine("bash", "cmd=x")
        line.set(ToolCallLine.Phase.ERROR, "an error message that is definitely longer than a narrow terminal")
        for (width in 1..8) {
            line.render(width).forEach { l ->
                assertTrue(Width.visibleWidth(l) <= width, "overflow at width=$width: ${Width.visibleWidth(l)} cells")
            }
        }
    }
}
