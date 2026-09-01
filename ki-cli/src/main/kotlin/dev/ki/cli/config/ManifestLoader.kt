package dev.ki.cli.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.ki.agent.config.Manifest
import dev.ki.agent.config.ManifestException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** A parsed [Manifest] plus the merged TOML tree it came from (used to fill extension config). */
class LoadedManifest(
    val manifest: Manifest,
    val tree: ObjectNode,
    /** Hat mode only: names listed in the hat manifest's `[tools.*]`, in declaration order —
     *  pi semantics: the hat's list IS the active allowlist (root entries are the no-hat
     *  baseline, not a union). */
    val hatToolNames: List<String>? = null,
    /** Hat mode only: names listed in the hat manifest's `[extensions.*]`. */
    val hatExtensionNames: Set<String>? = null,
    /** Hat mode only: the hat file's raw `[pi]` subtree (skills, hatDescription) — ignored by
     *  the ki runtime itself but consumed by the skills system and the parity harness. */
    val piSection: JsonNode? = null,
)

/**
 * Parses `ki.toml` (and any sibling files) into the pure-Kotlin [Manifest] model. This is the
 * **only** place Jackson runs — the model itself carries no serializer.
 *
 * A project may split its manifest across several TOML files: they are **deep-union merged**
 * (disjoint keys combine at every depth, e.g. `[tools.a]` in one file, `[tools.b]` in another),
 * but the *same* concrete key defined in two files is a hard error — no last-file-wins, no
 * load-order dependence. The merged tree is retained on [LoadedManifest] so extensions can be
 * handed their own config subtree without re-reading anything.
 */
object ManifestLoader {
    val mapper: ObjectMapper = TomlMapper().registerKotlinModule()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** Load and parse a single manifest [path]. Throws [ManifestException] if missing. */
    fun load(path: Path): LoadedManifest = load(listOf(path))

    /**
     * Load and deep-union-merge [paths] into one manifest. Every path must exist. Two files
     * defining the same concrete key throw a [ManifestException] naming the dotted key path and
     * both files — there is no override or ordering rule.
     */
    fun load(paths: List<Path>): LoadedManifest {
        require(paths.isNotEmpty()) { "load() needs at least one manifest path" }
        for (path in paths) {
            if (!path.exists()) throw ManifestException(
                "No ki.toml manifest at $path. Create one (see README) or pass --config <path>."
            )
        }

        val merged = mapper.createObjectNode()
        val origin = HashMap<String, Path>() // dotted key path → the file that first set it
        for (path in paths) {
            val tree = readTree(path)
            mergeInto(merged, tree, path, origin, prefix = "")
        }

        val manifest = buildManifest(merged, if (paths.size == 1) paths[0].toString() else paths.joinToString(" + "))
        // treeToValue can't populate ToolEntry.settings (the `@JsonAnySetter` sink was dropped
        // when the model went Jackson-free) — fill each entry's extra keys from the tree by hand.
        fillEntrySettings(manifest.tools, merged.get("tools"))
        fillEntrySettings(manifest.extensions, merged.get("extensions"))

        return LoadedManifest(manifest, merged)
    }

    /** Deserialize [node] (subtree or the whole tree) into [type]. Missing → an empty table. */
    fun <T> decode(node: JsonNode?, type: Class<T>): T =
        mapper.treeToValue(node ?: mapper.createObjectNode(), type)

    /**
     * Load the project [root] manifest plus a hat manifest (`.pi/hats/<name>/ki.toml`) with
     * **hat-wins** resolution — pi's semantics, not the deep-union of [load]: a concrete key
     * defined in both files takes the HAT's value (e.g. `[llm].model` exists in both), while
     * keys only in the root file are kept. One deliberate exception: `[context].files` are
     * appended (root list first, then the hat's) — a hat extends project context instead of
     * replacing it.
     *
     * The `[pi]` section of the hat file is not part of the [Manifest] model (ki ignores it)
     * but is exposed on the result as [LoadedManifest.piSection]. Same for the hat's
     * `[tools.*]` / `[extensions.*]` name lists — pi treats the hat's tool list as the ACTIVE
     * allowlist, so the caller can filter the merged map down to it.
     */
    fun loadHat(root: Path, hat: Path): LoadedManifest {
        if (!root.exists()) throw ManifestException("No ki.toml manifest at $root.")
        if (!hat.exists()) throw ManifestException("No hat manifest at $hat (expected .pi/hats/<name>/ki.toml).")
        val rootTree = readTree(root)
        val hatTree = readTree(hat)
        val merged = mergeHatWins(rootTree, hatTree)
        normalizeAgentKeys(merged)
        val manifest = buildManifest(merged, "$root + $hat (hat-wins)")
        fillEntrySettings(manifest.tools, merged.get("tools"))
        fillEntrySettings(manifest.extensions, merged.get("extensions"))
        return LoadedManifest(
            manifest,
            merged,
            hatToolNames = (hatTree.get("tools") as? ObjectNode)?.properties()?.map { it.key } ?: emptyList(),
            hatExtensionNames = (hatTree.get("extensions") as? ObjectNode)?.properties()?.map { it.key }?.toSet() ?: emptySet(),
            piSection = hatTree.get("pi"),
        )
    }

