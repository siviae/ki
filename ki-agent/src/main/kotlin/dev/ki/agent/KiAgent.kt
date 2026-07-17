package dev.ki.agent

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import dev.ki.ai.KiLlm

/**
 * The agent runtime, built on koog's [AIAgent]. Holds the system prompt, model,
 * and tool registry; drives the tool-calling loop via koog. pi's richer semantics
 * (steering/follow-up queues, before/after hooks) are layered on in later milestones.
 *
 * When a [historyProvider] is supplied, koog's chat-memory feature persists and
 * reloads the conversation keyed by the `sessionId` passed to [run] — that is how a
 * session resumes with its prior turns and tool results.
 */
class KiAgent(
    private val llm: KiLlm,
    systemPrompt: String,
    tools: List<ToolBase<*, *>>,
    private val historyProvider: ChatHistoryProvider? = null,
    maxIterations: Int = 50,
) {
    private val registry: ToolRegistry = ToolRegistry {
        tools(tools)
    }

    private val config: AIAgentConfig = AIAgentConfig(
        prompt = prompt("ki") { system(systemPrompt) },
        model = llm.defaultModel.toLLModel(),
        maxAgentIterations = maxIterations,
    )

    private val agent: AIAgent<String, String> = AIAgent(
        promptExecutor = llm.executor,
        agentConfig = config,
        toolRegistry = registry,
        installFeatures = {
            historyProvider?.let { provider ->
                install(ChatMemory) { chatHistoryProvider = provider }
            }
        },
    )

    /**
     * Run one turn: send [input], let the model call tools, return the final text.
     * [sessionId] keys chat-memory persistence — pass the same id to resume a session
     * (koog derives the run id from it); `null` starts a fresh, unpersisted-key run.
     */
    suspend fun run(input: String, sessionId: String? = null): String =
        agent.run(input, sessionId)
}
