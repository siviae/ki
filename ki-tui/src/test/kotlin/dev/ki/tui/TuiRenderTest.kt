package dev.ki.tui

// Ported from pi packages/tui/test/tui-render.test.ts ("TUI resize handling",
// "TUI content shrinkage", "TUI differential rendering") and tui-shrink.test.ts,
// via the Kotlin [VirtualTerminal]. Deferred (skipped): all Kitty-image cases and
// the Termux height-change special-case (ki always full-redraws on height change).

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuiRenderTest {
    private val esc = 27.toChar().toString()

    private fun tui(term: VirtualTerminal): Tui = Tui(term)

    // ---- tui-shrink.test.ts ------------------------------------------------

    @Test fun `clears all rendered lines when content shrinks to zero`() {
        val term = VirtualTerminal(40, 10)
        val t = tui(term)
        val content = LinesComponent(listOf("first", "second", "third"))
        t.addChild(content); t.start(); t.awaitIdle()

        assertTrue(term.getViewport().any { it.contains("first") })
        assertTrue(term.getViewport().any { it.contains("second") })
        assertTrue(term.getViewport().any { it.contains("third") })

        content.lines = emptyList()
        t.requestRender(); t.awaitIdle()

        val vp = term.getViewport()
        assertTrue(vp.none { it.contains("first") }, "first not cleared")
        assertTrue(vp.none { it.contains("second") }, "second not cleared")
        assertTrue(vp.none { it.contains("third") }, "third not cleared")
        t.stop()
    }

    // ---- TUI resize handling -----------------------------------------------

    @Test fun `triggers full re-render when terminal height changes`() {
        val term = VirtualTerminal(40, 10)
        val t = tui(term)
        val c = LinesComponent(listOf("Line 0", "Line 1", "Line 2"))
        t.addChild(c); t.start(); t.awaitIdle()
        val initial = t.fullRedraws

        term.resize(40, 15); t.awaitIdle()

        assertTrue(t.fullRedraws > initial, "height change should full-redraw")
        assertTrue(term.getViewport()[0].contains("Line 0"), "content preserved")
        t.stop()
    }

    @Test fun `triggers full re-render when terminal width changes`() {
        val term = VirtualTerminal(40, 10)
        val t = tui(term)
        val c = LinesComponent(listOf("Line 0", "Line 1", "Line 2"))
        t.addChild(c); t.start(); t.awaitIdle()
        val initial = t.fullRedraws

        term.resize(60, 10); t.awaitIdle()

        assertTrue(t.fullRedraws > initial, "width change should full-redraw")
        t.stop()
    }

    // ---- TUI content shrinkage ---------------------------------------------

    @Test fun `clears empty rows when content shrinks significantly`() {
        val term = VirtualTerminal(40, 10)
        val t = tui(term)
        val c = LinesComponent(listOf("Line 0", "Line 1", "Line 2", "Line 3", "Line 4", "Line 5"))
        t.addChild(c); t.start(); t.awaitIdle()
        val initial = t.fullRedraws

        c.lines = listOf("Line 0", "Line 1")
        t.requestRender(); t.awaitIdle()

        assertTrue(t.fullRedraws > initial, "shrink should full-redraw")
        val vp = term.getViewport()
        assertTrue(vp[0].contains("Line 0")); assertTrue(vp[1].contains("Line 1"))
        assertEquals("", vp[2]); assertEquals("", vp[3])
        t.stop()
    }

    @Test fun `handles shrink to single line`() {
        val term = VirtualTerminal(40, 10)
        val t = tui(term)
        val c = LinesComponent(listOf("Line 0", "Line 1", "Line 2", "Line 3"))
        t.addChild(c); t.start(); t.awaitIdle()

        c.lines = listOf("Only line")
        t.requestRender(); t.awaitIdle()

        val vp = term.getViewport()
        assertTrue(vp[0].contains("Only line")); assertEquals("", vp[1])
        t.stop()
    }

    @Test fun `handles shrink to empty`() {
        val term = VirtualTerminal(40, 10)
        val t = tui(term)
        val c = LinesComponent(listOf("Line 0", "Line 1", "Line 2"))
        t.addChild(c); t.start(); t.awaitIdle()

        c.lines = emptyList()
        t.requestRender(); t.awaitIdle()

        val vp = term.getViewport()
        assertEquals("", vp[0]); assertEquals("", vp[1])
        t.stop()
    }

    // ---- TUI differential rendering ----------------------------------------

    @Test fun `tracks cursor correctly when content shrinks with unchanged remaining lines`() {
        val term = VirtualTerminal(40, 10)
        val t = tui(term)
        val c = LinesComponent(listOf("Line 0", "Line 1", "Line 2", "Line 3", "Line 4"))
        t.addChild(c); t.start(); t.awaitIdle()

        c.lines = listOf("Line 0", "Line 1", "Line 2")
        t.requestRender(); t.awaitIdle()

        c.lines = listOf("Line 0", "CHANGED", "Line 2")
        t.requestRender(); t.awaitIdle()

        assertTrue(term.getViewport()[1].contains("CHANGED"), "cursor tracking: ${term.getViewport()[1]}")
        t.stop()
    }

    @Test fun `renders correctly when only a middle line changes`() {
        val term = VirtualTerminal(40, 10)
        val t = tui(term)
        val c = LinesComponent(listOf("Header", "Working...", "Footer"))
        t.addChild(c); t.start(); t.awaitIdle()

        for (frame in listOf("|", "/", "-", "\\")) {
            c.lines = listOf("Header", "Working $frame", "Footer")
            t.requestRender(); t.awaitIdle()
            val vp = term.getViewport()
            assertTrue(vp[0].contains("Header"))
            assertTrue(vp[1].contains("Working $frame"), "spinner: ${vp[1]}")
            assertTrue(vp[2].contains("Footer"))
        }
        t.stop()
    }

    @Test fun `resets styles after each rendered line`() {
        val term = VirtualTerminal(20, 6)
        val t = tui(term)
        t.addChild(LinesComponent(listOf("$esc[3mItalic", "Plain")))
        t.start(); t.awaitIdle()
        assertEquals(false, term.cellItalic(1, 0)) // "Plain" must not inherit italic
        t.stop()
    }

    @Test fun `renders correctly when first line changes but rest stays same`() {
        val term = VirtualTerminal(40, 10)
        val t = tui(term)
        val c = LinesComponent(listOf("Line 0", "Line 1", "Line 2", "Line 3"))
        t.addChild(c); t.start(); t.awaitIdle()

        c.lines = listOf("CHANGED", "Line 1", "Line 2", "Line 3")
        t.requestRender(); t.awaitIdle()

        val vp = term.getViewport()
        assertTrue(vp[0].contains("CHANGED")); assertTrue(vp[1].contains("Line 1"))
        assertTrue(vp[2].contains("Line 2")); assertTrue(vp[3].contains("Line 3"))
        t.stop()
    }

    @Test fun `renders correctly when last line changes but rest stays same`() {
        val term = VirtualTerminal(40, 10)
        val t = tui(term)
        val c = LinesComponent(listOf("Line 0", "Line 1", "Line 2", "Line 3"))
        t.addChild(c); t.start(); t.awaitIdle()

        c.lines = listOf("Line 0", "Line 1", "Line 2", "CHANGED")
        t.requestRender(); t.awaitIdle()

        val vp = term.getViewport()
        assertTrue(vp[0].contains("Line 0")); assertTrue(vp[1].contains("Line 1"))
        assertTrue(vp[2].contains("Line 2")); assertTrue(vp[3].contains("CHANGED"))
        t.stop()
    }

    @Test fun `renders correctly when multiple non-adjacent lines change`() {
        val term = VirtualTerminal(40, 10)
        val t = tui(term)
        val c = LinesComponent(listOf("Line 0", "Line 1", "Line 2", "Line 3", "Line 4"))
        t.addChild(c); t.start(); t.awaitIdle()

        c.lines = listOf("Line 0", "CHANGED 1", "Line 2", "CHANGED 3", "Line 4")
        t.requestRender(); t.awaitIdle()

        val vp = term.getViewport()
        assertTrue(vp[0].contains("Line 0")); assertTrue(vp[1].contains("CHANGED 1"))
        assertTrue(vp[2].contains("Line 2")); assertTrue(vp[3].contains("CHANGED 3"))
        assertTrue(vp[4].contains("Line 4"))
        t.stop()
    }

    @Test fun `handles transition from content to empty and back to content`() {
        val term = VirtualTerminal(40, 10)
        val t = tui(term)
        val c = LinesComponent(listOf("Line 0", "Line 1", "Line 2"))
        t.addChild(c); t.start(); t.awaitIdle()
        assertTrue(term.getViewport()[0].contains("Line 0"))

        c.lines = emptyList(); t.requestRender(); t.awaitIdle()
        c.lines = listOf("New Line 0", "New Line 1"); t.requestRender(); t.awaitIdle()

        val vp = term.getViewport()
        assertTrue(vp[0].contains("New Line 0"), "new content: ${vp[0]}")
        assertTrue(vp[1].contains("New Line 1"))
        t.stop()
    }

    @Test fun `full re-renders when deleted lines move the viewport upward`() {
        val term = VirtualTerminal(20, 5)
        val t = tui(term)
        val c = LinesComponent((0 until 12).map { "Line $it" })
        t.addChild(c); t.start(); t.awaitIdle()
        val initial = t.fullRedraws

        c.lines = (0 until 7).map { "Line $it" }
        t.requestRender(); t.awaitIdle()

        assertTrue(t.fullRedraws > initial, "shrink should full-redraw")
        assertEquals(listOf("Line 2", "Line 3", "Line 4", "Line 5", "Line 6"), term.getViewport())
        t.stop()
    }

    @Test fun `appends after a shrink without another full redraw once the viewport is reset`() {
        val term = VirtualTerminal(20, 5)
        val t = tui(term)
        val c = LinesComponent((0 until 8).map { "Line $it" })
        t.addChild(c); t.start(); t.awaitIdle()
        val initial = t.fullRedraws

        c.lines = listOf("Line 0", "Line 1")
        t.requestRender(); t.awaitIdle()
        assertTrue(t.fullRedraws > initial, "shrink should full-redraw")
        val afterShrink = t.fullRedraws

        c.lines = listOf("Line 0", "Line 1", "Line 2")
        t.requestRender(); t.awaitIdle()
        assertEquals(afterShrink, t.fullRedraws, "append should stay on the diff path")
        assertEquals(listOf("Line 0", "Line 1", "Line 2", "", ""), term.getViewport())
        t.stop()
    }
}
