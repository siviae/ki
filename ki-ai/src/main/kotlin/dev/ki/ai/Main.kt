package dev.ki.ai

import ai.koog.prompt.dsl.prompt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * Smoke test for the LiteLLM path: stream one prompt and print deltas.
 * Run against a LiteLLM proxy (or any OpenAI-compatible endpoint) via env vars.
 */
fun main() = runBlocking {
    val config = KiConfig(
        baseUrl = requireNotNull(System.getenv("LITELLM_BASE_URL")) { "LITELLM_BASE_URL must be set" },
        apiKey = requireNotNull(System.getenv("LITELLM_API_KEY") ?: System.getenv("OPENAI_API_KEY")) { "LITELLM_API_KEY or OPENAI_API_KEY must be set" },
        defaultModelId = requireNotNull(System.getenv("KI_MODEL")) { "KI_MODEL must be set" },
    )
    val ki = KiLlm(config)
    val model = ki.defaultModel.toLLModel()

    val p = prompt("ki-smoke") {
        system("You are a terse assistant.")
        user("Say hello in five words.")
    }

    println(">>> streaming from ${ki.defaultModel.id}")
    ki.executor.executeStreaming(p, model).collect { frame ->
        print(frame)
    }
    println("\n<<< done")
}
