package com.jccdex.toolkits.core.security

import java.util.Locale

/**
 * Constant-time comparison helpers (C-13) — the comparison loop runs over every byte so timing
 * does not reveal which byte differs. Length mismatch short-circuits (hash lengths are fixed/known).
 */
object SecureCompare {
    /** Constant-time equality of two byte arrays. */
    fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray
    ): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    /** Constant-time equality of two hex strings after lowercase normalization (case-insensitive). */
    fun constantTimeHexEquals(
        a: String,
        b: String
    ): Boolean {
        val aBytes = a.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII)
        val bBytes = b.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII)
        return constantTimeEquals(aBytes, bBytes)
    }
}
