package dev.ki.cli

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.rag.base.files.JVMFileSystemProvider

/**
 * Core tools that ship with the coding agent. These reuse koog's `agents-ext`
 * implementations rather than reimplementing them; the Kotlin-script mechanism
 * (see the .ki/tools scripts) is for user/self-extension tools like `grep`.
 *
 * M1 wires the read tool; write/edit/bash/ls land in M2.
 */
object BuiltinTools {
    fun all(): List<ToolBase<*, *>> = listOf(
        ReadFileTool(JVMFileSystemProvider.ReadOnly),
    )
}
