package dev.ki.cli

import dev.ki.agent.KiAgent
import dev.ki.agent.ToolCallEvent
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

    /**
     * The session id the next turn runs under. `/resume <id>` swaps it in place; koog's
     * chat-memory then reloads that conversation's history on the next `run` (M9). Mutable
     * so a resume needs no agent rebuild — only the key handed to `agent.run` changes.
     */
    private var activeSessionId: String = session.sessionId

    private fun buildAgent(llm: KiLlm): KiAgent = KiAgent(
        llm = llm,
        systemPrompt = session.systemPrompt,
        tools = session.tools,
        historyProvider = session.historyProvider,
        usageMeter = session.usageMeter,
        checkpointProvider = session.checkpointProvider,
        streaming = true,
    )

    /**
     * Run a turn under the active (possibly resumed) session id. [onReasoning] receives the
     * model's streamed reasoning/thinking deltas (M9.1); [onTool] receives tool-call
     * lifecycle events for the transcript's colored tool line (M9.2).
     */
    suspend fun run(input: String, onReasoning: (String) -> Unit, onTool: (ToolCallEvent) -> Unit): String =
        agent.run(input, activeSessionId, onReasoning, onTool)

    /**
     * Live resume. With no [id], list resumable sessions; with an [id], switch the active
     * session in place so the next turn continues that conversation (history reloaded by
     * chat-memory) without a restart. Returns a status line for the transcript.
     */
    fun resume(id: String?): String {
        val sessions = session.store.listSessions()
        if (id == null) {
            if (sessions.isEmpty()) return "No saved sessions to resume."
            val list = sessions.take(10).joinToString("\n") { s ->
                val marker = if (s.conversationId == activeSessionId) " (current)" else ""
                "  ${s.conversationId}  — ${s.messageCount} msgs$marker"
            }
            return "Resumable sessions (newest first):\n$list\n\nSwitch with: /resume <id>"
        }
        val target = sessions.firstOrNull { it.conversationId == id }
            ?: return "No session '$id'. Use /resume to list saved sessions."
        activeSessionId = id
        return "Resumed session $id (${target.messageCount} messages). Your next message continues it."
    }

    // --- SlashContext ---------------------------------------------------------
    override fun model(): String = agent.modelId
    override fun tools(): List<String> = agent.toolNames
    override fun modelCatalog(): List<String> = session.models.keys.toList()
    override fun configSummary(): String = buildString {
        appendLine("model:   ${agent.modelId}")
        appendLine("baseUrl: ${session.config.baseUrl}")
        appendLine("window:  ${session.config.contextWindow}")
        append("session: $activeSessionId")
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
