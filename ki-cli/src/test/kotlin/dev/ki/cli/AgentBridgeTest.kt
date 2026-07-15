package dev.ki.cli

import dev.ki.cli.ui.AgentBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
