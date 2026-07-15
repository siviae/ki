package dev.ki.agent.tools.builtin

// Ported from pi packages/coding-agent/test/tools.test.ts — the read / write / ls /
// bash "describe" blocks. Adapted to ki's tools: pi returns rich {content, details}
// and throws on tool failure; ki's tools return a single string and fold failures
// (nonzero exit, timeout, not-found) into that returned text, so assertions check
// the returned string rather than a thrown error or a details object.
//
// Deferred (skipped): image reads (MIME/BMP/PNG), streaming onUpdate coalescing,
// shellPath/WSL stdin transport, command-prefix, full-output temp-file persistence,
// and the `find` tool.

import dev.ki.agent.tools.ScriptToolSpec
import dev.ki.agent.tools.ToolArgs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileToolsTest {
    private val dir: Path = Files.createTempDirectory("ki-tools-test")

    private fun invoke(spec: ScriptToolSpec, vararg pairs: Pair<String, Any?>): String {
        val map = HashMap<String, kotlinx.serialization.json.JsonElement>()
        for ((k, v) in pairs) if (v != null) map[k] = JsonPrimitive(v.toString())
        return runBlocking { spec.body(ToolArgs(JsonObject(map))) }
    }

    // ---- read ---------------------------------------------------------------

    @Test fun `read returns file contents that fit within limits`() {
        val f = dir.resolve("test.txt"); f.writeText("Hello, world!\nLine 2\nLine 3")
        val out = invoke(readTool(dir), "path" to f.toString())
        assertEquals("Hello, world!\nLine 2\nLine 3", out)
        assertFalse(out.contains("Use offset="))
    }

    @Test fun `read reports a non-existent file`() {
        val out = invoke(readTool(dir), "path" to dir.resolve("nope.txt").toString())
        assertContains(out.lowercase(), "not found")
    }

    @Test fun `read truncates files exceeding the line limit`() {
        val f = dir.resolve("large.txt")
        f.writeText((1..2500).joinToString("\n") { "Line $it" })
        val out = invoke(readTool(dir), "path" to f.toString())
        assertContains(out, "Line 1")
        assertContains(out, "Line 2000")
        assertFalse(out.contains("Line 2001"))
        assertContains(out, "[Showing lines 1-2000 of 2500. Use offset=2001 to continue.]")
    }

    @Test fun `read truncates when the byte limit is exceeded`() {
        val f = dir.resolve("large-bytes.txt")
        f.writeText((1..500).joinToString("\n") { "Line $it: " + "x".repeat(200) })
        val out = invoke(readTool(dir), "path" to f.toString())
        assertContains(out, "Line 1:")
        assertTrue(Regex("""\[Showing lines 1-\d+ of 500 \(.* limit\)\. Use offset=\d+ to continue\.]""").containsMatchIn(out), out)
    }

    @Test fun `read honors the offset parameter`() {
        val f = dir.resolve("offset.txt"); f.writeText((1..100).joinToString("\n") { "Line $it" })
        val out = invoke(readTool(dir), "path" to f.toString(), "offset" to 51)
        assertFalse(out.contains("Line 50"))
        assertContains(out, "Line 51")
        assertContains(out, "Line 100")
        assertFalse(out.contains("Use offset="))
    }

    @Test fun `read honors the limit parameter`() {
        val f = dir.resolve("limit.txt"); f.writeText((1..100).joinToString("\n") { "Line $it" })
        val out = invoke(readTool(dir), "path" to f.toString(), "limit" to 10)
        assertContains(out, "Line 10")
        assertFalse(out.contains("Line 11"))
        assertContains(out, "[90 more lines in file. Use offset=11 to continue.]")
    }

    @Test fun `read honors offset and limit together`() {
        val f = dir.resolve("ol.txt"); f.writeText((1..100).joinToString("\n") { "Line $it" })
        val out = invoke(readTool(dir), "path" to f.toString(), "offset" to 41, "limit" to 20)
        assertFalse(out.contains("Line 40"))
        assertContains(out, "Line 41")
        assertContains(out, "Line 60")
        assertFalse(out.contains("Line 61"))
        assertContains(out, "[40 more lines in file. Use offset=61 to continue.]")
    }

    @Test fun `read errors when the offset is beyond the file length`() {
        val f = dir.resolve("short.txt"); f.writeText("Line 1\nLine 2\nLine 3")
        val out = invoke(readTool(dir), "path" to f.toString(), "offset" to 100)
        assertContains(out, "Offset 100 is beyond end of file (3 lines total)")
    }

    // ---- write --------------------------------------------------------------

    @Test fun `write creates a file and reports the byte count`() {
        val f = dir.resolve("w.txt")
        val out = invoke(writeTool(dir), "path" to f.toString(), "content" to "Test content")
        assertContains(out, "Successfully wrote")
        assertEquals("Test content", f.readText())
    }

    @Test fun `write creates parent directories`() {
        val f = dir.resolve("nested/deep/t.txt")
        val out = invoke(writeTool(dir), "path" to f.toString(), "content" to "Nested content")
        assertContains(out, "Successfully wrote")
        assertEquals("Nested content", f.readText())
    }

    // ---- ls -----------------------------------------------------------------

    @Test fun `ls lists dotfiles and marks directories with a slash`() {
        val d = Files.createTempDirectory("ki-ls")
        d.resolve(".hidden-file").writeText("secret")
        d.resolve(".hidden-dir").createDirectories()
        val out = invoke(lsTool(dir), "path" to d.toString())
        assertContains(out, ".hidden-file")
        assertContains(out, ".hidden-dir/")
    }

    @Test fun `ls reports an empty directory`() {
        val d = Files.createTempDirectory("ki-ls-empty")
        assertEquals("(empty directory)", invoke(lsTool(dir), "path" to d.toString()))
    }

    // ---- bash ---------------------------------------------------------------

    @Test fun `bash executes a simple command`() {
        val out = invoke(bashTool(dir), "command" to "echo 'test output'")
        assertContains(out, "test output")
    }

    @Test fun `bash surfaces a nonzero exit code`() {
        val out = invoke(bashTool(dir), "command" to "exit 1")
        assertContains(out, "Command exited with code 1")
    }

    @Test fun `bash merges stderr into the output`() {
        val out = invoke(bashTool(dir), "command" to "echo err 1>&2")
        assertContains(out, "err")
    }

    @Test fun `bash respects a timeout`() {
        val out = invoke(bashTool(dir), "command" to "sleep 5", "timeout" to 1)
        assertContains(out.lowercase(), "timed out")
    }

    @Test fun `bash errors when the working directory does not exist`() {
        val missing = dir.resolve("does-not-exist")
        val e = runCatching { invoke(bashTool(missing), "command" to "echo hi") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "expected IllegalArgumentException, got $e")
        assertContains(e.message ?: "", "Working directory does not exist")
    }
}
