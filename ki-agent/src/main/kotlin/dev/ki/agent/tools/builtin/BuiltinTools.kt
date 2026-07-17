package dev.ki.agent.tools.builtin

import ai.koog.agents.core.tools.ToolBase
import dev.ki.agent.tools.ScriptTool
import java.nio.file.Path

/**
 * The core file & shell tools every ki session ships with (M3). `bash`, `read`,
 * `write`, `ls` are authored with the `tool { }` DSL and wrapped as [ScriptTool];
 * `edit` is a dedicated [EditTool] (structured `edits[]` args). `grep` remains a
 * bundled .kts script (loaded separately by the CLI), and `find` is deferred.
 *
 * All tools resolve relative paths against [cwd] (default: the process cwd).
 */
object BuiltinTools {
    fun all(cwd: Path = processCwd()): List<ToolBase<*, *>> =
        NAMES.map { byName(it, cwd)!! }

    /** Names of the builtin tools, as referenced in a `ki.toml` manifest's `[tools.*]`. */
    val NAMES: Set<String> = setOf("bash", "read", "write", "ls", "edit")

    /** Build a single builtin by manifest name, or `null` if [name] is not a builtin. */
    fun byName(name: String, cwd: Path = processCwd()): ToolBase<*, *>? = when (name) {
        "bash" -> ScriptTool(bashTool(cwd))
        "read" -> ScriptTool(readTool(cwd))
        "write" -> ScriptTool(writeTool(cwd))
        "ls" -> ScriptTool(lsTool(cwd))
        "edit" -> EditTool(cwd)
        else -> null
    }
}
