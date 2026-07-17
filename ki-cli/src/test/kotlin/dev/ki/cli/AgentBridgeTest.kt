package dev.ki.cli

import dev.ki.cli.ui.AgentBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentBridgeTest {

    @Test
    fun `result is marshaled through uiPost`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val posted = AtomicReference(false)
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()

        // uiPost stands in for TApplication::invokeLater.
        val uiPost: (Runnable) -> Unit = { r -> posted.set(true); r.run() }
        val bridge = AgentBridge(scope, uiPost, run = { "echo: $it" })

        bridge.submit("hello") { r -> result.set(r); latch.countDown() }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "callback never fired")
        assertTrue(posted.get(), "result was not marshaled through uiPost")
        assertEquals("echo: hello", result.get())
    }

    @Test
    fun `runner exceptions surface as an error line, not a crash`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        val bridge = AgentBridge(scope, { it.run() }, run = { error("boom") })

        bridge.submit("x") { r -> result.set(r); latch.countDown() }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(result.get().contains("boom"), "expected error text, got: ${result.get()}")
    }

    @Test
    fun `cancel stops the turn without firing the result callback`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val resultFired = AtomicBoolean(false)

        val bridge = AgentBridge(scope, { it.run() }, run = {
            started.countDown()
            delay(10_000) // never completes within the test
            "done"
        })
        bridge.submit("x") { resultFired.set(true) }

        assertTrue(started.await(5, TimeUnit.SECONDS), "turn never started")
        bridge.cancel { cancelled.countDown() }

        assertTrue(cancelled.await(5, TimeUnit.SECONDS), "onCancelled never fired")
        Thread.sleep(50) // give any erroneous result callback a chance to fire
        assertFalse(resultFired.get(), "result callback must not fire on cancel")
        assertFalse(bridge.isBusy(), "bridge should be idle after cancel")
    }

    @Test
    fun `cancel with nothing running is a no-op`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fired = AtomicBoolean(false)
        AgentBridge(scope, { it.run() }, run = { "x" }).cancel { fired.set(true) }
        assertFalse(fired.get())
    }
}
