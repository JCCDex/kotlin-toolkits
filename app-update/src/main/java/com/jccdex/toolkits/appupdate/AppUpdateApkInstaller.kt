package com.jccdex.toolkits.appupdate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jccdex.toolkits.apkverify.ApkSigningFingerprint
import com.jccdex.toolkits.apkverify.JniVerifier
import com.jccdex.toolkits.apkverify.OfficialReleaseManifestLoader
import com.jccdex.toolkits.apkverify.ReleaseChecksums
import com.jccdex.toolkits.core.net.HttpError
import com.jccdex.toolkits.core.net.HttpFetcher
import com.jccdex.toolkits.core.net.HttpResult
import com.jccdex.toolkits.core.net.RedirectPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

sealed class AppUpdateDownloadResult {
    data class Success(val apkFile: File) : AppUpdateDownloadResult()

    data class Failed(val message: String) : AppUpdateDownloadResult()
}

object AppUpdateApkInstaller {
    private const val CACHE_DIR_NAME = "apk-updates"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private val FILE_NAME_SAFE = Regex("^[A-Za-z0-9._-]+$")

    // C-2: HTTP converged to core HttpFetcher (same-host https redirects, 200MB APK cap).
    private val httpFetcher =
        HttpFetcher(
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            maxResponseBytes = MAX_APK_BYTES.toInt(),
            redirectPolicy = RedirectPolicy.SAME_HOST_HTTPS
        )

    fun updateCacheDir(context: Context): File = File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }

    /** Prunes old APK files in the cache dir, keeping at most [maxKeep] most recently modified. */
    fun pruneCache(
        context: Context,
        maxKeep: Int = 2
    ) {
        val cacheDir = updateCacheDir(context)
        cacheDir.listFiles { f -> f.isFile && f.extension == "apk" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(maxKeep)
            ?.forEach { it.delete() }
    }

    /** Returns the cache file name for an APK, or null if [apkNamePrefix]/[versionName] contain
     *  path characters (e.g. `..`, `/`) that could escape the cache directory. */
    internal fun safeApkFileName(
        apkNamePrefix: String,
        versionName: String
    ): String? {
        if (!FILE_NAME_SAFE.matches(apkNamePrefix) || !FILE_NAME_SAFE.matches(versionName)) return null
        return "$apkNamePrefix-v$versionName.apk"
    }

    fun canRequestInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    suspend fun downloadAndVerify(
        context: Context,
        apkUrl: String,
        remote: ReleaseChecksums,
        apkNamePrefix: String,
        onProgress: (bytesRead: Long, contentLength: Long?) -> Unit
    ): AppUpdateDownloadResult =
        withContext(Dispatchers.IO) {
            val cacheDir = updateCacheDir(context)
            val fileName =
                safeApkFileName(apkNamePrefix, remote.versionName)
                    ?: return@withContext AppUpdateDownloadResult.Failed("Invalid APK file name (unsafe characters)")
            val target = File(cacheDir, fileName)
            if (target.canonicalPath.startsWith(cacheDir.canonicalPath).not()) {
                return@withContext AppUpdateDownloadResult.Failed("Invalid APK file path")
            }
            if (target.exists()) target.delete()
            val temp = File(target.parentFile, "${target.name}.tmp")
            if (temp.exists()) temp.delete()

            try {
                when (val downloadResult =
                    httpFetcher.downloadToFile(
                        apkUrl,
                        temp,
                        onProgress = { read, length -> onProgress(read, length) },
                        cancelCheck = { coroutineContext.ensureActive() }
                    )) {
                    is HttpResult.Success -> Unit
                    is HttpResult.Failure -> {
                        // Local val so the when can smart-cast (cross-module public property cannot).
                        val error: HttpError = downloadResult.error
                        val failed =
                            when (error) {
                                is HttpError.HttpException ->
                                    AppUpdateDownloadResult.Failed("Download failed (${error.code})")
                                is HttpError.SizeExceeded -> AppUpdateDownloadResult.Failed("APK exceeds size limit")
                                else -> AppUpdateDownloadResult.Failed("Unable to open download")
                            }
                        temp.delete()
                        return@withContext failed
                    }
                }
                if (!temp.exists() || temp.length() <= 0L) {
                    temp.delete()
                    return@withContext AppUpdateDownloadResult.Failed("Downloaded file is empty")
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }

                val actualSha = JniVerifier.computeSha256(target)
                if (!JniVerifier.hashEquals(actualSha, remote.apkSha256)) {
                    target.delete()
                    return@withContext AppUpdateDownloadResult.Failed("APK checksum mismatch")
                }

                // Signing cert check, fail-closed: when a trusted cert is known, an APK whose cert
                // cannot be extracted is REJECTED (not silently accepted). Exceptions here propagate to
                // the outer catch, which deletes the file and fails the download.
                val expectedCert = OfficialReleaseManifestLoader.load(context)?.signingCertSha256
                if (!expectedCert.isNullOrBlank()) {
                    when (
                        ApkSigningFingerprint.archiveMatchesAnySigner(
                            context,
                            target.absolutePath,
                            expectedCert
                        )
                    ) {
                        true -> Unit
                        false -> {
                            target.delete()
                            return@withContext AppUpdateDownloadResult.Failed("APK signing certificate mismatch")
                        }
                        null -> {
                            target.delete()
                            return@withContext AppUpdateDownloadResult.Failed("Unable to verify APK signing certificate")
                        }
                    }
                }

                pruneCache(context)
                AppUpdateDownloadResult.Success(target)
            } catch (cancelled: CancellationException) {
                temp.delete()
                target.delete()
                throw cancelled
            } catch (e: Exception) {
                temp.delete()
                target.delete()
                AppUpdateDownloadResult.Failed("Download failed: ${e.javaClass.simpleName}")
            }
        }

    /** Returns whether [apkFile]'s signing cert matches the installed app's cert, or null when the
     *  comparison cannot be established. Callers must abort the upgrade on null rather than assume
     *  compatibility (fail-closed). */
    fun isSigningCompatibleWithInstalled(
        context: Context,
        apkFile: File
    ): Boolean? {
        return try {
            val installedInfo =
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
            val archiveInfo =
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                ) ?: return null
            val installedCerts = ApkSigningFingerprint.allCertSha256FromInfo(installedInfo)
            val archiveCerts = ApkSigningFingerprint.allCertSha256FromInfo(archiveInfo)
            if (installedCerts.isEmpty() || archiveCerts.isEmpty()) return null
            installedCerts.any { installed ->
                archiveCerts.any { archive -> JniVerifier.hashEquals(installed, archive) }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Launches the system install intent for [apkFile].
     * @return true if the intent was launched; false if the host lacks [canRequestPackageInstalls]
     *   permission (M-W5 — previously the install would throw at startActivity time instead).
     */
    fun startInstall(
        context: Context,
        apkFile: File
    ): Boolean {
        if (!canRequestInstall(context)) {
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
        return true
    }
}
