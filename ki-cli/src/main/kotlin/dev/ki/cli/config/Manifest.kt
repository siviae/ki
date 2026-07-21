package dev.ki.cli.config

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.node.ObjectNode
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
 *
 * A project may split its manifest across several TOML files (see [load]). They are
 * **deep-union merged**: disjoint keys combine at every depth (e.g. `[tools.a]` in one
 * file, `[tools.b]` in another), but the *same* concrete key defined in two files is a
 * hard error — no last-file-wins, no load-order dependence.
 */
data class Manifest(
    val llm: LlmSection,
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

        /** Load and parse a single manifest [path]. Throws [ManifestException] if missing. */
        fun load(path: Path): Manifest = load(listOf(path))

        /**
         * Load and deep-union-merge [paths] into one manifest. Every path must exist.
         * Two files defining the same concrete key throw a [ManifestException] naming
         * the dotted key path and both files — there is no override or ordering rule.
         */
        fun load(paths: List<Path>): Manifest {
            require(paths.isNotEmpty()) { "load() needs at least one manifest path" }
            for (path in paths) {
                if (!path.exists()) throw ManifestException(
                    "No ki.toml manifest at $path. Create one (see README) or pass --config <path>."
                )
            }

            val merged = mapper.createObjectNode()
            val origin = HashMap<String, Path>() // dotted key path → the file that first set it
            for (path in paths) {
                val tree = try {
                    mapper.readTree(path.readText())
                } catch (e: Exception) {
                    throw ManifestException("Could not parse manifest $path: ${e.message}", e)
                }
                if (tree !is ObjectNode) throw ManifestException(
                    "Manifest $path is not a TOML table (got ${tree.nodeType})."
                )
                mergeInto(merged, tree, path, origin, prefix = "")
            }

            return try {
                mapper.treeToValue(merged, Manifest::class.java)
            } catch (e: Exception) {
                val where = if (paths.size == 1) paths[0].toString() else paths.joinToString(" + ")
                throw ManifestException("Could not build manifest from $where: ${e.message}", e)
            }
        }

        /**
         * Recursively merge [incoming] into [acc]. Disjoint keys union; two objects at
         * the same key recurse; any other same-key collision (scalar/array/type clash)
         * is a config error naming both source files.
         */
        private fun mergeInto(
            acc: ObjectNode,
            incoming: ObjectNode,
            file: Path,
            origin: MutableMap<String, Path>,
            prefix: String,
        ) {
            for ((key, value) in incoming.properties()) {
                val dotted = if (prefix.isEmpty()) key else "$prefix.$key"
                val existing = acc.get(key)
                when {
                    existing == null -> {
                        acc.set<JsonNode>(key, value)
                        origin[dotted] = file
                    }
                    existing is ObjectNode && value is ObjectNode ->
                        mergeInto(existing, value, file, origin, dotted)
                    else -> throw ManifestException(
                        "Duplicate config key '$dotted' — defined in ${firstFile(origin, dotted)} " +
                            "and $file. Each key must live in exactly one file (no overrides)."
                    )
                }
            }
        }

        /** The file that first set [dotted], or its nearest recorded ancestor path. */
        private fun firstFile(origin: Map<String, Path>, dotted: String): Any {
            origin[dotted]?.let { return it }
            var p = dotted
            while (p.contains('.')) {
                p = p.substringBeforeLast('.')
                origin[p]?.let { return it }
            }
            return "an earlier file"
        }
    }
}

data class LlmSection(
    val baseUrl: String,
    /** Name of the env var holding the API key (secret by reference, never inline). */
    val apiKeyEnv: String,
    val model: String,
)

/**
 * [checkpoints] opts into M9 agent-persistence: koog snapshots graph state after each
 * node so a process killed mid-turn resumes from the last node (not just the last
 * completed turn). Off by default — extra write per node; enable for crash-prone runs.
 */
data class DbSection(val path: String = "./.ki/ki.db", val checkpoints: Boolean = false)

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
