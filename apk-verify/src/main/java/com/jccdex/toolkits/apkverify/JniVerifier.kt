package com.jccdex.toolkits.apkverify

import android.util.Log
import com.jccdex.toolkits.core.security.SecureCompare
import java.io.File

/**
 * Integrity verification bridge.
 *
 * When the native library (libintegrity.so) is available, hash computation
 * and comparison are performed in C to raise the bar for run-time tampering.
 * Otherwise falls back to pure Java.
 */
object JniVerifier {
    private const val TAG = "JniVerifier"

    /** Whether [libintegrity.so] was loaded successfully. */
    private val nativeAvailable: Boolean =
        runCatching {
            System.loadLibrary("integrity")
            true
        }.getOrDefault(false).also { available ->
            if (!available) {
                // M-W1: fail loudly instead of silently degrading — callers must know hashes are
                // no longer computed/compared in native. Best-effort: plain-JVM unit tests stub Log.
                runCatching {
                    Log.e(
                        TAG,
                        "libintegrity.so failed to load — hash compute/compare degrades to pure Java"
                    )
                }
            }
        }

    /**
     * Compares two hex hash strings. Uses native constant-time comparison when available,
     * otherwise a constant-time Java fallback (M-W1: no timing side-channel, no ignoreCase).
     */
    fun hashEquals(
        a: String?,
        b: String?
    ): Boolean {
        if (a == null || b == null) return false
        return if (nativeAvailable) {
            nativeHashEquals(a, b)
        } else {
            constantTimeHexEquals(a, b)
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

    /** Constant-time comparison of two hex strings (M-W1/C-13): converges to core [SecureCompare]. */
    private fun constantTimeHexEquals(
        a: String,
        b: String
    ): Boolean = SecureCompare.constantTimeHexEquals(a, b)

    private external fun nativeComputeSha256(filePath: String): String?

    private external fun nativeHashEquals(
        hash1: String?,
        hash2: String?
    ): Boolean
}
