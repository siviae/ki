package dev.ki.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PricingTest {
    @Test fun `known model priced per million tokens`() {
        // gpt-4o: 2.50 in / 10.00 out per 1M.
        assertEquals(2.50, Pricing.costUsd("gpt-4o", 1_000_000, 0)!!, 1e-9)
        assertEquals(10.00, Pricing.costUsd("gpt-4o", 0, 1_000_000)!!, 1e-9)
        assertTrue(Pricing.costUsd("gpt-4o", 500_000, 500_000)!! > 0)
    }

    @Test fun `unknown model returns null`() {
        assertNull(Pricing.costUsd("some-unlisted-model", 1000, 1000))
    }
}
