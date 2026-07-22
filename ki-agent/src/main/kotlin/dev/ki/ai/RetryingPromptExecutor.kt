package dev.ki.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

/**
 * Wraps a [PromptExecutor] with exponential-backoff retry on *transient* LLM-call
 * failures — 5xx, request-timeout, rate-limit (429), and bare network errors (a
 * [KoogHttpClientException] with no status, or any non-HTTP I/O failure). Auth and
 * bad-request errors (401/403/400/404/422) are permanent and re-thrown on the first
 * try; retrying them only burns time and quota.
 *
 * Only the non-streaming [execute]/[executeMultipleChoices] paths retry: once a
 * streaming [Flow] has emitted frames there is no safe point to restart from, so
 * [executeStreaming] delegates unwrapped (streaming retry is deferred). [moderate]
 * and [models] likewise delegate.
 *
 * koog exposes no `Retry-After` header on [KoogHttpClientException], so 429 is
 * treated like any transient error and backed off exponentially rather than by the
 * server-suggested delay.
 */
class RetryingPromptExecutor(
    private val delegate: PromptExecutor,
    private val maxAttempts: Int = 4,
    private val baseDelayMs: Long = 500,
    private val maxDelayMs: Long = 30_000,
) : PromptExecutor() {

    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
    }

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        withRetry("execute") { delegate.execute(prompt, model, tools) }

    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
        withRetry("executeMultipleChoices") { delegate.executeMultipleChoices(prompt, model, tools) }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        delegate.executeStreaming(prompt, model, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override suspend fun models(): List<LLModel> = delegate.models()

    override fun close() = delegate.close()

    private suspend fun <T> withRetry(op: String, block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (attempt >= maxAttempts || !isTransient(e)) throw e
                val backoff = backoffMs(attempt)
                log.warn(e) { "$op failed (attempt $attempt/$maxAttempts), retrying in ${backoff}ms" }
                delay(backoff)
                attempt++
            }
        }
    }

    /** Full jitter: random point in [0, min(cap, base * 2^(attempt-1))]. */
    private fun backoffMs(attempt: Int): Long {
        val exp = baseDelayMs.toDouble() * (1L shl (attempt - 1))
        val capped = exp.coerceAtMost(maxDelayMs.toDouble()).toLong()
        return if (capped <= 0) 0 else Random.nextLong(capped + 1)
    }

    companion object {
        private val log = KotlinLogging.logger {}

        /** HTTP statuses worth retrying: request timeout, too-early, rate-limit, and 5xx. */
        private val RETRYABLE_STATUS = setOf(408, 425, 429, 500, 502, 503, 504)

        /**
         * True if [error] (or something in its cause chain) is a transient failure.
         *
         * The first [KoogHttpClientException] in the chain decides: a status in
         * [RETRYABLE_STATUS] (or no status at all — a connection failure koog couldn't
         * tag) is transient; any other status (401/403/400/…) is permanent. With no
         * koog HTTP exception in the chain, a raw [java.io.IOException] (connect reset,
         * socket timeout, unknown host) is transient; anything else — a programming
         * error, a bad prompt — is not, so we fail fast instead of burning retries.
         */
        fun isTransient(error: Throwable): Boolean {
            var cur: Throwable? = error
            while (cur != null) {
                when (cur) {
                    is KoogHttpClientException -> {
                        val status = cur.statusCode
                        return status == null || status in RETRYABLE_STATUS
                    }
                    is java.io.IOException -> return true
                }
                cur = cur.cause
            }
            return false
        }
    }
}
