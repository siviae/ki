package dev.ki.agent.tools.builtin

// Ported from pi packages/coding-agent/test/tools.test.ts — the "edit tool" and
// "edit tool CRLF handling" blocks. Adapted: pi throws on failure and returns a
// {details.diff/patch}; ki's EditTool returns the error message as its result
// string and reports only a success count (diff/patch generation is a rendering
// concern, deferred). pi's ENOENT/EACCES access errors map to ki's exists() check.
//
// Deferred (skipped): the "edit tool fuzzy matching" block (NFKC / smart-quote /
// dash / space normalization) — ki matches exact text only — and diff/patch
// preview (computeEditsDiff) assertions.

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class EditToolTest {
    private val dir: Path = Files.createTempDirectory("ki-edit-test")

    /** Invoke the edit tool with edits given as (oldText, newText) pairs. */
    private fun edit(path: String, vararg edits: Pair<String, String>): String {
        val json = buildJsonObject {
            put("path", path)
            putJsonArray("edits") {
                for ((old, new) in edits) addJsonObject { put("oldText", old); put("newText", new) }
            }
        }
        return runBlocking { EditTool(dir).execute(json) }
    }

    @Test fun `replaces text in a file`() {
        val f = dir.resolve("edit.txt"); f.writeText("Hello, world!")
        val out = edit(f.toString(), "world" to "testing")
        assertContains(out, "Successfully replaced")
        assertEquals("Hello, testing!", f.readText())
    }

    @Test fun `fails if the text is not found`() {
        val f = dir.resolve("nf.txt"); f.writeText("Hello, world!")
        val out = edit(f.toString(), "nonexistent" to "testing")
        assertContains(out, "Could not find the exact text")
    }

    @Test fun `reports ENOENT when the edit target does not exist`() {
        val missing = dir.resolve("missing.txt")
        val out = edit(missing.toString(), "hello" to "world")
        assertEquals("Could not edit file: $missing. Error code: ENOENT.", out)
    }

    @Test fun `fails if the text appears multiple times`() {
        val f = dir.resolve("dup.txt"); f.writeText("foo foo foo")
        val out = edit(f.toString(), "foo" to "bar")
        assertContains(out, "Found 3 occurrences")
    }

    @Test fun `replaces multiple disjoint regions in one call`() {
        val f = dir.resolve("multi.txt"); f.writeText("alpha\nbeta\ngamma\ndelta\n")
        val out = edit(f.toString(), "alpha\n" to "ALPHA\n", "gamma\n" to "GAMMA\n")
        assertContains(out, "Successfully replaced 2 block(s)")
        assertEquals("ALPHA\nbeta\nGAMMA\ndelta\n", f.readText())
    }

    @Test fun `matches edits against the original file, not incrementally`() {
        val f = dir.resolve("orig.txt"); f.writeText("foo\nbar\nbaz\n")
        edit(f.toString(), "foo\n" to "foo bar\n", "bar\n" to "BAR\n")
        assertEquals("foo bar\nBAR\nbaz\n", f.readText())
    }

    // Ported from pi's prepareEditArguments (edit.ts): some models send `edits` as a
    // JSON string rather than an array; the tool must coerce it.
    @Test fun `accepts edits sent as a JSON string`() {
        val f = dir.resolve("stredits.txt"); f.writeText("Hello, world!")
        val json = buildJsonObject {
            put("path", f.toString())
            put("edits", """[{"oldText":"world","newText":"testing"}]""")
        }
        val out = runBlocking { EditTool(dir).execute(json) }
        assertContains(out, "Successfully replaced")
        assertEquals("Hello, testing!", f.readText())
    }

    @Test fun `fails when edits is empty`() {
        val f = dir.resolve("empty.txt"); f.writeText("hello\nworld\n")
        val out = edit(f.toString())
        assertContains(out, "edits must contain at least one replacement")
    }

    @Test fun `fails when multi-edit regions overlap`() {
        val f = dir.resolve("ov.txt"); f.writeText("one\ntwo\nthree\n")
        val out = edit(f.toString(), "one\ntwo\n" to "ONE\nTWO\n", "two\nthree\n" to "TWO\nTHREE\n")
        assertContains(out, "overlap")
    }

    @Test fun `does not partially apply edits when one edit fails`() {
        val original = "alpha\nbeta\ngamma\n"
        val f = dir.resolve("nopartial.txt"); f.writeText(original)
        val out = edit(f.toString(), "alpha\n" to "ALPHA\n", "missing\n" to "MISSING\n")
        assertContains(out, "Could not find")
        assertEquals(original, f.readText())
    }

    // ---- CRLF handling ------------------------------------------------------

    @Test fun `matches LF oldText against CRLF file content`() {
        val f = dir.resolve("crlf.txt"); f.writeText("line one\r\nline two\r\nline three\r\n")
        val out = edit(f.toString(), "line two\n" to "replaced line\n")
        assertContains(out, "Successfully replaced")
    }

    @Test fun `preserves CRLF line endings after an edit`() {
        val f = dir.resolve("crlf2.txt"); f.writeText("first\r\nsecond\r\nthird\r\n")
        edit(f.toString(), "second\n" to "REPLACED\n")
        assertEquals("first\r\nREPLACED\r\nthird\r\n", f.readText())
    }

    @Test fun `preserves LF line endings for LF files`() {
        val f = dir.resolve("lf.txt"); f.writeText("first\nsecond\nthird\n")
        edit(f.toString(), "second\n" to "REPLACED\n")
        assertEquals("first\nREPLACED\nthird\n", f.readText())
    }
}
