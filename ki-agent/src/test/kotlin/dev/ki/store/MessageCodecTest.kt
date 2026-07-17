package dev.ki.store

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Gate 1 (M4): the koog `Message` blob must round-trip the **full tool loop** — an
 * Assistant turn carrying a `Tool.Call` part and a User turn carrying a `Tool.Result`
 * part — through [MessageCodec] with every part surviving. If this fails, resume
 * would lose tool interactions.
 */
class MessageCodecTest {

    @Test fun `round-trips a full tool-calling transcript`() {
        val transcript: List<Message> = listOf(
            Message.System(MessagePart.Text("You are ki."), RequestMetaInfo.Empty),
            Message.User(listOf(MessagePart.Text("list the files")), RequestMetaInfo.Empty),
            Message.Assistant(
                parts = listOf(
                    MessagePart.Text("calling bash"),
                    MessagePart.Tool.Call(id = "call_1", tool = "bash", args = """{"command":"ls"}"""),
                ),
                metaInfo = ResponseMetaInfo.Empty,
            ),
            Message.User(
                parts = listOf(MessagePart.Tool.Result(id = "call_1", tool = "bash", output = "a.txt\nb.txt")),
                metaInfo = RequestMetaInfo.Empty,
            ),
            Message.Assistant(MessagePart.Text("Two files: a.txt and b.txt"), ResponseMetaInfo.Empty),
        )

        for (message in transcript) {
            val restored = MessageCodec.decode(MessageCodec.encode(message))
            assertEquals(message, restored, "message did not survive the codec: $message")
        }
    }

    @Test fun `tool call args and result output survive`() {
        val call = Message.Assistant(
            parts = listOf(MessagePart.Tool.Call(id = "c", tool = "edit", args = """{"path":"x","edits":[]}""")),
            metaInfo = ResponseMetaInfo.Empty,
        )
        val restored = MessageCodec.decode(MessageCodec.encode(call)) as Message.Assistant
        val restoredCall = restored.parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("edit", restoredCall.tool)
        assertEquals("""{"path":"x","edits":[]}""", restoredCall.args)
    }
}
