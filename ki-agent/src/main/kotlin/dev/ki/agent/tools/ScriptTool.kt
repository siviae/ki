package dev.ki.agent.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import kotlinx.serialization.json.JsonObject

/**
 * Adapts a script-authored [ScriptToolSpec] into a koog [Tool]. The LLM-facing
 * JSON schema comes from the explicit [ToolDescriptor] we build here; koog decodes
 * the model's arguments JSON into a [JsonObject], which the script reads via [ToolArgs].
 * All koog/serialization plumbing lives in compiled Kotlin, never in the script.
 */
class ScriptTool(spec: ScriptToolSpec) : Tool<JsonObject, String>(
    typeToken<JsonObject>(),
    typeToken<String>(),
    descriptorOf(spec),
) {
    private val body = spec.body

    override suspend fun execute(args: JsonObject): String = body(ToolArgs(args))

    companion object {
        private fun descriptorOf(spec: ScriptToolSpec): ToolDescriptor {
            val (required, optional) = spec.parameters.partition { it.required }
            return ToolDescriptor(
                name = spec.name,
                description = spec.description,
                requiredParameters = required.map { it.toKoog() },
                optionalParameters = optional.map { it.toKoog() },
            )
        }

        private fun ToolParam.toKoog(): ToolParameterDescriptor =
            ToolParameterDescriptor(name, description, type.toKoog())

        private fun ParamType.toKoog(): ToolParameterType = when (this) {
            ParamType.STRING -> ToolParameterType.String
            ParamType.INTEGER -> ToolParameterType.Integer
            ParamType.BOOLEAN -> ToolParameterType.Boolean
            ParamType.FLOAT -> ToolParameterType.Float
        }
    }
}
