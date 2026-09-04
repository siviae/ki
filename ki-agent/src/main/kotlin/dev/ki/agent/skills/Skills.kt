package dev.ki.agent.skills

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Skill discovery and prompt formatting, byte-compatible with pi's
 * `loadSkills`/`formatSkillsForPrompt` (pi-coding-agent `core/skills.ts`).
 *
 * A skill is a directory with a `SKILL.md` whose YAML frontmatter carries `name`
 * (optional — falls back to the directory name) and `description` (required — a skill
 * without one is skipped, matching pi). `disable-model-invocation: true` skills are
 * loaded but excluded from the prompt (pi parity).
 *
 * The frontmatter parser is deliberately minimal: `key: value` scalars — plain, single-
 * or double-quoted — plus block scalars `>`/`|` with `-`/clip chomping, which is what
 * the repo's SKILL.md files use. Full YAML is out of scope for ki.
 */
data class Skill(
    val name: String,
    val description: String,
    /** Absolute path of the SKILL.md file (what the model `read`s on demand). */
    val filePath: Path,
    /** Directory containing SKILL.md — relative paths in the skill resolve against it. */
    val baseDir: Path,
    val disableModelInvocation: Boolean,
) {
    /** The path relative to [baseDir] that reads as `SKILL.md`. */
    val fileLabel: String get() = "SKILL.md"
}

object Skills {
    const val FILE_NAME = "SKILL.md"

    /**
     * Load one skill per directory path (pi's `loadSkillsFromDir` on a skill root: the
     * directory's own SKILL.md, no recursion). Missing/unparseable/undescribed skills are
     * skipped silently — pi surfaces diagnostics, ki treats the allowlist as curated.
     */
    fun load(paths: List<Path>): List<Skill> = paths.mapNotNull { loadOne(it) }

    fun loadOne(dir: Path): Skill? {
        val file = dir.resolve(FILE_NAME)
        if (!Files.isRegularFile(file)) return null
        val raw = try {
            Files.readString(file)
        } catch (_: Exception) {
            return null
        }
        val (frontmatter, _) = Frontmatter.parse(raw)
        // pi keeps the description verbatim (a folded scalar ends with a clip newline) —
        // only a fully blank one skips the skill
        val description = frontmatter["description"] ?: return null
        if (description.trim().isEmpty()) return null
        val name = frontmatter["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: dir.name
        return Skill(
            name = name,
            description = description,
            filePath = file,
            baseDir = dir,
            disableModelInvocation = frontmatter["disable-model-invocation"]?.trim() == "true",
        )
    }

    /**
     * Byte-compatible port of pi's `formatSkillsForPrompt`: skills with
     * `disable-model-invocation` are excluded; an empty visible set yields "".
     */
    fun formatForPrompt(skills: List<Skill>): String {
        val visible = skills.filter { !it.disableModelInvocation }
        if (visible.isEmpty()) return ""
        val lines = mutableListOf(
            "",
            "",
            "The following skills provide specialized instructions for specific tasks.",
            "Use the read tool to load a skill's file when the task matches its description.",
            "When a skill file references a relative path, resolve it against the skill directory " +
                "(parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.",
            "",
            "<available_skills>",
        )
        for (skill in visible) {
            lines.add("  <skill>")
            lines.add("    <name>${escapeXml(skill.name)}</name>")
            lines.add("    <description>${escapeXml(skill.description)}</description>")
            lines.add("    <location>${escapeXml(skill.filePath.toString())}</location>")
            lines.add("  </skill>")
        }
        lines.add("</available_skills>")
        return lines.joinToString("\n")
    }

    /** Port of pi's escapeXml: & < > " '. */
    fun escapeXml(str: String): String = str
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

/**
 * Minimal YAML frontmatter: the `---` fenced leading block of a markdown file, parsed as
 * flat `key: value` scalars. Block scalars (`>` folded, `|` literal) with `-` (strip) and
 * clip chomping are supported — plain single-line scalars may be single/double-quoted.
 */
object Frontmatter {
    /** Returns (frontmatter keys, body after the closing fence). */
    fun parse(content: String): Pair<Map<String, String>, String> {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        if (!normalized.startsWith("---")) return emptyMap<String, String>() to normalized
        val end = normalized.indexOf("\n---", 3)
        if (end == -1) return emptyMap<String, String>() to normalized
        val yaml = normalized.slice(4 until end)
        val body = normalized.slice(end + 4 until normalized.length).trim()
        return parseYaml(yaml) to body
    }

    private fun parseYaml(yaml: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var key: String? = null
        var blockMode: Char? = null // '>' folded, '|' literal
        var stripChomp = false
        val blockLines = mutableListOf<String>()

        fun flushBlock() {
            val k = key ?: return
            val mode = blockMode ?: return
            val text = if (mode == '>') foldLines(blockLines) else literalLines(blockLines)
            result[k] = if (stripChomp) text.trimEnd('\n') else text
            key = null
            blockMode = null
            stripChomp = false
            blockLines.clear()
        }

        for (line in yaml.lines()) {
            if (key != null && blockMode != null) {
                // Block scalar body: blank lines and lines indented deeper than the key.
                if (line.isBlank() || line.startsWith(" ") || line.startsWith("\t")) {
                    blockLines.add(if (line.isBlank()) "" else line.trimStart(' ', '\t'))
                    continue
                }
                flushBlock()
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf(':')
            if (idx <= 0) continue
            val k = trimmed.substring(0, idx).trim()
            val v = trimmed.substring(idx + 1).trim()
            if (v.startsWith(">") || v.startsWith("|")) {
                key = k
                blockMode = if (v[0] == '>') '>' else '|'
                stripChomp = v.endsWith("-")
                blockLines.clear()
                continue
            }
            result[k] = unquoteScalar(v)
            key = null
        }
        flushBlock()
        return result
    }

    /** Folded scalar: non-empty lines join with a space, blank lines become newlines. */
    private fun foldLines(lines: List<String>): String {
        val out = StringBuilder()
        var pendingBlanks = 0
        var started = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) {
                if (started) pendingBlanks++
                continue
            }
            if (!started) {
                out.append(line)
                started = true
            } else {
                if (pendingBlanks == 0) out.append(' ')
                repeat(pendingBlanks) { out.append('\n') }
                out.append(line)
            }
            pendingBlanks = 0
        }
        // clip chomping: exactly one trailing newline when the block is non-empty
        return if (started) out.toString() + "\n" else ""
    }

    /** Literal scalar: lines kept as-is, joined with newlines, clip chomping. */
    private fun literalLines(lines: List<String>): String {
        val body = lines.joinToString("\n") { it.trim() }
        return if (body.isEmpty()) "" else body + "\n"
    }

    private fun unquoteScalar(v: String): String = when {
        v.length >= 2 && v.startsWith('"') && v.endsWith('"') ->
            v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        v.length >= 2 && v.startsWith('\'') && v.endsWith('\'') ->
            v.substring(1, v.length - 1).replace("''", "'")
        else -> v
    }
}
