package dev.ki.ai

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor

/**
 * The unified LLM API layer. Deliberately thin: LiteLLM's proxy already unifies
 * providers behind one OpenAI-compatible endpoint, so this wraps koog's OpenAI
 * client pointed at the proxy and exposes it as a koog [PromptExecutor] that the
 * agent runtime builds on.
 *
 * The primary constructor builds the LiteLLM-backed executor from [KiConfig]. The
 * secondary [of] factory takes an explicit executor + model — used when embedding ki
 * in a host that already supplies its own executor, and by tests.
 */
class KiLlm private constructor(
    /** koog executor used by ki-agent to construct the agent. */
    val executor: PromptExecutor,
    val defaultModel: KiModel,
) {
    constructor(config: KiConfig) : this(
        executor = RetryingPromptExecutor(
            MultiLLMPromptExecutor(
                DoubleEncodedArgsWorkaroundClient(
                    apiKey = config.apiKey,
                    settings = OpenAIClientSettings(baseUrl = config.baseUrl),
                )
            )
        ),
        defaultModel = KiModel(
            id = config.defaultModelId,
            contextWindow = config.contextWindow,
            maxOutputTokens = config.maxOutputTokens,
        ),
    )

    companion object {
        /** Build from an explicit executor + model (embedding / tests). */
        fun of(executor: PromptExecutor, model: KiModel): KiLlm = KiLlm(executor, model)
    }
}
