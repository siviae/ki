package dev.ki.agent

import dev.ki.ai.KiConfig
import dev.ki.ai.KiLlm
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in end-to-end check that [KiAgent] drives koog against a real OpenAI-compatible
 * HTTP endpoint. Requires a mock/LiteLLM proxy; enable with:
 *
 *   KI_IT=1 KI_IT_BASE_URL=http://127.0.0.1:4010 gradle :ki-agent:test
 */
class KiAgentIntegrationTest {

    @Test
    fun `agent returns the model's reply over the wire`() {
        if (System.getenv("KI_IT") != "1") return // skipped unless explicitly enabled
        val baseUrl = System.getenv("KI_IT_BASE_URL") ?: "http://127.0.0.1:4010"
        val llm = KiLlm(KiConfig(baseUrl = baseUrl, apiKey = "sk-test", defaultModelId = "gpt-4o"))

        val agent = KiAgent(llm, systemPrompt = "You are terse.", tools = emptyList())
        val reply = runBlocking { agent.run("say hi") }

        assertTrue(reply.contains("friend", ignoreCase = true), "unexpected reply: $reply")
    }
}
