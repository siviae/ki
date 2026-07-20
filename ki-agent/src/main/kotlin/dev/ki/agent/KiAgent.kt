package dev.ki.agent

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.HistoryCompressionConfig
import ai.koog.agents.ext.agent.singleRunStrategyWithHistoryCompression
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONPrimitive
import dev.ki.agent.context.ContextUsage
import dev.ki.agent.context.KiTokenizer
import dev.ki.agent.context.UsageAccumulator
import dev.ki.ai.KiLlm
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList

/** Lifecycle phase of a tool call, for the transcript's tool-call line (M9.2). */
enum class ToolPhase { STARTING, OK, ERROR }

/**
 * A tool-call lifecycle event surfaced to the UI (M9.2). [id] is koog's tool-call id, so
 * the UI can update the same transcript line as the call moves STARTING → OK / ERROR — the
 * pi-style pending → success/error background stripe. [result] is the tool's own output
 * (OK) or failure message (ERROR); always null on STARTING.
 */
data class ToolCallEvent(val id: String, val name: String, val args: String, val phase: ToolPhase, val result: String? = null)

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
 *
 * **M9 — crash recovery.** When a [checkpointProvider] is supplied, koog's `Persistence`
 * feature snapshots graph state after each node. On a run started with a session id whose
 * last run was **interrupted**, koog rolls the agent back to the latest checkpoint and
 * continues from that node — so a killed process resumes mid-turn, not just from the last
 * completed turn. On clean completion koog writes a tombstone, which deserializes to a
 * no-op restore, so the happy path is unaffected and chat-memory handles history as usual.
 * Default off (null provider); the raw pre-compaction transcript lives in the checkpoint
 * blob even after M6 compaction overwrites the [historyProvider] rows.
 *
 * **M9.1 — streaming reasoning.** When [streaming] is on, the agent drives the tool loop
 * with a streaming LLM node instead of a blocking one: each turn's [StreamFrame]s are
 * collected, the model's reasoning/thinking deltas ([StreamFrame.ReasoningDelta]) are
 * pushed to the per-run [reasoningSink] as they arrive, and the frames are folded back
 * into the same `Message.Assistant` the blocking node would have produced — so the tool
 * loop, M6 compression, and checkpointing are unchanged. Usage is recorded from the
 * folded response inside the node (the streaming path does not fire `onLLMCallCompleted`).
 * Default off, so `ki-cluster` and tests keep the proven blocking path.
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
    private val usageMeter: UsageAccumulator? = null,
    private val checkpointProvider: PersistenceStorageProvider<*>? = null,
    private val streaming: Boolean = false,
) {
    /** Per-run sink for the model's streamed reasoning deltas; set for the duration of [run]. */
    @Volatile
    private var reasoningSink: ((String) -> Unit)? = null

    /** Per-run sink for tool-call lifecycle events (M9.2); set for the duration of [run]. */
    @Volatile
    private var toolSink: ((ToolCallEvent) -> Unit)? = null
    private val tokenizer = KiTokenizer()
    private val window: Long = llm.defaultModel.contextWindow
    private val budgetTokens: Long = maxOf(MIN_BUDGET, (window * contextBudgetRatio).toLong())

    /** The model id this agent talks to (for status / `/model`). */
    val modelId: String = llm.defaultModel.id

    /** Names of the registered tools (for `/tools`). */
    val toolNames: List<String> = tools.map { it.descriptor.name }

    /** Context usage from the most recent completed LLM call, or null before the first. */
    @Volatile
    var lastUsage: ContextUsage? = null
        private set

    /** Tool currently executing (for the status line), or null when idle. */
    @Volatile
    var currentTool: String? = null
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
        usageMeter?.record(meta?.inputTokensCount, meta?.outputTokensCount)
    }

    private val compressionConfig = HistoryCompressionConfig(
        isHistoryTooBig = ::tooBig,
        compressionStrategy = if (compressHistory)
            HistoryCompressionStrategy.FromLastNMessages(keepLastMessages)
        else HistoryCompressionStrategy.NoCompression,
    )

    /**
     * Collect one streaming LLM response: push reasoning deltas to [reasoningSink] as they
     * arrive, fold the frames into the `Message.Assistant` the blocking node would return,
     * append it to the session, and record usage (the streaming path skips `onLLMCallCompleted`).
     */
    private suspend fun ai.koog.agents.core.agent.session.AIAgentLLMWriteSession.streamFold(): Message.Assistant {
        val frames = requestLLMStreaming()
            .onEach { frame -> if (frame is StreamFrame.ReasoningDelta) reasoningSink?.invoke(frame.text.orEmpty()) }
            .toList()
        val response = frames.toMessageResponse()
        appendPrompt { message(response) }
        updateUsage(prompt, response)
        return response
    }

    /**
     * A single-run strategy that mirrors koog's [singleRunStrategyWithHistoryCompression]
     * but streams the two primary LLM calls (initial + post-tool) through [streamFold], so
     * reasoning deltas reach the UI live. The compression branch stays on the blocking path.
     */
    private fun streamingStrategy(): AIAgentGraphStrategy<String, String> =
        strategy<String, String>("single_run_streaming_with_history_compression") {
            val nodeCallLLM by node<String, Message.Assistant>("streamCallLLM") { input ->
                llm.writeSession { appendPrompt { user(input) }; streamFold() }
            }
            val nodeExecuteTool by nodeExecuteTools(parallel = false)
            val nodeSendToolResult by node<ReceivedToolResults, Message.Assistant>("streamSendToolResult") { results ->
                llm.writeSession {
                    appendPrompt { user { results.toolResults.forEach { r -> toolResult(r.toMessagePart()) } } }
                    streamFold()
                }
            }
            val nodeCompressHistory by nodeLLMCompressHistory<ReceivedToolResults>(
                strategy = compressionConfig.compressionStrategy,
                retrievalModel = compressionConfig.retrievalModel,
            )
            val nodeSendCompressedHistory by node<ReceivedToolResults, Message.Assistant> {
                llm.writeSession { requestLLM() }
            }

            edge(nodeStart forwardTo nodeCallLLM)
            edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
            edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })

            edge(nodeExecuteTool forwardTo nodeCompressHistory onCondition { llm.readSession { compressionConfig.isHistoryTooBig(prompt) } })
            edge(nodeExecuteTool forwardTo nodeSendToolResult onCondition { llm.readSession { !compressionConfig.isHistoryTooBig(prompt) } })
            edge(nodeCompressHistory forwardTo nodeSendCompressedHistory)

            edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
            edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
            edge(nodeSendCompressedHistory forwardTo nodeFinish onTextMessage { true })
            edge(nodeSendCompressedHistory forwardTo nodeExecuteTool onToolCalls { true })
        }

    private val agent: AIAgent<String, String> = AIAgent(
        promptExecutor = llm.executor,
        agentConfig = config,
        strategy = if (streaming) streamingStrategy()
        else singleRunStrategyWithHistoryCompression(compressionConfig),
        toolRegistry = registry,
        installFeatures = {
            historyProvider?.let { provider ->
                install(ChatMemory) { chatHistoryProvider = provider }
            }
            checkpointProvider?.let { provider ->
                install(Persistence) {
                    storage = provider
                    enableAutomaticPersistence = true
                }
            }
            install(EventHandler) {
                onLLMCallCompleted { ctx -> updateUsage(ctx.prompt, ctx.response) }
                onToolCallStarting { ctx ->
                    currentTool = ctx.toolName
                    toolSink?.invoke(ToolCallEvent(ctx.toolCallId.orEmpty(), ctx.toolName, argsPreview(ctx.toolArgs.toString()), ToolPhase.STARTING))
                }
                onToolCallCompleted { ctx ->
                    currentTool = null
                    toolSink?.invoke(ToolCallEvent(ctx.toolCallId.orEmpty(), ctx.toolName, argsPreview(ctx.toolArgs.toString()), ToolPhase.OK, resultPreview(ctx.toolResult)))
                }
                onToolCallFailed { ctx ->
                    currentTool = null
                    toolSink?.invoke(ToolCallEvent(ctx.toolCallId.orEmpty(), ctx.toolName, argsPreview(ctx.toolArgs.toString()), ToolPhase.ERROR, ctx.message.takeIf { it.isNotBlank() }))
                }
            }
        },
    )

    /**
     * Run one turn: send [input], let the model call tools, return the final text.
     * [sessionId] keys chat-memory persistence — pass the same id to resume a session
     * (koog derives the run id from it); `null` starts a fresh, unpersisted-key run.
     */
    suspend fun run(
        input: String,
        sessionId: String? = null,
        onReasoning: ((String) -> Unit)? = null,
        onTool: ((ToolCallEvent) -> Unit)? = null,
    ): String {
        reasoningSink = onReasoning
        toolSink = onTool
        return try {
            agent.run(input, sessionId)
        } finally {
            reasoningSink = null
            toolSink = null
        }
    }

    /** Compact a tool's JSON args for a one-line preview: drop the outer braces and
     *  collapse whitespace. Truncation to the viewport is the renderer's job. */
    private fun argsPreview(json: String): String =
        json.trim().removeSurrounding("{", "}").replace(Regex("\\s+"), " ").trim()

    /** The tool's own return value as display text — unwraps a string result, else its JSON form. */
    private fun resultPreview(result: JSONElement?): String? = when (result) {
        null -> null
        is JSONPrimitive -> result.contentOrNull
        else -> result.toString()
    }?.takeIf { it.isNotBlank() }

    private companion object {
        const val SAFETY = 1.25
        const val MIN_BUDGET = 256L
    }
}
