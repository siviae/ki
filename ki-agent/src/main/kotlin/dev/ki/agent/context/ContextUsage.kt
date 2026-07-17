package dev.ki.agent.context

/**
 * Context-window usage after a turn. [tokens] is the prompt size against [window].
 * [reported] is true when [tokens] came from the LLM's own usage accounting
 * (`ResponseMetaInfo`); false when it's a local estimate (no usage returned).
 */
data class ContextUsage(
    val tokens: Int,
    val window: Long,
    val reported: Boolean,
) {
    /** Percent of the window used (0 when the window is unknown). */
    val percent: Int get() = if (window <= 0) 0 else ((tokens.toLong() * 100) / window).toInt()
}
