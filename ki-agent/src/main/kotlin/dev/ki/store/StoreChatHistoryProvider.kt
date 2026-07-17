package dev.ki.store

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message

/**
 * Bridges koog's chat-memory [ChatHistoryProvider] to ki's [SessionStore] SPI — the
 * single place koog `Message`s cross into storage. koog passes `conversationId` =
 * the run's `sessionId`, so resuming a session means running the agent with the same
 * `sessionId` (see `KiAgent.run`).
 */
class StoreChatHistoryProvider(private val store: SessionStore) : ChatHistoryProvider {

    override suspend fun load(conversationId: String): List<Message> =
        store.load(conversationId).map { MessageCodec.decode(it.json) }

    override suspend fun store(conversationId: String, messages: List<Message>) {
        val rows = messages.mapIndexed { i, m ->
            StoredMessage(seq = i, role = m.role.name, json = MessageCodec.encode(m))
        }
        store.save(conversationId, rows)
    }
}
