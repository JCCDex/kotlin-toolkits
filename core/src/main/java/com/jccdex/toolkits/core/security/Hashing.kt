package com.jccdex.toolkits.core.security

import com.jccdex.toolkits.core.encoding.toHex
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 helpers (C-13) — single home for hashing shared by apk-verify / did / nft.
 */
object Hashing {
    private const val BUFFER_SIZE = 8192

    /** SHA-256 digest of [bytes]. */
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** SHA-256 digest of [inputStream]; closes the stream. */
    fun sha256(inputStream: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest()
    }

    /** SHA-256 digest of [file]. */
    fun sha256(file: File): ByteArray = sha256(FileInputStream(file))

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

    /** Lowercase hex SHA-256 of [inputStream]. */
    fun sha256Hex(inputStream: InputStream): String = sha256(inputStream).toHex()

    /** Lowercase hex SHA-256 of [file]. */
    fun sha256Hex(file: File): String = sha256(file).toHex()
}
