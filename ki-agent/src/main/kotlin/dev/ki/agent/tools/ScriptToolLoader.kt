package dev.ki.agent.tools

import java.io.File
import java.security.MessageDigest
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/** Script definition: a `.kts` file whose last expression is `tool("name") { ... }`. */
@KotlinScript(fileExtension = "ki.kts")
abstract class ToolScript

/**
 * Compiles `.kts` tool scripts to koog tools on startup.
 *
 * Compilation is cached on disk keyed by a hash of the script source (+ this
 * loader's cache version): unchanged tools skip the multi-second Kotlin compiler
 * cold start on subsequent launches. Scripts run with full JVM privileges — same
 * trust posture as a shell tool.
 */
class ScriptToolLoader(
    private val cacheDir: File = File(".ki/cache/tools"),
) {
    private val host: BasicJvmScriptingHost = run {
        cacheDir.mkdirs()
        val cache = CompiledScriptJarsCache { script, _ ->
            File(cacheDir, cacheKey(script.text) + ".jar")
        }
        val hostConfig = ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
            jvm { compilationCache(cache) }
        }
        BasicJvmScriptingHost(hostConfig, JvmScriptCompiler(hostConfig))
    }

    private val compilationConfig: ScriptCompilationConfiguration =
        createJvmCompilationConfigurationFromTemplate<ToolScript> {
            defaultImports(
                "dev.ki.agent.tools.*",
                "kotlinx.serialization.json.*",
                // For `onProviderRequest { prompt -> ... }` in extension scripts.
                "ai.koog.prompt.Prompt",
                "ai.koog.prompt.dsl.prompt",
                "ai.koog.prompt.message.*",
            )
            // Match ki-agent's target so inline DSL fns (e.g. `config<T>()`) inline into scripts;
            // the scripting host otherwise defaults to JVM 1.8 and rejects the JVM-21 bytecode.
            compilerOptions("-jvm-target", "21")
            jvm { dependenciesFromCurrentContext(wholeClasspath = true) }
        }

    /** Compile+evaluate one script file into a [ScriptTool]. Throws on failure. */
    fun load(file: File): ScriptTool {
        val result = host.eval(
            file.toScriptSource(),
            compilationConfig,
            ScriptEvaluationConfiguration(),
        )
        val spec = result.valueOrThrow(file)
        return ScriptTool(spec)
    }

    /**
     * Compile+evaluate one script into an [Extension]. A script ending in
     * `extension { ... }` yields its tools + hooks; a script ending in a bare
     * `tool("...") { ... }` is lifted into a single-tool, zero-hook extension, so a
     * plain tool script is a valid extension too. Throws on failure.
     */
    fun loadExtension(file: File): Extension {
        val result = host.eval(
            file.toScriptSource(),
            compilationConfig,
            ScriptEvaluationConfiguration(),
        )
        return when (val value = result.returnValueOrThrow(file)) {
            is Extension -> value
            is ScriptToolSpec -> Extension(tools = listOf(value))
            else -> error(
                "Extension script ${file.name} must end with an `extension { ... }` " +
                    "or a `tool(\"...\") { ... }` expression"
            )
        }
    }

    /** Discover and compile every `*.ki.kts` under [dir]. */
    fun loadAll(dir: File): List<ScriptTool> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".ki.kts") }
            .orEmpty()
            .sortedBy { it.name }
            .map { load(it) }
    }

    private fun ResultWithDiagnostics<*>.valueOrThrow(file: File): ScriptToolSpec =
        returnValueOrThrow(file) as? ScriptToolSpec
            ?: error("Tool script ${file.name} must end with a `tool(\"...\") { ... }` expression")

    /** The script's final-expression value, or throw a rendered compile error. May be null. */
    private fun ResultWithDiagnostics<*>.returnValueOrThrow(file: File): Any? {
        val evaluated = when (this) {
            is ResultWithDiagnostics.Success -> value
            is ResultWithDiagnostics.Failure -> {
                val msg = reports
                    .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
                    .joinToString("\n") { it.render() }
                error("Failed to compile tool script ${file.name}:\n$msg")
            }
        }
        val returnValue = (evaluated as kotlin.script.experimental.api.EvaluationResult).returnValue
        return (returnValue as? ResultValue.Value)?.value
    }

    companion object {
        private const val CACHE_VERSION = "v2" // bumped: JVM target 21 + extension config DSL
        private fun cacheKey(source: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((CACHE_VERSION + source).toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
