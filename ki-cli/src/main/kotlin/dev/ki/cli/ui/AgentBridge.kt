package dev.ki.cli.ui

import kotlinx.coroutines.CoroutineScope
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
 */
class AgentBridge(
    private val scope: CoroutineScope,
    private val uiPost: (Runnable) -> Unit,
    private val run: suspend (String) -> String,
) {
    fun submit(prompt: String, onResult: (String) -> Unit) {
        scope.launch {
            val result = try {
                run(prompt)
            } catch (e: Throwable) {
                "⚠ error: ${e.message ?: e::class.simpleName}"
            }
            uiPost(Runnable { onResult(result) })
        }
    }
}
