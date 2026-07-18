package dev.ki.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryingPromptExecutorTest {

    /** Fails the first [failures] calls with [error], then returns an assistant reply. */
    private class FlakyExecutor(private val failures: Int, private val error: () -> Throwable) : PromptExecutor() {
        val calls = AtomicInteger(0)
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            if (calls.getAndIncrement() < failures) throw error()
            return Message.Assistant(MessagePart.Text("ok"), ResponseMetaInfo.Empty)
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            throw NotImplementedError()
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): List<Message.Assistant> =
            throw NotImplementedError()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw NotImplementedError()
        override fun close() {}
    }

    private val p = prompt("t") { user("hi") }
    private val m = KiModel("test-model").toLLModel()
    private fun exec(e: PromptExecutor) = runBlocking { e.execute(p, m) }
    private fun text(msg: Message.Assistant) = (msg.parts.single() as MessagePart.Text).text

    @Test fun `transient 500 is retried then succeeds`() {
        val flaky = FlakyExecutor(failures = 2) { KoogHttpClientException(statusCode = 500) }
        val retrying = RetryingPromptExecutor(flaky, maxAttempts = 4, baseDelayMs = 1)

        assertEquals("ok", text(exec(retrying)))
        assertEquals(3, flaky.calls.get(), "expected 2 failures + 1 success")
    }

    @Test fun `auth 401 fails fast without retry`() {
        val flaky = FlakyExecutor(failures = 1) { KoogHttpClientException(statusCode = 401) }
        val retrying = RetryingPromptExecutor(flaky, maxAttempts = 4, baseDelayMs = 1)

        assertFailsWith<KoogHttpClientException> { exec(retrying) }
        assertEquals(1, flaky.calls.get(), "401 must not be retried")
    }

    @Test fun `retries are bounded by maxAttempts`() {
        val flaky = FlakyExecutor(failures = 99) { KoogHttpClientException(statusCode = 503) }
        val retrying = RetryingPromptExecutor(flaky, maxAttempts = 3, baseDelayMs = 1)

        assertFailsWith<KoogHttpClientException> { exec(retrying) }
        assertEquals(3, flaky.calls.get(), "should stop after maxAttempts")
    }

    @Test fun `network IOException is transient`() {
        val flaky = FlakyExecutor(failures = 1) { IOException("connection reset") }
        val retrying = RetryingPromptExecutor(flaky, maxAttempts = 2, baseDelayMs = 1)

        assertEquals("ok", text(exec(retrying)))
        assertEquals(2, flaky.calls.get())
    }

    @Test fun `classifier tags statuses and causes correctly`() {
        assertTrue(RetryingPromptExecutor.isTransient(KoogHttpClientException(statusCode = 429)))
        assertTrue(RetryingPromptExecutor.isTransient(KoogHttpClientException(statusCode = null)))
        assertTrue(RetryingPromptExecutor.isTransient(IOException("reset")))
        assertTrue(
            RetryingPromptExecutor.isTransient(RuntimeException("wrap", IOException("reset"))),
            "transient cause deep in the chain still counts",
        )
        // The real path: the OpenAI client rewraps the ktor KoogHttpClientException in
        // an outer exception, so the status must still be found through the cause chain.
        assertTrue(RetryingPromptExecutor.isTransient(RuntimeException("wrap", KoogHttpClientException(statusCode = 500))))
        assertFalse(RetryingPromptExecutor.isTransient(RuntimeException("wrap", KoogHttpClientException(statusCode = 401))))
        assertFalse(RetryingPromptExecutor.isTransient(KoogHttpClientException(statusCode = 401)))
        assertFalse(RetryingPromptExecutor.isTransient(IllegalArgumentException("bad prompt")))
    }
}
