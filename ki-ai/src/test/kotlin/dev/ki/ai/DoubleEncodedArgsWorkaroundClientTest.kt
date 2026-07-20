package dev.ki.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for the koog-preview7 double-encode workaround. Verified against the real
 * proxy (see [DoubleEncodedArgsWorkaroundClient]'s doc): a doubly-encoded `arguments` string
 * 500s, a single-encoded one doesn't. This is the exact shape koog's
 * `convertPromptToMessages` produces when replaying a prior assistant tool call.
 */
class DoubleEncodedArgsWorkaroundClientTest {
    private val doubleEncoded = """
        {"messages":[{"role":"assistant","tool_calls":[{"id":"call_1","function":{"name":"bash","arguments":"\"{\\\"command\\\": \\\"echo hi\\\"}\""},"type":"function"}]}],"model":"deepseek-v4-flash"}
    """.trimIndent()

    @Test
    fun `unwraps a doubly-encoded arguments string down to the args object`() {
        val fixed = unwrapDoubleEncodedToolArgs(doubleEncoded)

        val arguments = Json.parseToJsonElement(fixed).jsonObject["messages"]!!.jsonArray.first()
            .jsonObject["tool_calls"]!!.jsonArray.first()
            .jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content

        val parsedOnce = Json.parseToJsonElement(arguments)
        assertTrue(parsedOnce is JsonObject, "expected arguments to parse to an object, got: $parsedOnce")
        assertEquals("echo hi", parsedOnce.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `leaves an already single-encoded arguments string untouched`() {
        val singleEncoded = """
            {"messages":[{"role":"assistant","tool_calls":[{"id":"call_1","function":{"name":"bash","arguments":"{\"command\": \"echo hi\"}"},"type":"function"}]}],"model":"deepseek-v4-flash"}
        """.trimIndent()

        assertEquals(
            Json.parseToJsonElement(singleEncoded),
            Json.parseToJsonElement(unwrapDoubleEncodedToolArgs(singleEncoded)),
        )
    }

    @Test
    fun `leaves a request with no tool calls untouched`() {
        val noTools = """{"messages":[{"role":"user","content":"hi"}],"model":"deepseek-v4-flash"}"""

        assertEquals(Json.parseToJsonElement(noTools), Json.parseToJsonElement(unwrapDoubleEncodedToolArgs(noTools)))
    }
}
