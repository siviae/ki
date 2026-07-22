package dev.ki.agent.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The `config<T>()` DSL + [ConfigHandle] contract, independent of Jackson / script compilation. */
class ExtensionConfigDslTest {

    data class Foo(val x: Int = 0)

    @Test fun `config registers a request, handle errors before fill and returns value after`() {
        lateinit var handle: ConfigHandle<Foo>
        val ext = extension { handle = config() }

        val req = ext.configRequests.single()
        assertEquals(Foo::class, req.type)
        assertNull(req.section, "default binds the whole manifest root")

        assertFailsWith<IllegalStateException> { handle() }   // read before fill
        req.fill(Foo(42))
        assertEquals(42, handle().x)
    }

    @Test fun `section argument is recorded and fill flows to the returned handle`() {
        lateinit var handle: ConfigHandle<Foo>
        val ext = extension { handle = config("bash") }

        val req = ext.configRequests.single()
        assertEquals("bash", req.section)
        val value = Foo(7)
        req.fill(value)
        assertSame(value, handle())
    }
}
