package dev.ki.store

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import dev.ki.ai.KiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The resume spine, end to end: two agent runs sharing one [SessionStore] under the
 * same `sessionId`. Proves the koog chat-memory merge does the right thing —
 * history is reloaded, the new user turn is **not** dropped, and the system prompt is
 * **not** duplicated. A codec round-trip alone would let a resume bug ship green;
 * this test is what actually guards the top M4 acceptance line.
 */
class ResumeIntegrationTest {

    /** Records every prompt it's asked to execute; replays canned assistant replies. */
    private class RecordingExecutor(private val replies: List<String>) : PromptExecutor() {
        val prompts = mutableListOf<List<Message>>()
        private var i = 0
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            prompts.add(prompt.messages)
            val text = replies[i.coerceAtMost(replies.size - 1)]; i++
            return Message.Assistant(MessagePart.Text(text), ResponseMetaInfo.Empty)
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            throw NotImplementedError()
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): List<Message.Assistant> =
            throw NotImplementedError()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw NotImplementedError()
        override fun close() {}
    }

    private class InMemorySessionStore : SessionStore {
        private val rows = mutableMapOf<String, List<StoredMessage>>()
        override fun load(conversationId: String) = rows[conversationId] ?: emptyList()
        override fun save(conversationId: String, messages: List<StoredMessage>) { rows[conversationId] = messages }
        override fun listSessions() = rows.map { SessionInfo(it.key, 0, it.value.size) }
    }

    private fun render(messages: List<Message>): List<String> = messages.map { m ->
        m.role.name + ":" + m.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
    }

    private fun agent(executor: PromptExecutor, provider: StoreChatHistoryProvider): AIAgent<String, String> =
        AIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(
                prompt = prompt("ki") { system("You are ki.") },
                model = KiModel("test-model").toLLModel(),
                maxAgentIterations = 10,
            ),
            toolRegistry = ToolRegistry {},
            installFeatures = { install(ChatMemory) { chatHistoryProvider = provider } },
        )

    @Test fun `second run resumes prior turns without dropping input or duplicating system`() {
        val store = InMemorySessionStore()
        val provider = StoreChatHistoryProvider(store)
        val executor = RecordingExecutor(listOf("a1", "a2"))

        runBlocking {
            agent(executor, provider).run("u1", "S")     // fresh session
            agent(executor, provider).run("u2", "S")     // resume same session id
        }

        // Run 1 saw only the system prompt + first user turn.
        assertEquals(listOf("System:You are ki.", "User:u1"), render(executor.prompts[0]))
        // Run 2 saw the reloaded history + the new user turn, system prompt once.
        assertEquals(
            listOf("System:You are ki.", "User:u1", "Assistant:a1", "User:u2"),
            render(executor.prompts[1]),
        )

        // And the store holds the full transcript after resume: system, u1, a1, u2, a2.
        assertEquals(5, store.load("S").size)
    }
}
