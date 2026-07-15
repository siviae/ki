package dev.ki.agent.tools

import java.io.File
import java.security.MessageDigest
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
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
            )
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

    /** Discover and compile every `*.ki.kts` under [dir]. */
    fun loadAll(dir: File): List<ScriptTool> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".ki.kts") }
            .orEmpty()
            .sortedBy { it.name }
            .map { load(it) }
    }

    private fun ResultWithDiagnostics<*>.valueOrThrow(file: File): ScriptToolSpec {
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
        val value = (returnValue as? ResultValue.Value)?.value
        return value as? ScriptToolSpec
            ?: error("Tool script ${file.name} must end with a `tool(\"...\") { ... }` expression")
    }

    companion object {
        private const val CACHE_VERSION = "v1"
        private fun cacheKey(source: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((CACHE_VERSION + source).toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
