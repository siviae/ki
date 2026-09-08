package dev.ki.cli.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProxyModelsTest {
    @Test fun `models endpoint is baseUrl normalized plus slash v1 slash models`() {
        var seen: String? = null
        ProxyModels { url, _ ->
            seen = url
            """{"data":[{"id":"m","max_input_tokens":1,"max_output_tokens":2}]}"""
        }.lookup("http://localhost:4000/v1", "key", "m")
        assertEquals("http://localhost:4000/v1/models", seen)
    }

    @Test fun `bare proxy root also works`() {
        var seen: String? = null
        ProxyModels { url, _ ->
            seen = url
            """{"data":[{"id":"m","max_input_tokens":1,"max_output_tokens":2}]}"""
        }.lookup("http://localhost:4000", "key", "m")
        assertEquals("http://localhost:4000/v1/models", seen)
    }

    @Test fun `unknown model, bad json and http failures degrade to null`() {
        val good = """{"data":[{"id":"m","max_input_tokens":1,"max_output_tokens":2}]}"""
        assertNull(ProxyModels { _, _ -> good }.lookup("http://x/v1", "k", "other"))
        assertNull(ProxyModels { _, _ -> "not json" }.lookup("http://x/v1", "k", "m"))
        assertNull(ProxyModels { _, _ -> null }.lookup("http://x/v1", "k", "m"))
        assertNull(ProxyModels { _, _ -> """{"data":[{"id":"m"}]}""" }.lookup("http://x/v1", "k", "m"))
    }
}
