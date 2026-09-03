package com.jccdex.toolkits.apkverify

import com.jccdex.toolkits.core.security.Hashing
import java.io.File
import java.io.InputStream

/**
 * SHA-256 hex digest facade (C-13) — implementation converged to core [Hashing].
 */
object ApkDigest {
    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String = Hashing.sha256Hex(bytes)

    /** Lowercase hex SHA-256 of [inputStream]. Closes the stream. */
    fun sha256Hex(inputStream: InputStream): String = Hashing.sha256Hex(inputStream)

    /** Lowercase hex SHA-256 of [file]. */
    fun sha256Hex(file: File): String = Hashing.sha256Hex(file)
}
