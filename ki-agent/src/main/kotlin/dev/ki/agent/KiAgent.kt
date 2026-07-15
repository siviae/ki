package dev.ki.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import dev.ki.ai.KiLlm

/**
 * The agent runtime, built on koog's [AIAgent]. Holds the system prompt, model,
 * and tool registry; drives the tool-calling loop via koog. pi's richer semantics
 * (steering/follow-up queues, before/after hooks) are layered on in later milestones.
 */
class KiAgent(
    private val llm: KiLlm,
    systemPrompt: String,
    tools: List<ToolBase<*, *>>,
    maxIterations: Int = 50,
) {
    private val registry: ToolRegistry = ToolRegistry {
        tools(tools)
    }

    private val config: AIAgentConfig = AIAgentConfig(
        prompt = prompt("ki") { system(systemPrompt) },
        model = llm.defaultModel.toLLModel(),
        maxAgentIterations = maxIterations,
    )

    private val agent: AIAgent<String, String> = AIAgent(
        promptExecutor = llm.executor,
        agentConfig = config,
        toolRegistry = registry,
    )

    /** Run one turn: send [input], let the model call tools, return the final text. */
    suspend fun run(input: String): String = agent.run(input)
}
