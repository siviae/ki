package dev.ki.agent.context

/**
 * Cumulative token usage across LLM calls, thread-safe. Owned by the CLI controller
 * and injected into `KiAgent` so the running total **survives a `/model` rebuild**
 * (a new agent instance, same accumulator). Drives the running-cost display.
 */
class UsageAccumulator {
    private val lock = Any()
    var inputTokens: Long = 0L; private set
    var outputTokens: Long = 0L; private set
    var calls: Long = 0L; private set

    fun record(input: Int?, output: Int?) = synchronized(lock) {
        inputTokens += (input ?: 0)
        outputTokens += (output ?: 0)
        calls++
    }

    val totalTokens: Long get() = inputTokens + outputTokens
}
