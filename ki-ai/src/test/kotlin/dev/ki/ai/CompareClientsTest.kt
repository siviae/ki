package dev.ki.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class CompareClientsTest {
    @Test
    fun compare() {
        if (System.getenv("KI_IT") != "1") return
        val apiKey = System.getenv("DS_FLASH_KEY")!!
        val settings = OpenAIClientSettings(baseUrl = "https://llm-core-olap.samokat.ru/")
        val model = LLModel(
            provider = LLMProvider.OpenAI,
            id = "deepseek-v4-flash",
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.OpenAIEndpoint.Completions),
            contextLength = 128_000,
            maxOutputTokens = 8_192,
        )
        val p = prompt("ki") { user("say hi in one word") }

        println("=== OLD (vanilla OpenAILLMClient) ===")
        val old = OpenAILLMClient(apiKey = apiKey, settings = settings)
        runCatching { runBlocking { old.execute(p, model, emptyList()) } }
            .onSuccess { println("OK: ${it.textContent()}") }
            .onFailure { println("FAIL: ${it.message}") }

        println("=== NEW (DoubleEncodedArgsWorkaroundClient) ===")
        val new = DoubleEncodedArgsWorkaroundClient(apiKey = apiKey, settings = settings)
        runCatching { runBlocking { new.execute(p, model, emptyList()) } }
            .onSuccess { println("OK: ${it.textContent()}") }
            .onFailure { println("FAIL: ${it.message}") }
    }
}
