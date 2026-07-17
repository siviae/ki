package dev.ki.cli.config

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * The `ki.toml` manifest — the CLI's single, mandatory configuration source. It is
 * an **explicit allowlist**: every tool the agent may use is listed under `[tools.*]`
 * (nothing auto-loads), alongside LLM settings, the local DB path, instruction
 * context files, and an optional model catalog.
 *
 * Parsed with Jackson (our own type — the prod serializer), unlike the koog `Message`
 * blob which uses koog's kotlinx serializer.
 */
data class Manifest(
    val llm: LlmSection = LlmSection(),
    val db: DbSection = DbSection(),
    val context: ContextSection = ContextSection(),
    /** Tool name → entry. Builtins (see `BuiltinTools.NAMES`) need no `script`. */
    val tools: Map<String, ToolEntry> = emptyMap(),
    /** Optional named model catalog: alias → model metadata. */
    val models: Map<String, ModelEntry> = emptyMap(),
) {
    companion object {
        private val mapper = TomlMapper().registerKotlinModule()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        /** Load and parse [path]. Throws [ManifestException] if it is missing. */
        fun load(path: Path): Manifest {
            if (!path.exists()) throw ManifestException(
                "No ki.toml manifest at $path. Create one (see README) or pass --config <path>."
            )
            return try {
                mapper.readValue(path.readText(), Manifest::class.java)
            } catch (e: Exception) {
                throw ManifestException("Could not parse manifest $path: ${e.message}", e)
            }
        }
    }
}

data class LlmSection(
    val baseUrl: String? = null,
    /** Name of the env var holding the API key (secret by reference, never inline). */
    val apiKeyEnv: String? = null,
    val model: String? = null,
)

data class DbSection(val path: String = "./.ki/ki.db")

data class ContextSection(val files: List<String> = emptyList())

data class ModelEntry(
    val id: String,
    val displayName: String? = null,
    val contextWindow: Long = 128_000,
    val maxOutputTokens: Long = 8_192,
)

/**
 * A `[tools.<name>]` entry. A builtin is a bare table (`script == null`); a script
 * tool sets `script = "path/to/tool.ki.kts"`. Any remaining keys are the tool's own
 * configuration, captured in [settings] (e.g. `base_url`, `token_env`).
 */
class ToolEntry {
    var script: String? = null
    val settings: MutableMap<String, Any?> = LinkedHashMap()

    @JsonAnySetter
    fun set(key: String, value: Any?) { settings[key] = value }
}

class ManifestException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
