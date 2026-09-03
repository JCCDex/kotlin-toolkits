package com.jccdex.toolkits.apkverify

import android.content.Context
import android.net.Uri
import java.util.Locale

/**
 * Parsed contents of a release checksums file.
 */
data class ReleaseChecksums(
    val versionName: String,
    val versionCode: Int,
    val apkSha256: String,
    val signingCertSha256: String
)

object ReleaseChecksumsParser {
    private val HEX_SHA256 = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Reads and parses a checksums file from [uri].
     * Returns null when the file cannot be read or parsed.
     */
    fun parseFromUri(
        context: Context,
        uri: Uri
    ): ReleaseChecksums? {
        val text =
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                }
            } catch (_: Exception) {
                null
            } ?: return null

        return parse(text)
    }

    /** Parse checksums text content. */
    fun parse(text: String): ReleaseChecksums? {
        val fields = mutableMapOf<String, String>()

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                fields[key] = value
            }
        }

        val versionName = fields["versionName"]?.trim().orEmpty()
        val versionCodeRaw = fields["versionCode"]?.trim().orEmpty()
        val apkSha256 = fields["apkSha256"]?.trim().orEmpty()
        val signingCertSha256 = fields["signingCertSha256"]?.trim().orEmpty()

        val versionCode = versionCodeRaw.toIntOrNull() ?: return null
        if (versionName.isBlank()) return null
        if (!HEX_SHA256.matches(apkSha256)) return null
        if (!HEX_SHA256.matches(signingCertSha256)) return null

        return ReleaseChecksums(
            versionName = versionName,
            versionCode = versionCode,
            apkSha256 = apkSha256.lowercase(Locale.ROOT),
            signingCertSha256 = signingCertSha256.lowercase(Locale.ROOT)
        )
    }
}
