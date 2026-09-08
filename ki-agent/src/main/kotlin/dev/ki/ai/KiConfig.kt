package dev.ki.ai

/**
 * Connection config for the LiteLLM proxy. The JVM only ever talks to LiteLLM's
 * OpenAI-compatible HTTP endpoint; LiteLLM owns provider routing and auth.
 */
data class KiConfig(
    val baseUrl: String,
    val apiKey: String,
    val defaultModelId: String,
    /** Model context window (tokens) — drives M6 context-budget/compression. */
    val contextWindow: Long = 128_000,
    val maxOutputTokens: Long = 8_192,
    /** Sampling temperature override; null = provider default. */
    val temperature: Double? = null,
    /** Reasoning effort sent as the raw `reasoning_effort` request field; null = not sent.
     *  A free-form string (not koog's enum) so proxies accepting "xhigh" work. */
    val reasoningEffort: String? = null,
)
