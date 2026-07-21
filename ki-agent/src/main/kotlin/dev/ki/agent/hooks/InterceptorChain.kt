package dev.ki.agent.hooks

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.agents.core.tools.ToolDescriptor
import dev.ki.agent.tools.Extension
import dev.ki.agent.tools.InterceptionResult
import dev.ki.agent.tools.ProviderRequestHook
import dev.ki.agent.tools.ToolArgs
import dev.ki.agent.tools.ToolCallHook
import dev.ki.agent.tools.ToolResultHook
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/**
 * Assembles every loaded [Extension]'s hooks and applies them at the two
 * strategy-independent seams both koog agent graphs funnel through — the tool objects and
 * the [PromptExecutor] — plus the session-start callback. Wrapping (rather than adding
 * graph nodes) means the interceptors work for the blocking and the streaming strategy
 * alike, present and future (see PLAN `extension-hooks`).
 *
 * Hook order is load order (deterministic from the merged manifest); tools with no hook
 * targeting them, and an executor with no provider hooks, are returned unwrapped.
 */
class InterceptorChain(extensions: List<Extension>) {
    private val callHooks = extensions.flatMap { it.toolCallHooks }
    private val resultHooks = extensions.flatMap { it.toolResultHooks }
    private val providerHooks = extensions.flatMap { it.providerRequestHooks }
    private val sessionStartHooks = extensions.flatMap { it.sessionStartHooks }

    /** True when nothing was registered — lets callers skip wrapping entirely. */
    val isEmpty: Boolean =
        callHooks.isEmpty() && resultHooks.isEmpty() && providerHooks.isEmpty() && sessionStartHooks.isEmpty()

    /** Fire every `onSessionStart` hook once, in load order. */
    fun fireSessionStart(root: Path) = sessionStartHooks.forEach { it.fn(root) }

    /** Wrap [tool] in an [InterceptingTool] iff a call/result hook targets it. */
    fun wrap(tool: ToolBase<*, *>): ToolBase<*, *> {
        val name = tool.descriptor.name
        val calls = callHooks.filter { it.appliesTo(name) }
        val results = resultHooks.filter { it.appliesTo(name) }
        if (calls.isEmpty() && results.isEmpty()) return tool
        // Every ki tool (ScriptTool, EditTool) is Tool<JsonObject, String>; a tool with a
        // different arg type can't be intercepted by JSON-shaped hooks, so pass it through.
        @Suppress("UNCHECKED_CAST")
        val typed = tool as? Tool<JsonObject, String> ?: return tool
        return InterceptingTool(typed, name, calls, results)
    }

    /** Wrap [executor] iff any `onProviderRequest` hook is registered. */
    fun wrap(executor: PromptExecutor): PromptExecutor =
        if (providerHooks.isEmpty()) executor else InterceptingPromptExecutor(executor, providerHooks)
}

/** Thrown by [InterceptingTool] when an `onToolCall` hook blocks the call. */
class ToolBlockedException(reason: String) : RuntimeException(reason)

/**
 * Decorates a `Tool<JsonObject, String>` with the `tool_call` / `tool_result` hooks that
 * target it. koog invokes tools via `ToolBase.executeUnsafe` → the final
 * `Tool.execute(args, metadata)` → this overridden abstract `execute(args)`, so the
 * decorator is guaranteed on the execution path regardless of which agent graph runs.
 *
 * A [InterceptionResult.Block] throws [ToolBlockedException]: koog's generic tool-failure
 * handler catches it, feeds the reason back to the model as the tool result (so the loop
 * recovers), **and** fires `onToolCallFailed` — so a blocked call reads as ERROR in the
 * transcript, never a green success. (A koog `ToolException` is caught on a different path
 * that surfaces no terminal event, which would leave the UI line stuck "pending".)
 */
class InterceptingTool(
    private val delegate: Tool<JsonObject, String>,
    private val toolName: String,
    private val callHooks: List<ToolCallHook>,
    private val resultHooks: List<ToolResultHook>,
) : Tool<JsonObject, String>(delegate.argsType, delegate.resultType, delegate.descriptor, delegate.metadata) {

    override suspend fun execute(args: JsonObject): String {
        var current = args
        for (hook in callHooks) {
            when (val r = hook.fn(toolName, ToolArgs(current))) {
                InterceptionResult.Permit -> {}
                is InterceptionResult.Modify -> current = r.args
                is InterceptionResult.Block -> throw ToolBlockedException(r.reason)
            }
        }
        var out = delegate.execute(current)
        for (hook in resultHooks) out = hook.fn(toolName, out)
        return out
    }
}

/**
 * Decorates a [PromptExecutor] so every LLM call's [Prompt] passes through the
 * `onProviderRequest` hooks first (secret masking, etc.). Hooks return a new [Prompt];
 * the persisted transcript is never mutated. [moderate]/[models]/[close] delegate raw.
 */
class InterceptingPromptExecutor(
    private val delegate: PromptExecutor,
    private val hooks: List<ProviderRequestHook>,
) : PromptExecutor() {

    private fun transform(prompt: Prompt): Prompt = hooks.fold(prompt) { acc, h -> h.fn(acc) }

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        delegate.execute(transform(prompt), model, tools)

    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
        delegate.executeMultipleChoices(transform(prompt), model, tools)

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        delegate.executeStreaming(transform(prompt), model, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override suspend fun models(): List<LLModel> = delegate.models()

    override fun close() = delegate.close()
}
