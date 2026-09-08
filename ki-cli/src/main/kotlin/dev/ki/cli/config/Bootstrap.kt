package dev.ki.cli.config

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.ki.agent.config.Manifest
import dev.ki.agent.config.ManifestException
import dev.ki.agent.config.ModelEntry
import dev.ki.agent.config.ToolEntry
import dev.ki.agent.context.UsageAccumulator
import dev.ki.agent.skills.Skills
import dev.ki.agent.hooks.InterceptorChain
import dev.ki.agent.tools.Extension
import dev.ki.agent.tools.ScriptTool
import dev.ki.agent.tools.ScriptToolLoader
import dev.ki.agent.tools.builtin.BuiltinTools
import dev.ki.ai.KiConfig
import dev.ki.ai.KiLlm
import dev.ki.cli.store.PiJsonlSessionStore
import dev.ki.store.SessionStore
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
    val store: SessionStore,
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
    /** M6 compression keep-window (manifest `[agent].keep_last_messages`, default 20). */
    val keepLastMessages: Int,
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
        val root: Path = (args.configPath.toAbsolutePath().parent ?: Path.of(".").toAbsolutePath()).normalize()
        val hat = args.hat?.let { HatPaths.resolve(root, it) }
        val loaded = if (hat != null)
            ManifestLoader.loadHat(args.configPath.toAbsolutePath().normalize(), hat)
        else
            ManifestLoader.load(resolveConfigPaths(args, root))
        val manifest = loaded.manifest
        // BEFORE any script compilation: ScriptToolLoader snapshots the context
        // classloader when the compilation config is built (line ~78 below).
        applyJvmClasspath(manifest, root, args)

        // A hat's [agent].systemPrompt replaces the CLI default as the base prompt;
        // [context].files are appended to it by buildSystemPrompt as before.
        val base = manifest.agent.systemPrompt ?: baseSystemPrompt

        val effective = effectiveManifest(manifest, loaded)

        val config = resolveConfig(args, manifest)
        val llm = KiLlm(config)

        // Extensions contribute both tools and hooks; the chain wraps every tool it targets
        // (builtins, script tools, and extension-contributed tools alike). The executor is
        // wrapped later, per agent build (KiController), so a `/model` rebuild keeps its hooks.
        val loader = ScriptToolLoader()
        val extensions = buildExtensions(effective, root, loader, loaded.tree)
        val interceptors = InterceptorChain(extensions)
        val extensionTools = extensions.flatMap { it.tools }.map { ScriptTool(it) }
        val tools = (buildTools(effective, root, loader) + extensionTools).map { interceptors.wrap(it) }
        interceptors.fireSessionStart(root)

        val skillsBlock = if (loaded.piSection != null) {
            val names = loaded.piSection.get("skills")?.map { it.asText() } ?: emptyList()
            val paths = names.map { root.resolve(".pi/all-skills").resolve(it) }.filter { Files.exists(it) }
            Skills.formatForPrompt(Skills.load(paths))
        } else ""
        val systemPrompt = buildSystemPrompt(base, manifest, root, skillsBlock)

        // Single store: pi-format JSONL, cross-resumable with pi. Session dir:
        // explicit args.dbPath wins (test/CI hook), then [db].path (manifest override),
        // otherwise sessions land where pi puts them.
        val (providerName, apiName) = resolvePiProvider(loaded.tree, config.defaultModelId)
        val dir = when {
            args.dbPath != null -> Path.of(args.dbPath).normalize()
            !manifest.db.path.isNullOrBlank() -> root.resolve(manifest.db.path).normalize()
            else -> Path.of(System.getProperty("user.home"), ".pi", "agent", "sessions")
                .resolve(PiJsonlSessionStore.slugFor(root))
        }
        val store: SessionStore =
            PiJsonlSessionStore(dir, root, providerName, apiName, defaultModel = config.defaultModelId)
        val provider = StoreChatHistoryProvider(store)
        val sessionId = resolveSessionId(args, store)

        // CLI ships no checkpoint store (pi-jsonl only); embedded hosts may still supply
        // one via KiSession.checkpointProvider (M9/M10 SPI, see CheckpointStore).
        val checkpointProvider: PersistenceStorageProvider<*>? = null

        return KiSession(
            llm, tools, systemPrompt, store, provider, sessionId, checkpointProvider, args.prompt,
            config = config, models = manifest.models, usageMeter = UsageAccumulator(),
            keepLastMessages = manifest.agent.keepLastMessages ?: 20,
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

    /**
     * Extend the script classpath from `[jvm].classpath`. Entries are resolved against
     * the manifest root; a trailing slash-star globs every jar in the directory. The
     * resulting URLClassLoader becomes the thread's contextClassLoader — ScriptToolLoader's
     * `dependenciesFromCurrentContext(wholeClasspath = true)` then hands the KB tool
     * libs to compiled `.ki.kts` scripts (and to their runtime class references).
     * Irreversible by design: the CLI is a single-purpose process.
     */
    private fun applyJvmClasspath(manifest: Manifest, root: Path, args: CliArgs) {
        val entries = manifest.jvm.classpath
        if (entries.isEmpty()) return
        val urls = entries.flatMap { spec ->
            val resolved = root.resolve(spec.removeSuffix("/*")).normalize()
            if (spec.endsWith("/*")) {
                Files.newDirectoryStream(resolved, "*.jar").use { stream ->
                    stream.map { it.toUri().toURL() }.sortedBy { it.toString() }
                }
            } else {
                listOf(resolved.toUri().toURL())
            }
        }
        if (urls.isEmpty()) return
        val loader = java.net.URLClassLoader(
            urls.toTypedArray(),
            Thread.currentThread().contextClassLoader,
        )
        Thread.currentThread().contextClassLoader = loader
        System.err.println("ki: script classpath extended with ${urls.size} entr${if (urls.size == 1) "y" else "ies"} ([jvm].classpath)")
    }

    private fun resolveConfig(args: CliArgs, manifest: Manifest, env: (String) -> String? = System::getenv): KiConfig =
        resolveConfigParts(args, manifest, env).let { r ->
            val defaults = KiConfig(r.baseUrl, r.apiKey, r.modelId)
            KiConfig(
                baseUrl = r.baseUrl,
                apiKey = r.apiKey,
                defaultModelId = r.modelId,
                contextWindow = r.entry?.contextWindow ?: defaults.contextWindow,
                maxOutputTokens = r.entry?.maxOutputTokens ?: defaults.maxOutputTokens,
                temperature = manifest.llm.temperature,
                reasoningEffort = manifest.llm.reasoningEffort,
            )
        }

    /**
     * The env/manifest resolution shared by [resolveConfig] and [resolveForPrint]:
     * model = CLI flag > `KI_MODEL` env > manifest (a catalog alias resolves to its id);
     * base URL = `LITELLM_BASE_URL` env > manifest; API key = the manifest-declared env var,
     * then the generic `LITELLM_API_KEY` fallback (README: "if the proxy requires a key").
     * [env] is injectable for tests. [ResolvedModel.entry] is the catalog entry found under
     * the ALIAS (its contextWindow/maxOutputTokens feed the M6 context budget).
     */
    internal data class ResolvedModel(
        val modelId: String,
        val apiKey: String,
        val baseUrl: String,
        val entry: ModelEntry?,
    )

    internal fun resolveConfigParts(
        args: CliArgs,
        manifest: Manifest,
        env: (String) -> String?,
    ): ResolvedModel {
        val requested = args.model
            ?: env("KI_MODEL")?.takeIf { it.isNotBlank() }
            ?: manifest.llm.model
        val entry = manifest.models[requested]
        val modelId = entry?.id ?: requested

        val apiKey = env(manifest.llm.apiKeyEnv)
            ?: env("LITELLM_API_KEY")?.takeIf { it.isNotBlank() }
            ?: error("Environment variable '${manifest.llm.apiKeyEnv}' (set in [llm].api_key_env) is not set")

        val baseUrl = env("LITELLM_BASE_URL")?.takeIf { it.isNotBlank() }
            ?: manifest.llm.baseUrl
        return ResolvedModel(modelId, apiKey, baseUrl, entry)
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

    /**
     * pi's hats.ts prompt assembly, byte-compatible: parts = [base prompt, ...include file
     * contents (trimmed, no headers)], then the skills block — joined with a blank line.
     * (ki used to prefix each context file with `# <rel>`; the pi-parity milestone M1.2
     * dropped the headers so bot sessions get the exact same prompt under both runtimes.)
     */
    private fun buildSystemPrompt(base: String, manifest: Manifest, root: Path, skillsBlock: String): String {
        val parts = mutableListOf(base)
        for (rel in manifest.context.files) {
            val file = root.resolve(rel).normalize()
            if (!file.exists()) throw ManifestException("Context file not found: $file")
            val text = Files.readString(file).trim()
            if (text.isNotEmpty()) parts.add(text)
        }
        if (skillsBlock.isNotEmpty()) parts.add(skillsBlock)
        return parts.joinToString("\n\n")
    }

    private fun resolveSessionId(args: CliArgs, store: SessionStore): String = when {
        args.sessionId != null -> args.sessionId!! // RPC caller's id (pi --session-id parity)
        args.resume != null -> args.resume
        args.continueLatest -> store.listSessions().firstOrNull()?.conversationId ?: UUID.randomUUID().toString()
        else -> UUID.randomUUID().toString()
    }

    /**
     * Resolve the configuration the way [build] would (hat merge, env precedence, hat tool
     * filtering) and return it as a JSON node — no LLM key required, no tools built. Used by
     * `--print-resolved` and by the config-parity harness (ki vs pi's hats.ts).
     */
    fun resolveForPrint(args: CliArgs, env: (String) -> String? = System::getenv): ObjectNode {
        val root: Path = (args.configPath.toAbsolutePath().parent ?: Path.of(".").toAbsolutePath()).normalize()
        val hat = args.hat?.let { HatPaths.resolve(root, it) }
        val loaded = if (hat != null)
            ManifestLoader.loadHat(args.configPath.toAbsolutePath().normalize(), hat)
        else
            ManifestLoader.load(resolveConfigPaths(args, root))
        val manifest = loaded.manifest
        val effective = effectiveManifest(manifest, loaded)

        val resolved = resolveConfigParts(args, manifest, env)
        val skillNames = loaded.piSection?.get("skills")?.map { it.asText() } ?: emptyList()
        val skillPaths = skillNames.map { root.resolve(".pi/all-skills").resolve(it) }.filter { Files.exists(it) }
        val skillsBlock = Skills.formatForPrompt(Skills.load(skillPaths))
        val resolvedSystemPrompt = buildSystemPrompt(
            manifest.agent.systemPrompt ?: "", manifest, root, skillsBlock,
        )

        return ManifestLoader.mapper.createObjectNode().apply {
            put("hat", args.hat)
            put("model", resolved.modelId)
            put("baseUrl", resolved.baseUrl)
            put("apiKeyEnv", manifest.llm.apiKeyEnv)
            put("apiKeySet", env(manifest.llm.apiKeyEnv) != null ||
                !env("LITELLM_API_KEY").isNullOrBlank())
            put("dbPath", manifest.db.path)
            put("systemPrompt", manifest.agent.systemPrompt)
            put("hatDescription", loaded.piSection?.get("hatDescription")?.asText())
            set<JsonNode>("contextFiles", ManifestLoader.mapper.valueToTree(manifest.context.files))
            // declaration order (pi preserves it; the parity harness diffs exact arrays)
            set<JsonNode>("toolNames", ManifestLoader.mapper.valueToTree(effective.tools.keys.toList()))
            set<JsonNode>("extensionNames", ManifestLoader.mapper.valueToTree(effective.extensions.keys.toList()))
            set<JsonNode>("skills", ManifestLoader.mapper.valueToTree(skillNames))
            // fully assembled prompt (base + context files + skills block) — the byte-parity
            // harness target for M1.2; null without a hat (the CLI default base is passed in,
            // not known to the print view)
            put("resolvedSystemPrompt", if (manifest.agent.systemPrompt == null) null else resolvedSystemPrompt)
        }
    }

    /**
     * pi semantics: with a hat active, the hat's `[tools.*]` list IS the allowlist — root
     * entries are the no-hat baseline, not a union partner. Hat extensions likewise replace
     * the list (both repos keep the union at the extension-config level).
     */
    private fun effectiveManifest(manifest: Manifest, loaded: LoadedManifest): Manifest =
        if (loaded.hatToolNames != null) manifest.copy(
            tools = filterToHatTools(manifest.tools, loaded.hatToolNames),
            extensions = if (loaded.hatExtensionNames != null)
                manifest.extensions.filterKeys { it in loaded.hatExtensionNames }
            else manifest.extensions,
        ) else manifest

    /**
     * pi provider identity for the model: scan `[pi].llmProviders` (ki ignores it for
     * runtime purposes but the provider/api names go into pi-format session entries).
     * Fallback: ("ki", "openai-completions").
     */
    private fun resolvePiProvider(tree: ObjectNode, modelId: String): Pair<String, String> {
        val providers = tree.get("pi")?.get("llmProviders") as? ArrayNode ?: return "ki" to "openai-completions"
        for (p in providers) {
            val models = p.get("models") as? ArrayNode ?: continue
            if (models.any { it.get("id")?.asText() == modelId }) {
                val name = p.get("name")?.asText() ?: p.get("baseUrl")?.asText() ?: "ki"
                val api = p.get("api")?.asText() ?: "openai-completions"
                return name to api
            }
        }
        return "ki" to "openai-completions"
    }

    /** Filter the merged tool map down to the hat's list, ordered by the hat's declaration
     *  order (pi preserves it, and the parity harness diffs exact arrays). Script/builtin
     *  validation stays in [buildTools] — a hat entry without a `script` surfaces there. */
    private fun filterToHatTools(
        tools: Map<String, ToolEntry>,
        hatToolNames: List<String>,
    ): Map<String, ToolEntry> {
        val ordered = LinkedHashMap<String, ToolEntry>()
        for (name in hatToolNames) tools[name]?.let { ordered[name] = it }
        return ordered
    }
}

/** Location of a named hat's manifest inside the project root. */
object HatPaths {
    fun resolve(root: Path, name: String): Path {
        if (name.contains('/') || name.contains("\\") || name == "..") throw ManifestException(
            "Invalid hat name '$name'."
        )
        return root.resolve(".pi").resolve("hats").resolve(name).resolve("ki.toml").normalize()
    }
}
