package dev.ki.agent.config

/**
 * The `ki.toml` manifest — ki's typed configuration model. An **explicit allowlist**: every
 * tool the agent may use is listed under `[tools.*]` (nothing auto-loads), alongside LLM
 * settings, the local DB path, instruction context files, extensions, and a model catalog.
 *
 * This is a **pure-Kotlin model** and carries no serializer: parsing, deep-union merging of
 * multiple files, and deserialization all live in ki-cli's `ManifestLoader` (the only place
 * Jackson runs). Keeping the schema here — in the agent module — lets extensions and the
 * runtime type against config without depending on ki-cli or on a serialization library.
 */
data class Manifest(
    val llm: LlmSection,
    val db: DbSection = DbSection(),
    val context: ContextSection = ContextSection(),
    /** Tool name → entry. Builtins (see `BuiltinTools.NAMES`) need no `script`. */
    val tools: Map<String, ToolEntry> = emptyMap(),
    /**
     * Extension name → entry (each requires a `script`). Unlike a `[tools.*]` entry, an
     * extension script may register lifecycle **hooks** and its own typed config, not just
     * tools. See the `extension-hooks` / `extension-config` milestones.
     */
    val extensions: Map<String, ToolEntry> = emptyMap(),
    /** Optional named model catalog: alias → model metadata. */
    val models: Map<String, ModelEntry> = emptyMap(),
)

data class LlmSection(
    val baseUrl: String,
    /** Name of the env var holding the API key (secret by reference, never inline). */
    val apiKeyEnv: String,
    val model: String,
)

/**
 * [checkpoints] opts into agent-persistence: koog snapshots graph state after each node so a
 * process killed mid-turn resumes from the last node (not just the last completed turn). Off
 * by default — extra write per node; enable for crash-prone runs.
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
 * A `[tools.<name>]` / `[extensions.<name>]` entry. A builtin tool is a bare table
 * (`script == null`); a script tool/extension sets `script = "path/to/x.ki.kts"`. Any
 * remaining keys are the entry's own configuration, captured in [settings] by the loader
 * (e.g. `base_url`, `token_env`).
 */
class ToolEntry(
    var script: String? = null,
    val settings: MutableMap<String, Any?> = LinkedHashMap(),
)

class ManifestException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
