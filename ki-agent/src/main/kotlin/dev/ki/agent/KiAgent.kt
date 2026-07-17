package dev.ki.agent

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.HistoryCompressionConfig
import ai.koog.agents.ext.agent.singleRunStrategyWithHistoryCompression
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import dev.ki.agent.context.ContextUsage
import dev.ki.agent.context.KiTokenizer
import dev.ki.ai.KiLlm

/**
 * The agent runtime, built on koog's [AIAgent]. Holds the system prompt, model,
 * and tool registry; drives the tool-calling loop via koog. pi's richer semantics
 * (steering/follow-up queues, before/after hooks) are layered on in later milestones.
 *
 * When a [historyProvider] is supplied, koog's chat-memory feature persists and
 * reloads the conversation keyed by the `sessionId` passed to [run] — that is how a
 * session resumes with its prior turns and tool results.
 *
 * **M6 — context/token management.** When [compressHistory] is on, the agent uses
 * koog's history-compression strategy: after a tool step, if the prompt exceeds the
 * context budget (a fraction [contextBudgetRatio] of the model window), older turns
 * are summarized into a TL;DR — the system prompt and the most recent
 * [keepLastMessages] messages are kept. [lastUsage] exposes the latest turn's
 * context usage for display.
 */
class KiAgent(
    private val llm: KiLlm,
    systemPrompt: String,
    tools: List<ToolBase<*, *>>,
    private val historyProvider: ChatHistoryProvider? = null,
    maxIterations: Int = 50,
    private val compressHistory: Boolean = true,
    keepLastMessages: Int = 20,
    contextBudgetRatio: Double = 0.7,
) {
    private val tokenizer = KiTokenizer()
    private val window: Long = llm.defaultModel.contextWindow
    private val budgetTokens: Long = maxOf(MIN_BUDGET, (window * contextBudgetRatio).toLong())

    /** Context usage from the most recent completed LLM call, or null before the first. */
    @Volatile
    var lastUsage: ContextUsage? = null
        private set

    private val registry: ToolRegistry = ToolRegistry { tools(tools) }

    private val config: AIAgentConfig = AIAgentConfig(
        prompt = prompt("ki") { system(systemPrompt) },
        model = llm.defaultModel.toLLModel(),
        maxAgentIterations = maxIterations,
    )

    // Regex estimate undercounts real BPE, so bias the trigger up by SAFETY.
    private fun tooBig(prompt: Prompt): Boolean =
        compressHistory && tokenizer.estimate(prompt) * SAFETY > budgetTokens

    private fun updateUsage(prompt: Prompt, response: Message.Assistant?) {
        val meta = response?.metaInfo
        val reported = meta?.totalTokensCount ?: meta?.inputTokensCount
        lastUsage = if (reported != null) ContextUsage(reported, window, reported = true)
        else ContextUsage(tokenizer.estimate(prompt), window, reported = false)
    }

    private val agent: AIAgent<String, String> = AIAgent(
        promptExecutor = llm.executor,
        agentConfig = config,
        strategy = singleRunStrategyWithHistoryCompression(
            HistoryCompressionConfig(
                isHistoryTooBig = ::tooBig,
                compressionStrategy = if (compressHistory)
                    HistoryCompressionStrategy.FromLastNMessages(keepLastMessages)
                else HistoryCompressionStrategy.NoCompression,
            )
        ),
        toolRegistry = registry,
        installFeatures = {
            historyProvider?.let { provider ->
                install(ChatMemory) { chatHistoryProvider = provider }
            }
            install(EventHandler) {
                onLLMCallCompleted { ctx -> updateUsage(ctx.prompt, ctx.response) }
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

    private companion object {
        const val SAFETY = 1.25
        const val MIN_BUDGET = 256L
    }
}
