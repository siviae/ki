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
    fun all(cwd: Path = processCwd()): List<ToolBase<*, *>> = listOf(
        ScriptTool(bashTool(cwd)),
        ScriptTool(readTool(cwd)),
        ScriptTool(writeTool(cwd)),
        ScriptTool(lsTool(cwd)),
        EditTool(cwd),
    )
}
