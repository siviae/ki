package dev.ki.agent.tools.builtin

// Ported from pi packages/coding-agent/src/core/tools/edit-diff.ts — the exact-match
// path: BOM stripping, CRLF normalize/restore, per-edit not-found / duplicate /
// empty / overlap / no-change validation, and reverse-order application so offsets
// stay stable. All edits match against the ORIGINAL (LF-normalized) content, never
// incrementally.
//
// Deferred: fuzzy matching (NFKC + smart-quote/dash/space normalization and the
// line-preserving overlay). ki matches exact text only; a fuzzy match in pi becomes
// a not-found here. The unified-diff/patch generation (a rendering concern) is also
// dropped — the tool returns a success count, not a diff.

data class Edit(val oldText: String, val newText: String)

/** Raised for every edit-input failure; carries pi's exact user-facing message. */
class EditError(message: String) : Exception(message)

private fun detectLineEnding(content: String): String {
    val crlf = content.indexOf("\r\n")
    val lf = content.indexOf("\n")
    if (lf == -1 || crlf == -1) return "\n"
    return if (crlf < lf) "\r\n" else "\n"
}

private fun normalizeToLF(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n")

private fun restoreLineEndings(text: String, ending: String): String =
    if (ending == "\r\n") text.replace("\n", "\r\n") else text

private fun countOccurrences(content: String, oldText: String): Int =
    content.split(oldText).size - 1

private fun notFound(path: String, i: Int, total: Int): EditError = EditError(
    if (total == 1) "Could not find the exact text in $path. The old text must match exactly including all whitespace and newlines."
    else "Could not find edits[$i] in $path. The oldText must match exactly including all whitespace and newlines.",
)

private fun duplicate(path: String, i: Int, total: Int, occ: Int): EditError = EditError(
    if (total == 1) "Found $occ occurrences of the text in $path. The text must be unique. Please provide more context to make it unique."
    else "Found $occ occurrences of edits[$i] in $path. Each oldText must be unique. Please provide more context to make it unique.",
)

private fun emptyOldText(path: String, i: Int, total: Int): EditError = EditError(
    if (total == 1) "oldText must not be empty in $path."
    else "edits[$i].oldText must not be empty in $path.",
)

private fun noChange(path: String, total: Int): EditError = EditError(
    if (total == 1) "No changes made to $path. The replacement produced identical content. This might indicate an issue with special characters or the text not existing as expected."
    else "No changes made to $path. The replacements produced identical content.",
)

private data class Match(val editIndex: Int, val index: Int, val length: Int, val newText: String)

/**
 * Apply exact-text [edits] to [rawContent]; returns the new file content with the
 * original BOM and line endings restored. Throws [EditError] on any invalid input.
 */
fun applyEdits(rawContent: String, edits: List<Edit>, path: String): String {
    if (edits.isEmpty()) throw EditError("Edit tool input is invalid. edits must contain at least one replacement.")

    val bom = Char(0xFEFF)
    val hasBom = rawContent.startsWith(bom)
    val content = if (hasBom) rawContent.substring(1) else rawContent
    val ending = detectLineEnding(content)
    val normalized = normalizeToLF(content)
    val normEdits = edits.map { Edit(normalizeToLF(it.oldText), normalizeToLF(it.newText)) }

    normEdits.forEachIndexed { i, e -> if (e.oldText.isEmpty()) throw emptyOldText(path, i, normEdits.size) }

    val matched = ArrayList<Match>()
    normEdits.forEachIndexed { i, e ->
        val idx = normalized.indexOf(e.oldText)
        if (idx < 0) throw notFound(path, i, normEdits.size)
        val occ = countOccurrences(normalized, e.oldText)
        if (occ > 1) throw duplicate(path, i, normEdits.size, occ)
        matched.add(Match(i, idx, e.oldText.length, e.newText))
    }

    matched.sortBy { it.index }
    for (k in 1 until matched.size) {
        val prev = matched[k - 1]
        val cur = matched[k]
        if (prev.index + prev.length > cur.index) {
            throw EditError("edits[${prev.editIndex}] and edits[${cur.editIndex}] overlap in $path. Merge them into one edit or target disjoint regions.")
        }
    }

    var result = normalized
    for (k in matched.indices.reversed()) {
        val m = matched[k]
        result = result.substring(0, m.index) + m.newText + result.substring(m.index + m.length)
    }
    if (result == normalized) throw noChange(path, normEdits.size)

    return (if (hasBom) bom.toString() else "") + restoreLineEndings(result, ending)
}
