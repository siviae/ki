package dev.ki.agent.tools

import ai.koog.prompt.Prompt
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.reflect.KClass

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
 * A holder for an extension's typed config, filled by the loader from the merged manifest
 * *after* the script compiles and *before* the first turn. Read it via [invoke] inside a hook;
 * reading it in the `extension { }` body (before the fill) is an error.
 */
class ConfigHandle<T : Any> {
    private var value: T? = null
    internal fun fill(v: T) { value = v }
    operator fun invoke(): T = value
        ?: error("extension config read before it was filled — read config only inside hooks")
}

/**
 * A request to bind a manifest subtree into an extension's config [type]. [section] `null` binds
 * the whole manifest root (the class names the top-level sections it wants); a name binds just
 * that subtree. The loader deserializes and calls [fill].
 */
class ConfigRequest @PublishedApi internal constructor(
    val type: KClass<*>,
    val section: String?,
    private val handle: ConfigHandle<*>,
) {
    @Suppress("UNCHECKED_CAST")
    fun fill(value: Any) { (handle as ConfigHandle<Any>).fill(value) }
}

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
    /** Typed config the loader must fill from the merged manifest before the first turn. */
    val configRequests: List<ConfigRequest> = emptyList(),
)

/** DSL builder used inside a `.ki.kts` extension script (the `extension { ... }` block). */
class ExtensionBuilder {
    private val tools = mutableListOf<ScriptToolSpec>()
    private val toolCallHooks = mutableListOf<ToolCallHook>()
    private val toolResultHooks = mutableListOf<ToolResultHook>()
    private val providerRequestHooks = mutableListOf<ProviderRequestHook>()
    private val sessionStartHooks = mutableListOf<SessionStartHook>()
    @PublishedApi internal val configRequests = mutableListOf<ConfigRequest>()

    /** Register a tool, same DSL as a standalone tool script. */
    fun tool(name: String, configure: ToolBuilder.() -> Unit) {
        tools += dev.ki.agent.tools.tool(name, configure)
    }

    /**
     * Declare a typed config the agent fills from the merged manifest. [section] `null` (default)
     * binds the whole manifest root — the config class [T] names the top-level sections it wants
     * (`bash`, `files`, …) and the rest is ignored; a name binds just that subtree. Read the
     * returned handle via `handle()` **inside a hook** (it is filled after the script compiles).
     */
    inline fun <reified T : Any> config(section: String? = null): ConfigHandle<T> {
        val handle = ConfigHandle<T>()
        configRequests += ConfigRequest(T::class, section, handle)
        return handle
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

    fun build(): Extension =
        Extension(tools, toolCallHooks, toolResultHooks, providerRequestHooks, sessionStartHooks, configRequests)
}

/** Entry point an extension script calls as its final expression. */
fun extension(configure: ExtensionBuilder.() -> Unit): Extension =
    ExtensionBuilder().apply(configure).build()
