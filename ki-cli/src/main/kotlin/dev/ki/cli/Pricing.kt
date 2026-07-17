package dev.ki.cli

/** Per-1M-token USD prices (a local estimate — LiteLLM/provider billing is authoritative). */
data class ModelPrice(val inputPerMillion: Double, val outputPerMillion: Double)

/**
 * A small local price table for the running-cost display. Unknown models return
 * `null` (the status line then omits cost rather than lying). These are estimates;
 * the real bill comes from the provider / LiteLLM.
 */
object Pricing {
    private val table: Map<String, ModelPrice> = mapOf(
        "gpt-4o" to ModelPrice(2.50, 10.00),
        "gpt-4o-mini" to ModelPrice(0.15, 0.60),
        "gpt-4.1" to ModelPrice(2.00, 8.00),
        "gpt-4.1-mini" to ModelPrice(0.40, 1.60),
        "gpt-4.1-nano" to ModelPrice(0.10, 0.40),
        "o3" to ModelPrice(2.00, 8.00),
        "o4-mini" to ModelPrice(1.10, 4.40),
    )

    /** Estimated USD cost, or null if the model isn't in the table. */
    fun costUsd(modelId: String, inputTokens: Long, outputTokens: Long): Double? {
        val p = table[modelId] ?: return null
        return inputTokens / 1_000_000.0 * p.inputPerMillion +
            outputTokens / 1_000_000.0 * p.outputPerMillion
    }
}
