package dev.ki.agent.hooks

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import dev.ki.agent.tools.Extension
import dev.ki.agent.tools.InterceptionResult
import dev.ki.agent.tools.extension
import dev.ki.ai.KiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InterceptorChainTest {

    /** Records every args object it is executed with; echoes back the `v` arg. */
    private class RecordingTool(name: String, val calls: MutableList<JsonObject> = mutableListOf()) :
        Tool<JsonObject, String>(typeToken<JsonObject>(), typeToken<String>(), ToolDescriptor(name, "test tool", emptyList(), emptyList())) {
        override suspend fun execute(args: JsonObject): String {
            calls += args
            return "ran:" + (args["v"]?.jsonPrimitive?.content.orEmpty())
        }
    }

    private fun args(v: String): JsonObject = buildJsonObject { put("v", v) }

    @Suppress("UNCHECKED_CAST")
    private fun wrap(tool: RecordingTool, vararg exts: Extension): Tool<JsonObject, String> =
        InterceptorChain(exts.toList()).wrap(tool) as Tool<JsonObject, String>

    @Test fun `block short-circuits — delegate never runs and reason surfaces`() {
        val tool = RecordingTool("bash")
        val wrapped = wrap(tool, extension { onToolCall("bash") { _, _ -> InterceptionResult.Block("no rm") } })

        val e = assertFailsWith<ToolException.ValidationFailure> {
            runBlocking { wrapped.execute(args("x")) }
        }
        assertTrue(e.message!!.contains("no rm"))
        assertTrue(tool.calls.isEmpty(), "delegate must not run on a block")
    }

    @Test fun `modify rewrites the args the delegate sees`() {
        val tool = RecordingTool("write")
        val wrapped = wrap(tool, extension {
            onToolCall("write") { _, _ -> InterceptionResult.Modify(buildJsonObject { put("v", "SAFE") }) }
        })

        val out = runBlocking { wrapped.execute(args("DANGER")) }
        assertEquals("ran:SAFE", out)
        assertEquals("SAFE", tool.calls.single()["v"]!!.jsonPrimitive.content)
    }

    @Test fun `two call hooks compose in load order`() {
        val tool = RecordingTool("edit")
        val wrapped = wrap(tool, extension {
            onToolCall("edit") { _, a -> InterceptionResult.Modify(buildJsonObject { put("v", a.string("v") + "1") }) }
            onToolCall("edit") { _, a -> InterceptionResult.Modify(buildJsonObject { put("v", a.string("v") + "2") }) }
        })

        runBlocking { wrapped.execute(args("")) }
        assertEquals("12", tool.calls.single()["v"]!!.jsonPrimitive.content)
    }

    @Test fun `result hook transforms output`() {
        val tool = RecordingTool("read")
        val wrapped = wrap(tool, extension { onToolResult("read") { _, r -> r.uppercase() } })

        assertEquals("RAN:HI", runBlocking { wrapped.execute(args("hi")) })
    }

    @Test fun `a tool no hook targets is returned unwrapped`() {
        val tool = RecordingTool("read")
        val same = InterceptorChain(listOf(extension { onToolCall("bash") { _, _ -> InterceptionResult.Permit } })).wrap(tool)
        assertSame(tool, same, "untargeted tool must not be wrapped")
    }

    // --- provider-request hook ---------------------------------------------------

    /** Captures the prompt it is asked to execute. */
    private class CapturingExecutor : PromptExecutor() {
        var seen: Prompt? = null
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            seen = prompt
            return Message.Assistant(MessagePart.Text("ok"), ResponseMetaInfo.Empty)
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            throw NotImplementedError()
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
            throw NotImplementedError()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw NotImplementedError()
        override fun close() {}
    }

    private fun userText(p: Prompt): String =
        ((p.messages.last() as Message.User).parts.single() as MessagePart.Text).text

    @Test fun `provider hook masks the wire prompt without mutating the original`() {
        val capturing = CapturingExecutor()
        val ext = extension {
            onProviderRequest { pr -> prompt(pr.id) { user(userText(pr).replace("SECRET", "***")) } }
        }
        val wrapped = InterceptorChain(listOf(ext)).wrap(capturing)

        val original = prompt("t") { user("token=SECRET") }
        runBlocking { wrapped.execute(original, KiModel("test-model").toLLModel(), emptyList()) }

        assertEquals("token=***", userText(capturing.seen!!), "wire prompt is masked")
        assertEquals("token=SECRET", userText(original), "persisted prompt is untouched")
    }

    @Test fun `executor with no provider hook is returned unwrapped`() {
        val capturing = CapturingExecutor()
        val same = InterceptorChain(listOf(extension { onToolCall("x") { _, _ -> InterceptionResult.Permit } })).wrap(capturing)
        assertSame(capturing, same)
    }
}
