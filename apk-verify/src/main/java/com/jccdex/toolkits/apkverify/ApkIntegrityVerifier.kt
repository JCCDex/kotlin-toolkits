package com.jccdex.toolkits.apkverify

import android.content.Context
import android.net.Uri
import java.io.File

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

    /**
     * Checks whether the currently installed package is signed with the
     * expected release certificate from the manifest.
     *
     * @param context Android context
     * @param skipSignatureCheck when true, always returns [ApkVerificationResult.PassedSignatureOnly]
     *        (callers should pass [BuildConfig.DEBUG] for their own module)
     */
    fun verifyInstalledPackage(
        context: Context,
        skipSignatureCheck: Boolean = false
    ): ApkVerificationResult {
        if (skipSignatureCheck) {
            return ApkVerificationResult.PassedSignatureOnly
        }

        val manifest =
            OfficialReleaseManifestLoader.load(context)
                ?: return ApkVerificationResult.ManifestMissing

        if (manifest.signingCertSha256.isBlank()) {
            return ApkVerificationResult.ManifestNotConfigured
        }

        val actual =
            ApkSigningFingerprint.installedReleaseCertSha256(context)
                ?: return ApkVerificationResult.ReadFailed

        return if (JniVerifier.hashEquals(actual, manifest.signingCertSha256)) {
            ApkVerificationResult.PassedSignatureOnly
        } else {
            ApkVerificationResult.CertMismatch(manifest.signingCertSha256, actual)
        }
    }

    /**
     * Verifies the APK file at [uri] against the bundled manifest.
     */
    fun verifyApkFile(
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
            val info =
                context.packageManager.getPackageArchiveInfo(
                    temp.absolutePath,
                    0
                ) ?: return ApkFileVerification(ApkVerificationResult.NotAValidApk)

            val versionCode = info.versionCode
            val versionName = info.versionName ?: "unknown"

            val actualCert =
                ApkSigningFingerprint.archiveCertSha256(
                    context,
                    temp.absolutePath
                ) ?: return ApkFileVerification(ApkVerificationResult.ReadFailed)

            if (!JniVerifier.hashEquals(actualCert, manifest.signingCertSha256)) {
                return ApkFileVerification(
                    result =
                        ApkVerificationResult.CertMismatch(
                            manifest.signingCertSha256,
                            actualCert
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
        return try {
            val temp = File.createTempFile("apk_verify_", ".apk", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            } ?: return null
            temp
        } catch (_: Exception) {
            null
        }
    }
}
