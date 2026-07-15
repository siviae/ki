package dev.ki.ai

import ai.koog.prompt.dsl.prompt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * Smoke test for the LiteLLM path: stream one prompt and print deltas.
 * Run against a LiteLLM proxy (or any OpenAI-compatible endpoint) via env vars.
 */
fun main() = runBlocking {
    val ki = KiLlm.fromEnv()
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
