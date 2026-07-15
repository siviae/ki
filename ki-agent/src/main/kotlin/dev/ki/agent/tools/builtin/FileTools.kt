package dev.ki.agent.tools.builtin

import dev.ki.agent.tools.ParamType
import dev.ki.agent.tools.ScriptToolSpec
import dev.ki.agent.tools.tool
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The builtin file & shell tools, ported from pi's coding-agent tools (bash.ts,
 * read.ts, write.ts, ls.ts). Each is authored with the same declarative `tool { }`
 * DSL the .kts scripts use — the only structural port is `edit` (see [editTool]),
 * whose `edits[]` array cannot be expressed with scalar params.
 *
 * Rendering, image handling, streaming updates and the macOS path-variant
 * fallbacks are UI/host concerns and are deferred; the execute-side contract
 * (truncation, offsets, error strings) follows pi.
 */

fun bashTool(cwd: Path): ScriptToolSpec = tool("bash") {
    description = "Execute a bash command in the current working directory. Returns stdout and " +
        "stderr merged. Output is truncated to the last 2000 lines or 50KB (whichever is hit " +
        "first). Optionally provide a timeout in seconds."
    param("command", "Bash command to execute", ParamType.STRING, required = true)
    param("timeout", "Timeout in seconds (optional, no default timeout)", ParamType.INTEGER, required = false)
    execute { args ->
        val command = args.string("command")
        val timeout = args.intOrNull("timeout")?.toLong()
        if (timeout != null && timeout <= 0) return@execute "Invalid timeout: must be a positive number of seconds"

        val result = BashExec.run(command, cwd, timeout)
        val truncation = truncateTail(result.output)
        // The body (last-N lines of output) plus a truncation notice, if any — shared
        // by the success and error paths so a failing *and* truncated command keeps it.
        val body = buildString {
            append(truncation.content)
            if (truncation.truncated) {
                val startLine = truncation.totalLines - truncation.outputLines + 1
                append("\n\n[Showing lines $startLine-${truncation.totalLines} of ${truncation.totalLines}. Output truncated.]")
            }
        }
        fun withStatus(status: String) = if (result.output.isEmpty()) status else "$body\n\n$status"
        when {
            result.timedOut -> withStatus("Command timed out after $timeout seconds")
            result.exitCode != null && result.exitCode != 0 -> withStatus("Command exited with code ${result.exitCode}")
            result.output.isEmpty() -> "(no output)"
            else -> body
        }
    }
}

fun writeTool(cwd: Path): ScriptToolSpec = tool("write") {
    description = "Write content to a file. Creates the file if it doesn't exist, overwrites if it " +
        "does. Automatically creates parent directories."
    param("path", "Path to the file to write (relative or absolute)", ParamType.STRING, required = true)
    param("content", "The content to write", ParamType.STRING, required = true)
    execute { args ->
        val path = args.string("path")
        val content = args.string("content")
        val abs = resolveToCwd(path, cwd)
        abs.parent?.let { Files.createDirectories(it) }
        abs.writeText(content)
        "Successfully wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to $path"
    }
}

fun lsTool(cwd: Path): ScriptToolSpec = tool("ls") {
    description = "List directory contents. Returns entries sorted alphabetically, with '/' suffix " +
        "for directories. Includes dotfiles. Output is truncated to 500 entries or 50KB."
    param("path", "Directory to list (default: current directory)", ParamType.STRING, required = false)
    param("limit", "Maximum number of entries to return (default: 500)", ParamType.INTEGER, required = false)
    execute { args ->
        val dir = resolveToCwd(args.stringOrNull("path") ?: ".", cwd)
        val limit = args.intOrNull("limit") ?: 500
        if (!dir.exists()) return@execute "Path not found: $dir"
        if (!dir.isDirectory()) return@execute "Not a directory: $dir"

        val entries = dir.listDirectoryEntries().sortedBy { it.name.lowercase() }
        var entryLimitReached = false
        val results = ArrayList<String>()
        for (entry in entries) {
            if (results.size >= limit) { entryLimitReached = true; break }
            val suffix = if (runCatching { entry.isDirectory() }.getOrDefault(false)) "/" else ""
            results.add(entry.name + suffix)
        }
        if (results.isEmpty()) return@execute "(empty directory)"

        val truncation = truncateHead(results.joinToString("\n"), maxLines = Int.MAX_VALUE)
        var output = truncation.content
        val notices = ArrayList<String>()
        if (entryLimitReached) notices.add("$limit entries limit reached. Use limit=${limit * 2} for more")
        if (truncation.truncated) notices.add("${formatSize(DEFAULT_MAX_BYTES)} limit reached")
        if (notices.isNotEmpty()) output += "\n\n[${notices.joinToString(". ")}]"
        output
    }
}

fun readTool(cwd: Path): ScriptToolSpec = tool("read") {
    description = "Read the contents of a text file. Output is truncated to 2000 lines or 50KB " +
        "(whichever is hit first). Use offset/limit for large files; when you need the full file, " +
        "continue with offset until complete."
    param("path", "Path to the file to read (relative or absolute)", ParamType.STRING, required = true)
    param("offset", "Line number to start reading from (1-indexed)", ParamType.INTEGER, required = false)
    param("limit", "Maximum number of lines to read", ParamType.INTEGER, required = false)
    execute { args ->
        val path = args.string("path")
        val offset = args.intOrNull("offset")
        val limit = args.intOrNull("limit")
        val abs = resolveToCwd(path, cwd)
        if (!abs.exists()) return@execute "File not found: $path"

        val allLines = abs.readText().split("\n")
        val totalFileLines = allLines.size
        val startLine = if (offset != null) maxOf(0, offset - 1) else 0
        val startLineDisplay = startLine + 1
        if (startLine >= allLines.size) {
            return@execute "Offset $offset is beyond end of file (${allLines.size} lines total)"
        }

        val selected: String
        val userLimited: Int?
        if (limit != null) {
            val endLine = minOf(startLine + limit, allLines.size)
            selected = allLines.subList(startLine, endLine).joinToString("\n")
            userLimited = endLine - startLine
        } else {
            selected = allLines.subList(startLine, allLines.size).joinToString("\n")
            userLimited = null
        }

        val truncation = truncateHead(selected)
        when {
            truncation.firstLineExceedsLimit -> {
                val firstLineSize = formatSize(allLines[startLine].toByteArray(Charsets.UTF_8).size)
                "[Line $startLineDisplay is $firstLineSize, exceeds ${formatSize(DEFAULT_MAX_BYTES)} limit. " +
                    "Use bash: sed -n '${startLineDisplay}p' $path | head -c $DEFAULT_MAX_BYTES]"
            }
            truncation.truncated -> {
                val endLineDisplay = startLineDisplay + truncation.outputLines - 1
                val nextOffset = endLineDisplay + 1
                val limitNote = if (truncation.truncatedBy == "lines") "" else " (${formatSize(DEFAULT_MAX_BYTES)} limit)"
                truncation.content +
                    "\n\n[Showing lines $startLineDisplay-$endLineDisplay of $totalFileLines$limitNote. Use offset=$nextOffset to continue.]"
            }
            userLimited != null && startLine + userLimited < allLines.size -> {
                val remaining = allLines.size - (startLine + userLimited)
                val nextOffset = startLine + userLimited + 1
                "${truncation.content}\n\n[$remaining more lines in file. Use offset=$nextOffset to continue.]"
            }
            else -> truncation.content
        }
    }
}
