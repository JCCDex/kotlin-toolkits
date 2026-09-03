package com.jccdex.toolkits.apkverify

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Result of an APK integrity verification.
 */
sealed class ApkVerificationResult {
    /** Signature AND file hash both match the manifest entry. */
    data object PassedFull : ApkVerificationResult()

    /** Signature matches; the manifest has no file hash for this version. */
    data object PassedSignatureOnly : ApkVerificationResult()

    /** The APK's signing certificate does not match [expected]. */
    data class CertMismatch(
        val expected: String,
        val actual: String
    ) : ApkVerificationResult()

    /** The file hash does not match the manifest entry for this version. */
    data class HashMismatch(
        val expected: String,
        val actual: String
    ) : ApkVerificationResult()

    /** The versionCode in the provided APK is not listed in the manifest. */
    data class VersionUnknown(
        val versionCode: Int,
        val versionName: String
    ) : ApkVerificationResult()

    /** The file is not a valid Android APK. */
    data object NotAValidApk : ApkVerificationResult()

    /** I/O error while reading the selected file. */
    data object ReadFailed : ApkVerificationResult()

    /** The bundled manifest is missing or corrupt. */
    data object ManifestMissing : ApkVerificationResult()

    /** Manifest exists but signingCertSha256 is empty. */
    data object ManifestNotConfigured : ApkVerificationResult()

    /** Imported checksums file could not be parsed. */
    data object ChecksumsParseFailed : ApkVerificationResult()

    /** Checksums signing cert does not match the embedded manifest trust root. */
    data class ChecksumsCertMismatch(
        val expected: String,
        val actual: String
    ) : ApkVerificationResult()

    /** APK version does not match the imported checksums file version. */
    data class ChecksumsVersionMismatch(
        val checksumsVersionName: String,
        val checksumsVersionCode: Int,
        val apkVersionName: String,
        val apkVersionCode: Int
    ) : ApkVerificationResult()
}

/**
 * Wraps the [result] of a file-based verification together with the
 * computed APK SHA-256.
 */
data class ApkFileVerification(
    val result: ApkVerificationResult,
    val computedApkSha256: String? = null,
    val versionCode: Int? = null,
    val versionName: String? = null
)

/**
 * Verifies that an APK file — either the installed package or a file
 * selected by the user — is signed with the official release certificate.
 */
object ApkIntegrityVerifier {
    private const val BUFFER_SIZE = 8192

    /** M-W2: bounded APK copy — a content:// source must not fill the cache dir (DoS). */
    private const val MAX_APK_SIZE = 512L * 1024 * 1024 // 512 MB

    /**
     * Verifies the APK file at [uri] against the bundled manifest.
     *
     * TRUST MODEL (H-W4-3): the expected signing certificate comes from the host app's bundled
     * `official_release_manifest.json`, which each host generates for itself and which ships signed
     * inside the host APK. This guards against installing a wrong/unexpected APK while the host app
     * itself is legitimate — it does NOT protect against a repackaged host (which can edit its own
     * manifest or fake the result). For an out-of-band anchor, pass [checksums] (a user-imported
     * `ReleaseChecksums` file), whose cert and hashes are cross-checked against the manifest.
     *
     * M-W3: suspend + IO — the previous synchronous version ran APK copy + parse + SHA-256 on the
     * caller's thread (UI ANR). The blocking work is delegated to [verifyApkFileInternal].
     */
    suspend fun verifyApkFile(
        context: Context,
        uri: Uri,
        checksums: ReleaseChecksums? = null
    ): ApkFileVerification =
        withContext(Dispatchers.IO) {
            verifyApkFileInternal(context, uri, checksums)
        }

