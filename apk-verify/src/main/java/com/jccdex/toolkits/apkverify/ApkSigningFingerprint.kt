package com.jccdex.toolkits.apkverify

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * Extracts the release signing certificate SHA-256 fingerprint from
 * installed packages and APK archive files.
 *
 * Requires API ≥ 28 (minSdk 30 satisfies it).
 */
object ApkSigningFingerprint {
    private const val TAG = "ApkSigningFingerprint"

    /**
     * Returns the lowercase hex SHA-256 of the installed application's
     * first signing certificate, or null if it cannot be read.
     *
     * L-17: Logs a warning if multiple signers are present.
     */
    fun installedReleaseCertSha256(context: Context): String? {
        return try {
            val info =
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            val signingInfo = info.signingInfo ?: return null
            val signers = signingInfo.apkContentsSigners ?: return null
            if (signers.isEmpty()) return null
            if (signers.size > 1) {
                Log.w(TAG, "Multiple APK signers detected (${signers.size}); only first is verified")
            }
            ApkDigest.sha256Hex(signers[0].toByteArray())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns the lowercase hex SHA-256 of the first signing certificate
     * of an uninstalled APK file at [apkPath], or null on failure.
     *
     * L-17: Logs a warning if multiple signers are present.
     */
    fun archiveCertSha256(
        context: Context,
        apkPath: String
    ): String? {
        return try {
            val info =
                context.packageManager.getPackageArchiveInfo(
                    apkPath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ) ?: return null
            certSha256FromInfo(info)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * M-W3: cert SHA-256 from an already-parsed [PackageInfo] — avoids a second getPackageArchiveInfo
     * call when the caller already holds a parsed archive (verifyApkFile).
     *
     * L-17: Logs a warning if multiple signers are present.
     */
    fun certSha256FromInfo(info: PackageInfo): String? {
        val signingInfo = info.signingInfo ?: return null
        val signers = signingInfo.apkContentsSigners ?: return null
        if (signers.isEmpty()) return null
        if (signers.size > 1) {
            Log.w(TAG, "Multiple APK signers detected (${signers.size}); only first is verified")
        }
        return ApkDigest.sha256Hex(signers[0].toByteArray())
    }

    /**
     * Returns true when any signer of the APK at [apkPath] matches [expectedFingerprint]
     * (case-insensitive), false when none match, or null when certificates cannot be read.
     */
    fun archiveMatchesAnySigner(
        context: Context,
        apkPath: String,
        expectedFingerprint: String
    ): Boolean? {
        return try {
            val info =
                context.packageManager.getPackageArchiveInfo(
                    apkPath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ) ?: return null
            val certs = allCertSha256FromInfo(info)
            if (certs.isEmpty()) return null
            matchesAnySigner(info, expectedFingerprint)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * L-17: Returns all signing certificate SHA-256 fingerprints for multi-signer APKs.
     * Returns empty list on failure.
     */
    fun allCertSha256FromInfo(info: PackageInfo): List<String> {
        val signingInfo = info.signingInfo ?: return emptyList()
        val signers = signingInfo.apkContentsSigners ?: return emptyList()
        return signers.map { ApkDigest.sha256Hex(it.toByteArray()) }
    }

    /**
     * L-17: Checks if any signer matches the expected fingerprint.
     */
    fun matchesAnySigner(
        info: PackageInfo,
        expectedFingerprint: String
    ): Boolean {
        return allCertSha256FromInfo(info).any { it.equals(expectedFingerprint, ignoreCase = true) }
    }
}
