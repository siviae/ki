package dev.ki.cli.ui

import dev.ki.agent.ToolCallEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Bridges the coroutine/async agent runtime to the single-threaded ki-tui UI.
 *
 * The agent runs on a background coroutine; when it produces a result the callback
 * is marshaled back onto the UI thread via [uiPost] (in production, `Tui::post`).
 * Widgets are only ever touched inside that posted runnable — never off-thread.
 *
 * [run] is injected (rather than a concrete agent) so the bridge is unit-testable
 * without an LLM or a terminal.
 *
 * A single in-flight turn is tracked so it can be [cancel]led. On cancel the result
 * callback does **not** fire (the coroutine is dead) — callers pass an `onCancel`
 * cleanup so the UI can leave the "thinking" state cleanly.
 */
class AgentBridge(
    private val scope: CoroutineScope,
    private val uiPost: (Runnable) -> Unit,
    private val run: suspend (String, (String) -> Unit, (ToolCallEvent) -> Unit) -> String,
) {
    @Volatile
    private var current: Job? = null

    /**
     * Start a turn. [onReasoning] receives streamed reasoning deltas and [onTool] receives
     * tool-call lifecycle events; all callbacks are marshaled onto the UI thread. On cancel
     * none fire again (the coroutine is dead).
     */
    fun submit(
        prompt: String,
        onReasoning: (String) -> Unit,
        onTool: (ToolCallEvent) -> Unit,
        onResult: (String) -> Unit,
    ) {
        current = scope.launch {
            val result = try {
                run(
                    prompt,
                    { delta -> uiPost(Runnable { onReasoning(delta) }) },
                    { event -> uiPost(Runnable { onTool(event) }) },
                )
            } catch (e: CancellationException) {
                throw e // cancellation is not an error — never surface it as a result
            } catch (e: Throwable) {
                "⚠ error: ${e.message ?: e::class.simpleName}"
            }
            uiPost(Runnable { onResult(result) })
        }
    }

    /** True while a turn is running. */
    fun isBusy(): Boolean = current?.isActive == true

    /**
     * Cancel the in-flight turn, if any. [onCancelled] runs on the UI thread once the
     * turn has actually stopped, for UI cleanup (the result callback won't fire).
     */
    fun cancel(onCancelled: () -> Unit) {
        val job = current ?: return
        if (!job.isActive) return
        job.cancel()
        scope.launch {
            job.join()
            uiPost(Runnable { onCancelled() })
        }
    }
}
