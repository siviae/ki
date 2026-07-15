package dev.ki.agent.tools.builtin

// Ported from pi packages/coding-agent/src/core/tools/truncate.ts.
// Two independent limits — whichever is hit first wins: line count and byte count.
// truncateHead keeps the first N (file reads); truncateTail keeps the last N (bash
// output — you want the end where errors land). Never returns partial lines except
// the tail edge case where a single last line already exceeds the byte budget.

const val DEFAULT_MAX_LINES = 2000
const val DEFAULT_MAX_BYTES = 50 * 1024 // 50KB

data class TruncationResult(
    val content: String,
    val truncated: Boolean,
    /** "lines", "bytes", or null. */
    val truncatedBy: String?,
    val totalLines: Int,
    val totalBytes: Int,
    val outputLines: Int,
    val outputBytes: Int,
    /** Only for the tail edge case: the last line alone exceeded the byte budget. */
    val lastLinePartial: Boolean,
    /** For head truncation: the first line alone exceeded the byte budget. */
    val firstLineExceedsLimit: Boolean,
    val maxLines: Int,
    val maxBytes: Int,
)

/** Human-readable byte size (matches pi's formatSize). */
fun formatSize(bytes: Int): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
    else -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
}

private fun utf8Len(s: String): Int = s.toByteArray(Charsets.UTF_8).size

/** Split into lines for counting, dropping a single trailing empty from a final "\n". */
private fun splitLinesForCounting(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val lines = content.split("\n").toMutableList()
    if (content.endsWith("\n")) lines.removeAt(lines.size - 1)
    return lines
}

/** Keep the first lines/bytes that fit. Suitable for file reads. */
fun truncateHead(
    content: String,
    maxLines: Int = DEFAULT_MAX_LINES,
    maxBytes: Int = DEFAULT_MAX_BYTES,
): TruncationResult {
    val totalBytes = utf8Len(content)
    val lines = splitLinesForCounting(content)
    val totalLines = lines.size

    if (totalLines <= maxLines && totalBytes <= maxBytes) {
        return TruncationResult(content, false, null, totalLines, totalBytes, totalLines, totalBytes, false, false, maxLines, maxBytes)
    }
    // First line alone exceeds the byte budget → return empty with the flag set.
    if (lines.isNotEmpty() && utf8Len(lines[0]) > maxBytes) {
        return TruncationResult("", true, "bytes", totalLines, totalBytes, 0, 0, false, true, maxLines, maxBytes)
    }

    val out = ArrayList<String>()
    var bytes = 0
    var truncatedBy = "lines"
    var i = 0
    while (i < lines.size && i < maxLines) {
        val lineBytes = utf8Len(lines[i]) + if (i > 0) 1 else 0 // +1 for the joining newline
        if (bytes + lineBytes > maxBytes) { truncatedBy = "bytes"; break }
        out.add(lines[i]); bytes += lineBytes; i++
    }
    if (out.size >= maxLines && bytes <= maxBytes) truncatedBy = "lines"

    val outputContent = out.joinToString("\n")
    return TruncationResult(outputContent, true, truncatedBy, totalLines, totalBytes, out.size, utf8Len(outputContent), false, false, maxLines, maxBytes)
}

/** Keep the last lines/bytes that fit. Suitable for bash output. */
fun truncateTail(
    content: String,
    maxLines: Int = DEFAULT_MAX_LINES,
    maxBytes: Int = DEFAULT_MAX_BYTES,
): TruncationResult {
    val totalBytes = utf8Len(content)
    val lines = splitLinesForCounting(content)
    val totalLines = lines.size

    if (totalLines <= maxLines && totalBytes <= maxBytes) {
        return TruncationResult(content, false, null, totalLines, totalBytes, totalLines, totalBytes, false, false, maxLines, maxBytes)
    }

    val out = ArrayDeque<String>()
    var bytes = 0
    var truncatedBy = "lines"
    var lastLinePartial = false
    var i = lines.size - 1
    while (i >= 0 && out.size < maxLines) {
        val lineBytes = utf8Len(lines[i]) + if (out.isNotEmpty()) 1 else 0
        if (bytes + lineBytes > maxBytes) {
            truncatedBy = "bytes"
            // Edge case: nothing collected yet and this line alone busts the budget →
            // keep the tail bytes of the line (partial first line).
            if (out.isEmpty()) {
                val truncatedLine = truncateBytesFromEnd(lines[i], maxBytes)
                out.addFirst(truncatedLine); bytes = utf8Len(truncatedLine); lastLinePartial = true
            }
            break
        }
        out.addFirst(lines[i]); bytes += lineBytes; i--
    }
    if (out.size >= maxLines && bytes <= maxBytes) truncatedBy = "lines"

    val outputContent = out.joinToString("\n")
    return TruncationResult(outputContent, true, truncatedBy, totalLines, totalBytes, out.size, utf8Len(outputContent), lastLinePartial, false, maxLines, maxBytes)
}

/** Keep the last [maxBytes] bytes of [s], snapping to a UTF-8 char boundary. */
private fun truncateBytesFromEnd(s: String, maxBytes: Int): String {
    val buf = s.toByteArray(Charsets.UTF_8)
    if (buf.size <= maxBytes) return s
    var start = buf.size - maxBytes
    // A continuation byte has its top two bits == 10; advance to the next lead byte.
    while (start < buf.size && (buf[start].toInt() and 0xC0) == 0x80) start++
    return String(buf, start, buf.size - start, Charsets.UTF_8)
}
