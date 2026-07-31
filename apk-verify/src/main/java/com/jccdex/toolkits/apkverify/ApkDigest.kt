package com.jccdex.toolkits.apkverify

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Computes SHA-256 hex digests for byte arrays, files, and streams.
 */
object ApkDigest {
    private const val BUFFER_SIZE = 8192

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).toHex()
    }

    /** Lowercase hex SHA-256 of [inputStream]. Closes the stream. */
    fun sha256Hex(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().toHex()
    }

    /** Lowercase hex SHA-256 of [file]. */
    fun sha256Hex(file: File): String = sha256Hex(FileInputStream(file))

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
}
