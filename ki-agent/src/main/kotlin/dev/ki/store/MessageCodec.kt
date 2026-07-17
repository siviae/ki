package dev.ki.store

import ai.koog.prompt.message.Message
import kotlinx.serialization.json.Json

/**
 * koog `Message` ⇄ JSON string. The **only** code that touches koog's serialization.
 *
 * koog `Message` is a kotlinx `@Serializable` sealed type (System/User/Assistant, with
 * tool calls/results riding inside as `MessagePart.Tool.Call` / `.Result`). It is
 * serialized with koog's native kotlinx `Json` — not Jackson — because the sealed
 * polymorphism is kotlinx's contract; the produced string is stored opaquely as
 * `message_json` and never re-parsed by the store. (Our own config/manifest/catalog
 * types use Jackson; this one blob is the deliberate exception.)
 */
object MessageCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(message: Message): String = json.encodeToString(Message.serializer(), message)

    fun decode(text: String): Message = json.decodeFromString(Message.serializer(), text)
}
