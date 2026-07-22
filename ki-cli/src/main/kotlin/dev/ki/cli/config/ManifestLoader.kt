package dev.ki.cli.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.ki.agent.config.Manifest
import dev.ki.agent.config.ManifestException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** A parsed [Manifest] plus the merged TOML tree it came from (used to fill extension config). */
class LoadedManifest(val manifest: Manifest, val tree: ObjectNode)

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

        val manifest = try {
            mapper.treeToValue(merged, Manifest::class.java)
        } catch (e: Exception) {
            val where = if (paths.size == 1) paths[0].toString() else paths.joinToString(" + ")
            throw ManifestException("Could not build manifest from $where: ${e.message}", e)
        }
        // treeToValue can't populate ToolEntry.settings (the `@JsonAnySetter` sink was dropped
        // when the model went Jackson-free) — fill each entry's extra keys from the tree by hand.
        fillEntrySettings(manifest.tools, merged.get("tools"))
        fillEntrySettings(manifest.extensions, merged.get("extensions"))

        return LoadedManifest(manifest, merged)
    }

    /** Deserialize [node] (subtree or the whole tree) into [type]. Missing → an empty table. */
    fun <T> decode(node: JsonNode?, type: Class<T>): T =
        mapper.treeToValue(node ?: mapper.createObjectNode(), type)

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
