package dev.ki.ai

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * pi-flavored model metadata. Because every model is served through the LiteLLM
 * proxy (an OpenAI-compatible endpoint), the koog provider is always OpenAI; the
 * `id` is whatever model name LiteLLM is configured to route.
 */
data class KiModel(
    val id: String,
    val displayName: String = id,
    val contextWindow: Long = 128_000,
    val maxOutputTokens: Long = 8_192,
) {
    /** Map to the koog model koog's LLM client understands. */
    fun toLLModel(): LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = id,
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
            // LiteLLM's proxy speaks the OpenAI chat-completions wire protocol, so
            // koog's OpenAI client must target that endpoint (not the Responses API).
            LLMCapability.OpenAIEndpoint.Completions,
        ),
        contextLength = contextWindow,
        maxOutputTokens = maxOutputTokens,
    )
}
