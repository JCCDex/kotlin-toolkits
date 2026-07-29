package com.jccdex.toolkits.appupdate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jccdex.toolkits.apkverify.ApkDigest
import com.jccdex.toolkits.apkverify.ApkSigningFingerprint
import com.jccdex.toolkits.apkverify.JniVerifier
import com.jccdex.toolkits.apkverify.OfficialReleaseManifestLoader
import com.jccdex.toolkits.apkverify.ReleaseChecksums
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

sealed class AppUpdateDownloadResult {
    data class Success(val apkFile: File) : AppUpdateDownloadResult()
    data class Failed(val message: String) : AppUpdateDownloadResult()
}

object AppUpdateApkInstaller {
    private const val CACHE_DIR_NAME = "apk-updates"
    private const val BUFFER_SIZE = 64 * 1024

    fun updateCacheDir(context: Context): File =
        File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }

    fun canRequestInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    suspend fun downloadAndVerify(
        context: Context,
        apkUrl: String,
        remote: ReleaseChecksums,
        apkNamePrefix: String,
        onProgress: (bytesRead: Long, contentLength: Long?) -> Unit
    ): AppUpdateDownloadResult = withContext(Dispatchers.IO) {
        val target = File(updateCacheDir(context), "${apkNamePrefix}-v${remote.versionName}.apk")
        if (target.exists()) target.delete()
        val temp = File(target.parentFile, "${target.name}.tmp")
        if (temp.exists()) temp.delete()

        val connection = (URL(apkUrl).openConnection() as? HttpURLConnection)
            ?: return@withContext AppUpdateDownloadResult.Failed("Unable to open download")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            connection.connect()
            if (connection.responseCode !in 200..299) {
                return@withContext AppUpdateDownloadResult.Failed("Download failed (${connection.responseCode})")
            }
            val contentLength = connection.contentLengthLong.takeIf { it > 0L }
            var downloaded = 0L
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, contentLength)
                    }
                }
            }
            if (!temp.exists() || temp.length() <= 0L) {
                return@withContext AppUpdateDownloadResult.Failed("Downloaded file is empty")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }

            val actualSha = ApkDigest.sha256Hex(target)
            if (!JniVerifier.hashEquals(actualSha, remote.apkSha256)) {
                target.delete()
                return@withContext AppUpdateDownloadResult.Failed("APK checksum mismatch")
            }

            // Best-effort signing cert check (may throw on some API levels)
            try {
                val expectedCert = OfficialReleaseManifestLoader.load(context)?.signingCertSha256.orEmpty()
                if (expectedCert.isNotBlank()) {
                    val actualCert = ApkSigningFingerprint.archiveCertSha256(context, target.absolutePath).orEmpty()
                    if (actualCert.isNotBlank() && !JniVerifier.hashEquals(actualCert, expectedCert)) {
                        target.delete()
                        return@withContext AppUpdateDownloadResult.Failed("APK signing certificate mismatch")
                    }
                }
            } catch (_: Exception) {
                // ignore — SHA-256 is the primary check
            }

            AppUpdateDownloadResult.Success(target)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            temp.delete(); target.delete(); throw cancelled
        } catch (e: Exception) {
            temp.delete(); target.delete()
            AppUpdateDownloadResult.Failed("Download failed: ${e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    fun isSigningCompatibleWithInstalled(context: Context, apkFile: File): Boolean = runCatching {
        val installed = ApkSigningFingerprint.installedReleaseCertSha256(context) ?: return true
        val archive = ApkSigningFingerprint.archiveCertSha256(context, apkFile.absolutePath) ?: return true
        JniVerifier.hashEquals(installed, archive)
    }.getOrDefault(true)

    fun startInstall(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
