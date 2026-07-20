package dev.ki.cli.config

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import dev.ki.agent.context.UsageAccumulator
import dev.ki.agent.tools.ScriptToolLoader
import dev.ki.agent.tools.builtin.BuiltinTools
import dev.ki.ai.KiConfig
import dev.ki.ai.KiLlm
import dev.ki.cli.store.SqliteCheckpointStore
import dev.ki.cli.store.SqliteSessionStore
import dev.ki.store.StoreChatHistoryProvider
import dev.ki.store.StoreCheckpointProvider
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Everything a run needs, assembled from CLI args + the `ki.toml` manifest. */
class KiSession(
    val llm: KiLlm,
    val tools: List<ToolBase<*, *>>,
    val systemPrompt: String,
    val store: SqliteSessionStore,
    val historyProvider: StoreChatHistoryProvider,
    val sessionId: String,
    /** M9 checkpoint provider when `[db].checkpoints` is on, else null (recovery off). */
    val checkpointProvider: PersistenceStorageProvider<*>?,
    val oneShotPrompt: String?,
    /** Effective config (base for a `/model` rebuild). */
    val config: KiConfig,
    /** Model catalog (alias → metadata) for `/model <name>`. */
    val models: Map<String, ModelEntry>,
    /** Cumulative token usage, shared across `/model` rebuilds. */
    val usageMeter: UsageAccumulator,
)

/**
 * Resolves the effective configuration (CLI flag > env > manifest > default) and
 * wires the local, SQLite-backed deployment. The manifest is the tool allowlist:
 * only listed tools are built; an unlisted tool is simply unavailable.
 */
object Bootstrap {
    fun build(args: CliArgs, baseSystemPrompt: String): KiSession {
        val manifest = Manifest.load(args.configPath)
        val root: Path = args.configPath.toAbsolutePath().parent ?: Path.of(".").toAbsolutePath()

        val config = resolveConfig(args, manifest)
        val llm = KiLlm(config)
        val tools = buildTools(manifest, root)
        val systemPrompt = buildSystemPrompt(baseSystemPrompt, manifest, root)

        val dbPath = root.resolve(args.dbPath ?: manifest.db.path).normalize()
        val store = SqliteSessionStore(dbPath)
        val provider = StoreChatHistoryProvider(store)
        val sessionId = resolveSessionId(args, store)

        // M9: checkpoints share the session store's SQLite connection (see SqliteCheckpointStore).
        val checkpointProvider = if (manifest.db.checkpoints)
            StoreCheckpointProvider(SqliteCheckpointStore(store.connection)) else null

        return KiSession(
            llm, tools, systemPrompt, store, provider, sessionId, checkpointProvider, args.prompt,
            config = config, models = manifest.models, usageMeter = UsageAccumulator(),
        )
    }

    private fun resolveConfig(args: CliArgs, manifest: Manifest): KiConfig {
        // Model: CLI flag overrides manifest; a name matching a catalog alias resolves to its id.
        val requested = args.model ?: manifest.llm.model
        val entry = manifest.models[requested]
        val modelId = entry?.id ?: requested

        val apiKey = System.getenv(manifest.llm.apiKeyEnv)
            ?: error("Environment variable '${manifest.llm.apiKeyEnv}' (set in [llm].api_key_env) is not set")

        val baseUrl = manifest.llm.baseUrl

        // Catalog metadata (context window / max output) drives M6's context budget;
        // fall back to KiConfig's defaults when the model isn't catalogued.
        val defaults = KiConfig(baseUrl, apiKey, modelId)
        return KiConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            defaultModelId = modelId,
            contextWindow = entry?.contextWindow ?: defaults.contextWindow,
            maxOutputTokens = entry?.maxOutputTokens ?: defaults.maxOutputTokens,
        )
    }

    private fun buildTools(manifest: Manifest, root: Path): List<ToolBase<*, *>> {
        if (manifest.tools.isEmpty()) return emptyList()
        val loader by lazy { ScriptToolLoader() }
        return manifest.tools.map { (name, entry) ->
            when {
                entry.script != null -> {
                    val file = root.resolve(entry.script!!).normalize().toFile()
                    if (!file.exists()) throw ManifestException(
                        "Tool '$name' points to a missing script: $file"
                    )
                    loader.load(file)
                }
                name in BuiltinTools.NAMES -> BuiltinTools.byName(name)!!
                else -> throw ManifestException(
                    "Unknown tool '$name': not a builtin (${BuiltinTools.NAMES.joinToString()}) and no script path given."
                )
            }
        }
    }

    private fun buildSystemPrompt(base: String, manifest: Manifest, root: Path): String {
        if (manifest.context.files.isEmpty()) return base
        val sb = StringBuilder(base)
        for (rel in manifest.context.files) {
            val file = root.resolve(rel).normalize()
            if (!file.exists()) throw ManifestException("Context file not found: $file")
            sb.append("\n\n# ").append(rel).append("\n\n").append(file.readText())
        }
        return sb.toString()
    }

    private fun resolveSessionId(args: CliArgs, store: SqliteSessionStore): String = when {
        args.resume != null -> args.resume
        args.continueLatest -> store.listSessions().firstOrNull()?.conversationId ?: UUID.randomUUID().toString()
        else -> UUID.randomUUID().toString()
    }
}
