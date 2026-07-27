package com.jccdex.toolkits.apkverify

import android.content.Context
import android.content.pm.PackageManager

/**
 * Extracts the release signing certificate SHA-256 fingerprint from
 * installed packages and APK archive files.
 *
 * Requires API ≥ 28 (minSdk 30 satisfies it).
 */
object ApkSigningFingerprint {
    /**
     * Returns the lowercase hex SHA-256 of the installed application's
     * first signing certificate, or null if it cannot be read.
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
            ApkDigest.sha256Hex(signers[0].toByteArray())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns the lowercase hex SHA-256 of the first signing certificate
     * of an uninstalled APK file at [apkPath], or null on failure.
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
            val signingInfo = info.signingInfo ?: return null
            val signers = signingInfo.apkContentsSigners ?: return null
            if (signers.isEmpty()) return null
            ApkDigest.sha256Hex(signers[0].toByteArray())
        } catch (_: Exception) {
            null
        }
    }
}
