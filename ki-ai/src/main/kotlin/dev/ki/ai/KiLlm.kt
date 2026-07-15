package dev.ki.ai

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor

/**
 * The unified LLM API layer. Deliberately thin: LiteLLM's proxy already unifies
 * providers behind one OpenAI-compatible endpoint, so this wraps koog's OpenAI
 * client pointed at the proxy and exposes it as a koog [PromptExecutor] that the
 * agent runtime builds on.
 */
class KiLlm(private val config: KiConfig) {

    val defaultModel: KiModel = KiModel(id = config.defaultModelId)

    private val client: OpenAILLMClient = OpenAILLMClient(
        apiKey = config.apiKey,
        settings = OpenAIClientSettings(baseUrl = config.baseUrl),
    )

    /**
     * koog executor used by ki-agent to construct the agent. A single-client
     * [MultiLLMPromptExecutor] — there is only ever one client (the LiteLLM proxy).
     */
    val executor: PromptExecutor = MultiLLMPromptExecutor(client)

    companion object {
        fun fromEnv(): KiLlm = KiLlm(KiConfig.fromEnv())
    }
}
