package dev.ki.agent.context

import ai.koog.prompt.dsl.prompt
import kotlin.test.Test
import kotlin.test.assertTrue

class KiTokenizerTest {
    private val tok = KiTokenizer()

    @Test fun `estimate grows with prompt size`() {
        val small = prompt("t") { system("hi") }
        val large = prompt("t") { system("word ".repeat(500)) }
        assertTrue(tok.estimate(small) > 0)
        assertTrue(tok.estimate(large) > tok.estimate(small))
    }
}
