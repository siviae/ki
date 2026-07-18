package dev.ki.cluster

import java.security.MessageDigest

/**
 * Maps a session id to the 64-bit key `pg_advisory_lock` takes. Must be **deterministic and
 * stable across JVMs/nodes** so every node computes the same key for a session — that is what
 * makes the lock actually mutually exclusive across the cluster.
 *
 * `String.hashCode` is only 32-bit (collision-prone) and not contractually stable across
 * platforms; SHA-256's first 8 bytes give a stable 64-bit value with negligible collision risk.
 */
object AdvisoryKeys {
    fun of(sessionId: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8))
        var key = 0L
        for (i in 0 until 8) key = (key shl 8) or (digest[i].toLong() and 0xff)
        return key
    }
}
