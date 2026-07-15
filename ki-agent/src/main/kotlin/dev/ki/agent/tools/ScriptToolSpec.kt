package dev.ki.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The contract a `.kts` tool script produces. A script's final expression is a
 * call to [tool] { ... }, which returns one of these. ki-agent wraps it into a
 * koog [dev.ki.agent.tools.ScriptTool]. Keeping the koog/serialization plumbing
 * out here means scripts stay tiny and declarative.
 */
class ScriptToolSpec internal constructor(
    val name: String,
    var description: String,
    val parameters: MutableList<ToolParam>,
    var body: suspend (ToolArgs) -> String,
)

data class ToolParam(
    val name: String,
    val description: String,
    val type: ParamType,
    val required: Boolean,
)

enum class ParamType { STRING, INTEGER, BOOLEAN, FLOAT }

/** Thin typed view over the decoded tool-call arguments passed to a script. */
class ToolArgs(private val json: JsonObject) {
    fun string(name: String): String =
        (json[name] as? JsonPrimitive)?.content
            ?: error("missing required string arg '$name'")

    fun stringOrNull(name: String): String? =
        (json[name] as? JsonPrimitive)?.content

    fun intOrNull(name: String): Int? = json[name]?.jsonPrimitive?.content?.toIntOrNull()

    fun boolOrNull(name: String): Boolean? = json[name]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
}

/** DSL builder used inside a `.kts` script. */
class ToolBuilder(private val name: String) {
    var description: String = ""
    private val params = mutableListOf<ToolParam>()
    private var body: suspend (ToolArgs) -> String = { "" }

    fun param(name: String, description: String, type: ParamType = ParamType.STRING, required: Boolean = true) {
        params += ToolParam(name, description, type, required)
    }

    fun execute(block: suspend (ToolArgs) -> String) {
        body = block
    }

    internal fun build(): ScriptToolSpec = ScriptToolSpec(name, description, params, body)
}

/** Entry point a tool script calls. */
fun tool(name: String, configure: ToolBuilder.() -> Unit): ScriptToolSpec =
    ToolBuilder(name).apply(configure).build()