    private fun readTree(path: Path): ObjectNode {
        val tree = try {
            mapper.readTree(path.readText())
        } catch (e: Exception) {
            throw ManifestException("Could not parse manifest $path: ${e.message}", e)
        }
        if (tree !is ObjectNode) throw ManifestException("Manifest $path is not a TOML table (got ${tree.nodeType}).")
        return tree
    }

    private fun buildManifest(merged: ObjectNode, where: String): Manifest = try {
        mapper.treeToValue(merged, Manifest::class.java)
    } catch (e: Exception) {
        throw ManifestException("Could not build manifest from $where: ${e.message}", e)
    }

    /**
     * Hat-wins merge: keys only in [hat] are added, keys in both recurse when both are
     * objects and otherwise take the hat's value. `[context].files` concatenates instead.
     */
    private fun mergeHatWins(root: ObjectNode, hat: ObjectNode): ObjectNode {
        val out = root.deepCopy() as ObjectNode
        for ((key, value) in hat.properties()) {
            val existing = out.get(key)
            when {
                existing == null -> out.set<JsonNode>(key, value)
                key == "context" && value is ObjectNode && existing is ObjectNode ->
                    out.set<JsonNode>("context", mergeContext(existing, value))
                existing is ObjectNode && value is ObjectNode ->
                    out.set<JsonNode>(key, mergeHatWins(existing, value))
                else -> out.set<JsonNode>(key, value) // hat wins — scalar, array, or type clash
            }
        }
        return out
    }

    /** `[context]` merge: `files` concatenates (root first, hat second); other keys hat-wins. */
    private fun mergeContext(root: ObjectNode, hat: ObjectNode): ObjectNode {
        val out = mergeHatWins(root, hat)
        val rootFiles = root.get("files")
        val hatFiles = hat.get("files")
        if (rootFiles is ArrayNode || hatFiles is ArrayNode) {
            val files = mapper.createArrayNode()
            rootFiles?.forEach { files.add(it) }
            hatFiles?.forEach { files.add(it) }
            out.set<JsonNode>("files", files)
        }
        return out
    }

    /**
     * Pi hat files spell `[agent].systemPrompt` in camelCase; the manifest model (Jackson
     * SNAKE_CASE) expects `system_prompt`. Accept both spellings. (The agent model stays
     * Jackson-free — the loader owns the rename.)
     */
    private fun normalizeAgentKeys(tree: ObjectNode) {
        val agent = tree.get("agent") as? ObjectNode ?: return
        val camel = agent.get("systemPrompt")
        if (camel != null) {
            agent.set<JsonNode>("system_prompt", camel)
            agent.remove("systemPrompt")
        }
    }

    /** Copy each `[<section>.<name>]` table's keys (except `script`) into the entry's settings. */
    private fun fillEntrySettings(entries: Map<String, dev.ki.agent.config.ToolEntry>, section: JsonNode?) {
        if (section == null) return
        for ((name, entry) in entries) {
            val table = section.get(name) as? ObjectNode ?: continue
            for ((key, value) in table.properties()) {
                if (key == "script") continue
                entry.settings[key] = mapper.convertValue(value, Any::class.java)
            }
        }
    }

    /**
     * Recursively merge [incoming] into [acc]. Disjoint keys union; two objects at the same key
     * recurse; any other same-key collision (scalar/array/type clash) is a config error naming
     * both source files.
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
