package dev.ki.cli

import dev.ki.agent.KiAgent
import dev.ki.agent.context.ContextUsage
import dev.ki.ai.KiLlm
import dev.ki.cli.config.KiSession
import dev.ki.cli.ui.SlashContext

/**
 * Owns the live session state the TUI and slash commands act on: the current
 * [KiAgent], the model, cumulative usage, and cost. A `/model` switch **rebuilds** the
 * agent against a new model while keeping the same `sessionId`, history provider, and
 * usage accumulator — so conversation history (M4) and the running cost total carry
 * across the switch untouched.
 */
class KiController(private val session: KiSession) : SlashContext {

    private var agent: KiAgent = buildAgent(session.llm)

    private fun buildAgent(llm: KiLlm): KiAgent = KiAgent(
        llm = llm,
        systemPrompt = session.systemPrompt,
        tools = session.tools,
        historyProvider = session.historyProvider,
        usageMeter = session.usageMeter,
        checkpointProvider = session.checkpointProvider,
    )

    /** Run a turn under the persistent session id. */
    suspend fun run(input: String): String = agent.run(input, session.sessionId)

    // --- SlashContext ---------------------------------------------------------
    override fun model(): String = agent.modelId
    override fun tools(): List<String> = agent.toolNames
    override fun modelCatalog(): List<String> = session.models.keys.toList()
    override fun configSummary(): String = buildString {
        appendLine("model:   ${agent.modelId}")
        appendLine("baseUrl: ${session.config.baseUrl}")
        appendLine("window:  ${session.config.contextWindow}")
        append("session: ${session.sessionId}")
    }

    // --- Status-line inputs ---------------------------------------------------
    fun currentTool(): String? = agent.currentTool
    fun usage(): ContextUsage? = agent.lastUsage

    /** Estimated running cost in USD, or null if the model isn't priced. */
    fun costUsd(): Double? =
        Pricing.costUsd(agent.modelId, session.usageMeter.inputTokens, session.usageMeter.outputTokens)

    /** Switch the active model (rebuild the agent); returns a status message. */
    fun switchModel(name: String): String {
        val entry = session.models[name]
        val modelId = entry?.id ?: name
        val newConfig = session.config.copy(
            defaultModelId = modelId,
            contextWindow = entry?.contextWindow ?: session.config.contextWindow,
            maxOutputTokens = entry?.maxOutputTokens ?: session.config.maxOutputTokens,
        )
        agent = buildAgent(KiLlm(newConfig))
        return "Switched to $modelId."
    }
}
