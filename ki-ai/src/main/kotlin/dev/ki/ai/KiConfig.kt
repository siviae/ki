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
) {
    companion object {
        /** Resolve from environment, with sensible localhost defaults for a dev LiteLLM proxy. */
        fun fromEnv(): KiConfig = KiConfig(
            baseUrl = System.getenv("LITELLM_BASE_URL") ?: "http://localhost:4000",
            apiKey = System.getenv("LITELLM_API_KEY") ?: System.getenv("OPENAI_API_KEY") ?: "sk-noauth",
            defaultModelId = System.getenv("KI_MODEL") ?: "gpt-4o",
        )
    }
}
