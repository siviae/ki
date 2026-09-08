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
    /** Whether the model accepts image parts (Vision capability in koog terms). */
    val vision: Boolean = true,
) {
    /** Map to the koog model koog's LLM client understands. */
    fun toLLModel(): LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = id,
        capabilities = buildList {
            add(LLMCapability.Completion)
            add(LLMCapability.Tools)
            // koog rejects image parts (requireCapability) when the model lacks Vision —
            // declare it per [vision]: vision support is decided by the PROXY/model
            // (qwen3.8-27b accepts image_url blocks), not by this descriptor;
            // non-vision models fail server-side.
            if (vision) add(LLMCapability.Vision.Image)
            // LiteLLM's proxy speaks the OpenAI chat-completions wire protocol, so
            // koog's OpenAI client must target that endpoint (not the Responses API).
            add(LLMCapability.OpenAIEndpoint.Completions)
        },
        contextLength = contextWindow,
        maxOutputTokens = maxOutputTokens,
    )
}
