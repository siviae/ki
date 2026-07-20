package dev.ki.agent.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import dev.ki.agent.tools.builtin.BuiltinTools
import kotlin.test.Test

class DebugOpenAIClient(apiKey: String, settings: OpenAIClientSettings) :
    OpenAILLMClient(apiKey, settings, KtorKoogHttpClient.Factory()) {

    fun dumpRequest(tools: List<ToolDescriptor>): String {
        val model = LLModel(
            provider = LLMProvider.OpenAI,
            id = "deepseek-v4-flash",
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.OpenAIEndpoint.Completions),
            contextLength = 128_000,
            maxOutputTokens = 8_192,
        )
        val p = prompt("ki") {
            system("You are terse.")
            user("Use the bash tool to run `echo hi` and report the output.")
        }
        val messages = convertPromptToMessages(p, model)
        val llmTools = tools.takeIf { it.isNotEmpty() }?.map { it.toOpenAIChatTool() }
        return serializeProviderChatRequest(
            messages = messages,
            model = model,
            tools = llmTools,
            toolChoice = p.params.toolChoice?.toOpenAIToolChoice(),
            params = p.params,
            stream = false,
        )
    }

    /** Turn-2 body: prior assistant tool_call + tool result appended, as the agent loop replays it. */
    fun dumpTurn2Request(tools: List<ToolDescriptor>): String {
        val model = LLModel(
            provider = LLMProvider.OpenAI,
            id = "deepseek-v4-flash",
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.OpenAIEndpoint.Completions),
            contextLength = 128_000,
            maxOutputTokens = 8_192,
        )
        val p = prompt("ki") {
            system("You are terse.")
            user("Use the bash tool to run `echo hi` and report the output.")
        }.withMessages {
            it + Message.Assistant(
                part = MessagePart.Tool.Call(id = "call_1", tool = "bash", args = """{"command": "echo hi"}"""),
                metaInfo = ResponseMetaInfo.Empty,
            ) + Message.User(
                part = MessagePart.Tool.Result(id = "call_1", tool = "bash", output = "hi"),
                metaInfo = RequestMetaInfo.Empty,
            )
        }
        val messages = convertPromptToMessages(p, model)
        val llmTools = tools.takeIf { it.isNotEmpty() }?.map { it.toOpenAIChatTool() }
        return serializeProviderChatRequest(
            messages = messages,
            model = model,
            tools = llmTools,
            toolChoice = p.params.toolChoice?.toOpenAIToolChoice(),
            params = p.params,
            stream = false,
        )
    }
}

class RequestDumpDiagTest {
    @Test
    fun `dump the real wire request body`() {
        val client = DebugOpenAIClient("sk-test", OpenAIClientSettings(baseUrl = "http://unused"))
        val tools = BuiltinTools.all().map { it.descriptor }
        println("===REQUEST1===")
        println(client.dumpRequest(tools))
        println("===REQUEST2===")
        println(client.dumpTurn2Request(tools))
    }
}
