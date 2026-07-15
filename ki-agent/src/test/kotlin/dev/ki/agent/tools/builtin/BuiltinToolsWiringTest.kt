package dev.ki.agent.tools.builtin

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ki-specific: the builtins register in a koog ToolRegistry without the hand-built
 *  edit `List(Object)` descriptor being rejected, and expose the expected names. */
class BuiltinToolsWiringTest {
    @Test fun `builtins register and expose their names`() {
        val tools = BuiltinTools.all()
        val names = tools.map { it.descriptor.name }.toSet()
        assertEquals(setOf("bash", "read", "write", "ls", "edit"), names)

        val registry = ToolRegistry { tools(tools) }
        assertNotNull(registry.getTool("edit"))

        // The edit descriptor carries a required array-of-object `edits` parameter.
        val edit = tools.first { it.descriptor.name == "edit" }
        val editsParam = edit.descriptor.requiredParameters.first { it.name == "edits" }
        assertTrue(editsParam.type is ai.koog.agents.core.tools.ToolParameterType.List)
    }

    /** The hand-built `List(Object(...))` edit descriptor must serialize to a valid
     *  OpenAI/LiteLLM JSON schema — the boundary the ToolRegistry test doesn't reach.
     *  Runs the descriptor through the exact generator ki's LiteLLM path uses. */
    @Test fun `edit descriptor serializes to an array-of-object JSON schema`() {
        val edit = BuiltinTools.all().first { it.descriptor.name == "edit" }
        val schema: JsonObject = OpenAICompatibleToolDescriptorSchemaGenerator().generate(edit.descriptor)

        val properties = schema["properties"]!!.jsonObject
        val edits = properties["edits"]!!.jsonObject
        assertEquals("array", edits["type"]!!.jsonPrimitive.content)

        val items = edits["items"]!!.jsonObject
        assertEquals("object", items["type"]!!.jsonPrimitive.content)
        val itemProps = items["properties"]!!.jsonObject
        assertTrue(itemProps.containsKey("oldText"), "items missing oldText: $items")
        assertTrue(itemProps.containsKey("newText"), "items missing newText: $items")
    }
}
