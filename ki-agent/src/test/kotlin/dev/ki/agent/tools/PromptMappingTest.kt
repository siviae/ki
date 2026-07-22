package dev.ki.agent.tools

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Prompt.mapMessages] must mask every text-bearing span across all message/part types while
 * preserving order, types, ids/names, and metadata — and never mutate the original (which backs
 * persisted chat-memory).
 */
class PromptMappingTest {

    private fun allText(p: Prompt): String = buildString {
        for (m in p.messages) {
            val parts: List<MessagePart> = when (m) {
                is Message.System -> m.parts
                is Message.User -> m.parts
                is Message.Assistant -> m.parts
                else -> emptyList()
            }
            for (part in parts) when (part) {
                is MessagePart.Text -> append(part.text)
                is MessagePart.Tool.Result -> append(part.output)
                is MessagePart.Tool.Call -> append(part.args)
                is MessagePart.Reasoning -> { part.content.forEach { append(it) }; part.summary?.forEach { append(it) } }
                else -> {}
            }
        }
    }

    private val original = Prompt(
        listOf(
            Message.System(listOf(MessagePart.Text("sys SECRET")), RequestMetaInfo.Empty, "s1"),
            Message.User(
                listOf(
                    MessagePart.Text("user SECRET"),
                    MessagePart.Tool.Result("t1", "bash", "out SECRET here", false),
                ),
                RequestMetaInfo.Empty, "u1",
            ),
            Message.Assistant(
                listOf(
                    MessagePart.Text("asst SECRET"),
                    MessagePart.Tool.Call("t1", "bash", """{"k":"SECRET"}"""),
                    MessagePart.Reasoning(listOf("think SECRET"), listOf("sum SECRET"), "", "r1"),
                ),
                ResponseMetaInfo.Empty, "a1",
            ),
        ),
        "pid",
    )

    @Test fun `masks every text span across all message and part types`() {
        val masked = original.mapMessages { it.replace("SECRET", "***") }

        assertFalse(allText(masked).contains("SECRET"), "some span was not masked: ${allText(masked)}")
        assertTrue(allText(masked).contains("***"))
    }

    @Test fun `preserves structure — order, types, ids, names, count`() {
        val masked = original.mapMessages { it.replace("SECRET", "***") }

        assertEquals("pid", masked.id)
        assertEquals(3, masked.messages.size)
        assertTrue(masked.messages[0] is Message.System)
        assertTrue(masked.messages[1] is Message.User)
        assertTrue(masked.messages[2] is Message.Assistant)

        val user = masked.messages[1] as Message.User
        val result = user.parts.filterIsInstance<MessagePart.Tool.Result>().single()
        assertEquals("t1", result.id)
        assertEquals("bash", result.tool)
        assertEquals("out *** here", result.output)

        val asst = masked.messages[2] as Message.Assistant
        val call = asst.parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("t1", call.id)
        assertEquals("bash", call.tool)
        assertEquals("""{"k":"***"}""", call.args, "tool-call args are masked (JSON-safe value replace)")
    }

    @Test fun `does not mutate the original prompt`() {
        original.mapMessages { it.replace("SECRET", "***") }
        assertTrue(allText(original).contains("SECRET"), "original must be untouched")
        assertFalse(allText(original).contains("***"))
    }
}
