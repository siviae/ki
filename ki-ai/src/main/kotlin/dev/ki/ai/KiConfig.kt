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
)
