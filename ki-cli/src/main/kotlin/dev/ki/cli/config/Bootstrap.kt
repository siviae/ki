package dev.ki.cli.config

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.ki.agent.config.Manifest
import dev.ki.agent.config.ManifestException
import dev.ki.agent.config.ModelEntry
import dev.ki.agent.context.UsageAccumulator
import dev.ki.agent.hooks.InterceptorChain
import dev.ki.agent.tools.Extension
import dev.ki.agent.tools.ScriptTool
import dev.ki.agent.tools.ScriptToolLoader
import dev.ki.agent.tools.builtin.BuiltinTools
import dev.ki.ai.KiConfig
import dev.ki.ai.KiLlm
import dev.ki.cli.store.SqliteCheckpointStore
import dev.ki.cli.store.SqliteSessionStore
import dev.ki.store.StoreChatHistoryProvider
import dev.ki.store.StoreCheckpointProvider
import java.nio.file.Files
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
    /** Extension hooks: wraps tools (done) and the LLM executor (re-applied on `/model`). */
    val interceptors: InterceptorChain,
)

/**
 * Resolves the effective configuration (CLI flag > env > manifest > default) and
 * wires the local, SQLite-backed deployment. The manifest is the tool allowlist:
 * only listed tools are built; an unlisted tool is simply unavailable.
 */
object Bootstrap {
    fun build(args: CliArgs, baseSystemPrompt: String): KiSession {
        val root: Path = args.configPath.toAbsolutePath().parent ?: Path.of(".").toAbsolutePath()
        val loaded = ManifestLoader.load(resolveConfigPaths(args, root))
        val manifest = loaded.manifest

        val config = resolveConfig(args, manifest)
        val llm = KiLlm(config)

        // Extensions contribute both tools and hooks; the chain wraps every tool it targets
        // (builtins, script tools, and extension-contributed tools alike). The executor is
        // wrapped later, per agent build (KiController), so a `/model` rebuild keeps its hooks.
        val loader = ScriptToolLoader()
        val extensions = buildExtensions(manifest, root, loader, loaded.tree)
        val interceptors = InterceptorChain(extensions)
        val extensionTools = extensions.flatMap { it.tools }.map { ScriptTool(it) }
        val tools = (buildTools(manifest, root, loader) + extensionTools).map { interceptors.wrap(it) }
        interceptors.fireSessionStart(root)

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
            interceptors = interceptors,
        )
    }

    /**
     * The manifest files to merge: the primary [CliArgs.configPath] first, then either
     * the explicit [CliArgs.additionalConfigs] or — when none were given — any sibling
     * `ki.*.toml` files auto-discovered next to the primary. Discovered paths are sorted
     * for deterministic error messages.
     */
    private fun resolveConfigPaths(args: CliArgs, root: Path): List<Path> {
        if (args.additionalConfigs.isNotEmpty()) return listOf(args.configPath) + args.additionalConfigs
        val primary = args.configPath.toAbsolutePath().normalize()
        val siblings = try {
            Files.newDirectoryStream(root, "ki.*.toml").use { stream ->
                stream.map { it }.filter { it.toAbsolutePath().normalize() != primary }.sorted()
            }
        } catch (_: Exception) {
            emptyList() // root missing/unreadable — Manifest.load reports the primary as missing
        }
        return listOf(args.configPath) + siblings
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

    private fun buildTools(manifest: Manifest, root: Path, loader: ScriptToolLoader): List<ToolBase<*, *>> {
        if (manifest.tools.isEmpty()) return emptyList()
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

    /**
     * Load each `[extensions.<name>]` script (always a `script` path) into an [Extension], then
     * fill its registered config from the merged manifest [tree]: `section == null` binds the
     * whole root (the config class cherry-picks sections), a name binds that subtree. Jackson
     * deserializes the script-defined config class by reflection — the merged config is already
     * in memory, so no file is re-read.
     */
    private fun buildExtensions(
        manifest: Manifest,
        root: Path,
        loader: ScriptToolLoader,
        tree: ObjectNode,
    ): List<Extension> {
        if (manifest.extensions.isEmpty()) return emptyList()
        return manifest.extensions.map { (name, entry) ->
            val script = entry.script
                ?: throw ManifestException("Extension '$name' requires a `script` path.")
            val file = root.resolve(script).normalize().toFile()
            if (!file.exists()) throw ManifestException(
                "Extension '$name' points to a missing script: $file"
            )
            val extension = loader.loadExtension(file)
            for (req in extension.configRequests) {
                val node = if (req.section == null) tree else tree.get(req.section)
                try {
                    req.fill(ManifestLoader.decode(node, req.type.java))
                } catch (e: Exception) {
                    val where = req.section?.let { "[$it]" } ?: "manifest root"
                    throw ManifestException(
                        "Extension '$name' config ${req.type.simpleName} could not be read from $where: ${e.message}", e
                    )
                }
            }
            extension
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
