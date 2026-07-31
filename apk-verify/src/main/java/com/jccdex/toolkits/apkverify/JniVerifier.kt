package com.jccdex.toolkits.apkverify

import java.io.File

/**
 * Integrity verification bridge.
 *
 * When the native library (libintegrity.so) is available, hash computation
 * and comparison are performed in C to raise the bar for run-time tampering.
 * Otherwise falls back to pure Java.
 */
object JniVerifier {
    /** Whether [libintegrity.so] was loaded successfully. */
    private val nativeAvailable: Boolean =
        runCatching {
            System.loadLibrary("integrity")
            true
        }.getOrDefault(false)

    /**
     * Compares two hex hash strings case-insensitively.
     */
    fun hashEquals(
        a: String?,
        b: String?
    ): Boolean {
        if (a == null || b == null) return false
        return if (nativeAvailable) {
            nativeHashEquals(a, b)
        } else {
            a.equals(b, ignoreCase = true)
        }
    }

    /**
     * Computes the SHA-256 digest of [file] and returns it as a
     * lowercase hex string. Falls back to Java when native is unavailable.
     */
    fun computeSha256(file: File): String {
        if (nativeAvailable) {
            val hex = nativeComputeSha256(file.absolutePath)
            if (hex != null) return hex
        }
        return ApkDigest.sha256Hex(file)
    }

    private external fun nativeComputeSha256(filePath: String): String?

    private external fun nativeHashEquals(
        hash1: String?,
        hash2: String?
    ): Boolean
}
