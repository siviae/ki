package dev.ki.agent.tools.builtin

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The `edit` tool. Unlike the other builtins, its `edits[]` argument is an array of
 * `{oldText, newText}` objects — which the scalar `tool { }` DSL can't express — so
 * it's a koog [Tool] with an explicit [ToolParameterType.List] of
 * [ToolParameterType.Object] descriptor, decoding the model's [JsonObject] by hand
 * (the same approach [dev.ki.agent.tools.ScriptTool] uses for its args).
 *
 * The matching/replacement contract is [applyEdits] (a port of pi's edit-diff).
 */
class EditTool(private val cwd: Path) : Tool<JsonObject, String>(
    typeToken<JsonObject>(),
    typeToken<String>(),
    descriptor,
) {
    override suspend fun execute(args: JsonObject): String {
        val path = (args["path"] as? JsonPrimitive)?.content
            ?: return "Edit tool input is invalid. 'path' is required."
        // Some models (pi notes Opus 4.6, GLM-5.1) send `edits` as a JSON string
        // instead of an array — coerce it (port of pi's prepareEditArguments).
        val editsElement = args["edits"]
        val editsJson = (editsElement as? JsonArray)
            ?: (editsElement as? JsonPrimitive)?.takeIf { it.isString }
                ?.let { runCatching { Json.parseToJsonElement(it.content) as? JsonArray }.getOrNull() }
            ?: return "Edit tool input is invalid. edits must contain at least one replacement."
        val edits = editsJson.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val old = obj["oldText"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val new = obj["newText"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Edit(old, new)
        }

        val abs = resolveToCwd(path, cwd)
        // pi surfaces the errno; a missing file is ENOENT.
        if (!abs.exists()) return "Could not edit file: $path. Error code: ENOENT."

        return try {
            val newContent = applyEdits(abs.readText(), edits, path)
            abs.writeText(newContent)
            "Successfully replaced ${edits.size} block(s) in $path."
        } catch (e: EditError) {
            e.message ?: "Edit failed."
        }
    }

    companion object {
        private val editObject = ToolParameterType.Object(
            properties = listOf(
                ToolParameterDescriptor("oldText", "Exact text for one targeted replacement. Must be unique in the file and not overlap other edits.", ToolParameterType.String),
                ToolParameterDescriptor("newText", "Replacement text for this targeted edit.", ToolParameterType.String),
            ),
            requiredProperties = listOf("oldText", "newText"),
            additionalProperties = false,
            additionalPropertiesType = null,
        )

        private val descriptor = ToolDescriptor(
            name = "edit",
            description = "Edit a single file using exact text replacement. Every edits[].oldText must " +
                "match a unique, non-overlapping region of the original file. Each edit is matched " +
                "against the original file, not incrementally. Merge nearby changes into one edit.",
            requiredParameters = listOf(
                ToolParameterDescriptor("path", "Path to the file to edit (relative or absolute)", ToolParameterType.String),
                ToolParameterDescriptor("edits", "One or more targeted {oldText, newText} replacements.", ToolParameterType.List(editObject)),
            ),
            optionalParameters = emptyList(),
        )
    }
}