    private fun verifyApkFileInternal(
        context: Context,
        uri: Uri,
        checksums: ReleaseChecksums? = null
    ): ApkFileVerification {
        val manifest =
            OfficialReleaseManifestLoader.load(context)
                ?: return ApkFileVerification(ApkVerificationResult.ManifestMissing)

        if (manifest.signingCertSha256.isBlank()) {
            return ApkFileVerification(ApkVerificationResult.ManifestNotConfigured)
        }

        val temp =
            copyUriToTemp(context, uri)
                ?: return ApkFileVerification(ApkVerificationResult.ReadFailed)

        try {
            // M-W3: single parse — GET_SIGNING_CERTIFICATES yields versionCode + signing cert in one
            // getPackageArchiveInfo call (was two separate parses of the same temp APK).
            val info =
                context.packageManager.getPackageArchiveInfo(
                    temp.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ) ?: return ApkFileVerification(ApkVerificationResult.NotAValidApk)

            val versionCode = info.versionCode
            val versionName = info.versionName ?: "unknown"

            val signerCerts = ApkSigningFingerprint.allCertSha256FromInfo(info)
            if (signerCerts.isEmpty()) {
                return ApkFileVerification(ApkVerificationResult.ReadFailed)
            }

            // L-17: accept when any signer matches the trusted manifest cert (dual-signed APKs).
            if (!ApkSigningFingerprint.matchesAnySigner(info, manifest.signingCertSha256)) {
                return ApkFileVerification(
                    result =
                        ApkVerificationResult.CertMismatch(
                            manifest.signingCertSha256,
                            signerCerts.first()
                        ),
                    versionCode = versionCode,
                    versionName = versionName
                )
            }

            val computedSha256 = JniVerifier.computeSha256(temp)
            val result =
                verifyApkContents(
                    manifest = manifest,
                    versionCode = versionCode,
                    versionName = versionName,
                    computedApkSha256 = computedSha256,
                    checksums = checksums
                )

            return ApkFileVerification(
                result = result,
                computedApkSha256 = computedSha256,
                versionCode = versionCode,
                versionName = versionName
            )
        } finally {
            temp.delete()
        }
    }

    internal fun verifyApkContents(
        manifest: OfficialReleaseManifest,
        versionCode: Int,
        versionName: String,
        computedApkSha256: String,
        checksums: ReleaseChecksums? = null
    ): ApkVerificationResult {
        val entry = manifest.releases.find { it.versionCode == versionCode }
        val manifestHash = entry?.apkSha256?.trim().orEmpty()

        if (manifestHash.isNotEmpty()) {
            return if (JniVerifier.hashEquals(computedApkSha256, manifestHash)) {
                ApkVerificationResult.PassedFull
            } else {
                ApkVerificationResult.HashMismatch(manifestHash, computedApkSha256)
            }
        }

        if (checksums != null) {
            if (!JniVerifier.hashEquals(checksums.signingCertSha256, manifest.signingCertSha256)) {
                return ApkVerificationResult.ChecksumsCertMismatch(
                    expected = manifest.signingCertSha256,
                    actual = checksums.signingCertSha256
                )
            }
            if (checksums.versionCode != versionCode) {
                return ApkVerificationResult.ChecksumsVersionMismatch(
                    checksumsVersionName = checksums.versionName,
                    checksumsVersionCode = checksums.versionCode,
                    apkVersionName = versionName,
                    apkVersionCode = versionCode
                )
            }
            return if (JniVerifier.hashEquals(computedApkSha256, checksums.apkSha256)) {
                ApkVerificationResult.PassedFull
            } else {
                ApkVerificationResult.HashMismatch(checksums.apkSha256, computedApkSha256)
            }
        }

        if (entry == null) {
            return ApkVerificationResult.VersionUnknown(versionCode, versionName)
        }

        return ApkVerificationResult.PassedSignatureOnly
    }

    private fun copyUriToTemp(
        context: Context,
        uri: Uri
    ): File? {
        val temp =
            try {
                File.createTempFile("apk_verify_", ".apk", context.cacheDir)
            } catch (_: Exception) {
                return null
            }
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                copyStreamToTemp(input, temp, MAX_APK_SIZE)
            } ?: run {
                temp.delete()
                null
            }
        } catch (_: Exception) {
            temp.delete()
            null
        }
    }

    /**
     * Bounded stream→file copy (M-W2). Returns [temp] on success; deletes [temp] and returns null
     * when the stream exceeds [maxBytes] or an I/O error occurs.
     * [maxBytes] is injectable so unit tests need not stream 512MB.
     */
    internal fun copyStreamToTemp(
        input: InputStream,
        temp: File,
        maxBytes: Long = MAX_APK_SIZE
    ): File? =
        try {
            temp.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) {
                        temp.delete()
                        return null
                    }
                    output.write(buffer, 0, n)
                }
            }
            temp
        } catch (_: Exception) {
            temp.delete()
            null
        }
}
