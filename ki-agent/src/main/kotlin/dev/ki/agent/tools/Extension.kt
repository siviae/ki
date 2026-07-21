package dev.ki.agent.tools

import ai.koog.prompt.Prompt
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/**
 * The result a `tool_call` interceptor returns for one tool invocation.
 *
 * - [Permit] — run the tool with the current args.
 * - [Block]  — do not run the tool; the [reason] is surfaced to the model as the tool
 *   result (via a koog `ToolException`), so the model sees the denial and can adapt.
 * - [Modify] — run the tool, but with [args] substituted for what the model sent.
 *
 * Multiple `onToolCall` hooks on one tool compose in load order: a [Modify] feeds its
 * args into the next hook; the first [Block] short-circuits.
 */
sealed interface InterceptionResult {
    data object Permit : InterceptionResult
    data class Block(val reason: String) : InterceptionResult
    data class Modify(val args: JsonObject) : InterceptionResult
}

/**
 * A `tool_call` hook. [tools] is the set of tool names it targets; empty or `"*"` means
 * every tool. [fn] receives the tool name and its decoded args and returns an
 * [InterceptionResult].
 */
class ToolCallHook(
    val tools: Set<String>,
    val fn: suspend (name: String, args: ToolArgs) -> InterceptionResult,
) {
    fun appliesTo(name: String): Boolean = tools.isEmpty() || "*" in tools || name in tools
}

/** A `tool_result` hook: post-processes a tool's string output before the model sees it. */
class ToolResultHook(
    val tools: Set<String>,
    val fn: suspend (name: String, result: String) -> String,
) {
    fun appliesTo(name: String): Boolean = tools.isEmpty() || "*" in tools || name in tools
}

/**
 * A `before_provider_request` hook: rewrites the [Prompt] on its way to the LLM (e.g.
 * secret masking). It **must return a new [Prompt]**, never mutate the one it is given —
 * that same object backs persisted chat-memory, so mutating it would corrupt the stored
 * transcript. Kept synchronous so it also applies on the streaming (`Flow`) path.
 */
class ProviderRequestHook(val fn: (Prompt) -> Prompt)

/** A `session_start` hook: runs once when the session is assembled, given the project root. */
class SessionStartHook(val fn: (root: Path) -> Unit)

/**
 * What a `.ki.kts` extension script produces: any mix of tools and lifecycle hooks. A
 * script that ends in a bare `tool("x") { ... }` is lifted into a single-tool, zero-hook
 * extension (see [dev.ki.agent.tools.ScriptToolLoader.loadExtension]), so every existing
 * `[tools.*]` script stays valid.
 */
class Extension(
    val tools: List<ScriptToolSpec> = emptyList(),
    val toolCallHooks: List<ToolCallHook> = emptyList(),
    val toolResultHooks: List<ToolResultHook> = emptyList(),
    val providerRequestHooks: List<ProviderRequestHook> = emptyList(),
    val sessionStartHooks: List<SessionStartHook> = emptyList(),
)

/** DSL builder used inside a `.ki.kts` extension script (the `extension { ... }` block). */
class ExtensionBuilder {
    private val tools = mutableListOf<ScriptToolSpec>()
    private val toolCallHooks = mutableListOf<ToolCallHook>()
    private val toolResultHooks = mutableListOf<ToolResultHook>()
    private val providerRequestHooks = mutableListOf<ProviderRequestHook>()
    private val sessionStartHooks = mutableListOf<SessionStartHook>()

    /** Register a tool, same DSL as a standalone tool script. */
    fun tool(name: String, configure: ToolBuilder.() -> Unit) {
        tools += dev.ki.agent.tools.tool(name, configure)
    }

    /** Intercept calls to [toolNames] (none/`"*"` = all): permit, block, or modify args. */
    fun onToolCall(vararg toolNames: String, block: suspend (name: String, args: ToolArgs) -> InterceptionResult) {
        toolCallHooks += ToolCallHook(toolNames.toSet(), block)
    }

    /** Post-process the string result of [toolNames] (none/`"*"` = all). */
    fun onToolResult(vararg toolNames: String, block: suspend (name: String, result: String) -> String) {
        toolResultHooks += ToolResultHook(toolNames.toSet(), block)
    }

    /** Rewrite the outgoing LLM prompt. Must return a new [Prompt] (never mutate the input). */
    fun onProviderRequest(block: (Prompt) -> Prompt) {
        providerRequestHooks += ProviderRequestHook(block)
    }

    /** Run once at session start with the project root. */
    fun onSessionStart(block: (root: Path) -> Unit) {
        sessionStartHooks += SessionStartHook(block)
    }

    internal fun build(): Extension =
        Extension(tools, toolCallHooks, toolResultHooks, providerRequestHooks, sessionStartHooks)
}

/** Entry point an extension script calls as its final expression. */
fun extension(configure: ExtensionBuilder.() -> Unit): Extension =
    ExtensionBuilder().apply(configure).build()
