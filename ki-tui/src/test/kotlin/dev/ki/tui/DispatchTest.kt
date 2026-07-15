package dev.ki.tui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Terminal that captures the input handler so a test can inject raw chunks. */
private class InjectTerminal : Terminal {
    var onInput: ((String) -> Unit)? = null
    override fun start(onInput: (String) -> Unit, onResize: () -> Unit) { this.onInput = onInput }
    override fun stop() {}
    override fun write(s: String) {}
    override val columns = 40
    override val rows = 10
    override fun moveBy(lines: Int) {}
    override fun hideCursor() {}
    override fun showCursor() {}
    override fun clearLine() {}
    override fun clearScreen() {}
    override fun setTitle(title: String) {}
}

class DispatchTest {
    // Regression: a real pty commonly delivers "text" + Enter as one read chunk,
    // and delivers Enter as LF (0x0A), not CR. Both must submit.
    @Test fun `a single chunk of text plus LF submits`() {
        assertSubmits("hi\n")
    }

    @Test fun `a single chunk of text plus CR submits`() {
        assertSubmits("hi\r")
    }

    private fun assertSubmits(chunk: String) {
        val term = InjectTerminal()
        val tui = Tui(term)
        val editor = Editor()
        var submitted: String? = null
        editor.onSubmit = { submitted = it }
        tui.addChild(editor)
        tui.setFocus(editor)
        tui.start()
        tui.awaitIdle()

        term.onInput!!(chunk)
        tui.awaitIdle()

        assertEquals("hi", editor.text)
        assertEquals("hi", submitted)
        tui.stop()
    }
}
