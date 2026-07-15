package dev.ki.agent.tools.builtin

// Ported from pi packages/coding-agent/src/core/tools/truncate.ts behavior (the
// truncate module has no standalone test file in pi; these assert the contract the
// read/bash/ls tests rely on: line-limit vs byte-limit precedence, and tail's
// partial-last-line edge case).

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TruncateTest {
    @Test fun `head keeps everything when within limits`() {
        val r = truncateHead("a\nb\nc")
        assertFalse(r.truncated)
        assertEquals("a\nb\nc", r.content)
        assertEquals(3, r.totalLines)
    }

    @Test fun `head truncates by line limit`() {
        val content = (1..2500).joinToString("\n") { "Line $it" }
        val r = truncateHead(content)
        assertTrue(r.truncated)
        assertEquals("lines", r.truncatedBy)
        assertEquals(2500, r.totalLines)
        assertEquals(2000, r.outputLines)
        assertTrue(r.content.endsWith("Line 2000"))
    }

    @Test fun `head truncates by byte limit before line limit`() {
        val content = (1..500).joinToString("\n") { "Line $it: " + "x".repeat(200) }
        val r = truncateHead(content)
        assertTrue(r.truncated)
        assertEquals("bytes", r.truncatedBy)
        assertTrue(r.outputLines < 500)
        assertTrue(r.outputBytes <= 50 * 1024)
    }

    @Test fun `head flags a first line that alone exceeds the byte budget`() {
        val r = truncateHead("y".repeat(60 * 1024) + "\nsecond")
        assertTrue(r.firstLineExceedsLimit)
        assertEquals("", r.content)
    }

    @Test fun `tail keeps the last lines`() {
        val content = (1..2500).joinToString("\n") { "Line $it" }
        val r = truncateTail(content)
        assertTrue(r.truncated)
        assertEquals(2000, r.outputLines)
        assertTrue(r.content.startsWith("Line 501"))
        assertTrue(r.content.endsWith("Line 2500"))
    }

    @Test fun `tail returns a partial last line when it alone busts the byte budget`() {
        val r = truncateTail("z".repeat(60 * 1024))
        assertTrue(r.truncated)
        assertTrue(r.lastLinePartial)
        assertTrue(r.outputBytes <= 50 * 1024)
    }
}
