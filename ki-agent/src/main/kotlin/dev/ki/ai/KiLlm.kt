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
            temperatureOverride(
                config,
                MultiLLMPromptExecutor(
                    DoubleEncodedArgsWorkaroundClient(
                        apiKey = config.apiKey,
                        settings = OpenAIClientSettings(baseUrl = normalizeBaseUrl(config.baseUrl)),
                    )
                ),
            ),
        ),
        defaultModel = KiModel(
            id = config.defaultModelId,
            contextWindow = config.contextWindow,
            maxOutputTokens = config.maxOutputTokens,
        ),
    )

    companion object {
        /** Wrap with the temperature override when `[llm].temperature` is set (else no-op). */
        private fun temperatureOverride(config: KiConfig, executor: PromptExecutor): PromptExecutor =
            config.temperature?.let { TemperatureOverrideExecutor(executor, it) } ?: executor

        /** Build from an explicit executor + model (embedding / tests). */
        fun of(executor: PromptExecutor, model: KiModel): KiLlm = KiLlm(executor, model)

        /**
         * koog appends `v1/chat/completions` to the base URL itself (its default is
         * `https://api.openai.com`, no `/v1`). pi-style configs spell the base URL WITH
         * `/v1` (e.g. `https://host/v1`) — strip the suffix so both spellings hit the same
         * endpoint instead of `/v1v1/chat/completions` (404).
         */
        fun normalizeBaseUrl(url: String): String =
            url.trimEnd('/').removeSuffix("/v1")
    }
}
