package dev.ki.cluster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AdvisoryKeysTest {

    @Test fun `same session id maps to the same key`() {
        assertEquals(AdvisoryKeys.of("session-abc"), AdvisoryKeys.of("session-abc"))
    }

    @Test fun `distinct session ids map to distinct keys`() {
        assertNotEquals(AdvisoryKeys.of("a"), AdvisoryKeys.of("b"))
        assertNotEquals(AdvisoryKeys.of("session-1"), AdvisoryKeys.of("session-2"))
    }

    @Test fun `key is a stable constant across runs (guards cross-node determinism)`() {
        // A pinned literal: if the hashing changes, nodes on different builds would compute
        // different keys for the same session and the lock would stop being mutually exclusive.
        assertEquals(-2039914840885289964L, AdvisoryKeys.of(""))
    }
}
