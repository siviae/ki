package dev.ki.ai

import kotlin.test.Test
import ai.koog.prompt.llm.LLMCapability
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class KiModelTest {
    @Test fun `vision model declares the Vision capability for koog`() {
        val caps = KiModel(id = "qwen3.8-27b").toLLModel().capabilities!!
        assertTrue(caps.contains(LLMCapability.Vision.Image), "capabilities=$caps")
    }

    @Test fun `non-vision model omits Vision so koog rejects image parts up front`() {
        val caps = KiModel(id = "text-only", vision = false).toLLModel().capabilities!!
        assertFalse(caps.contains(LLMCapability.Vision.Image), "capabilities=$caps")
        // the rest of the set is untouched
        assertEquals(3, caps.size)
    }
}
