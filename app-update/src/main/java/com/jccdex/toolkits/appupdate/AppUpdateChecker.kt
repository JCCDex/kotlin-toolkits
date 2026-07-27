package com.jccdex.toolkits.appupdate

import com.jccdex.toolkits.apkverify.ReleaseChecksums
import com.jccdex.toolkits.apkverify.ReleaseChecksumsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

sealed class AppUpdateCheckResult {
    data class UpdateAvailable(
        val remote: ReleaseChecksums,
        val apkDownloadUrl: String
    ) : AppUpdateCheckResult()

    data class AlreadyLatest(
        val remote: ReleaseChecksums
    ) : AppUpdateCheckResult()

    data class Failed(
        val message: String
    ) : AppUpdateCheckResult()
}

object AppUpdateChecker {
    fun evaluate(
        localVersionCode: Int,
        remote: ReleaseChecksums,
        apkDownloadUrl: String
    ): AppUpdateCheckResult =
        if (remote.versionCode > localVersionCode) {
            AppUpdateCheckResult.UpdateAvailable(remote, apkDownloadUrl)
        } else {
            AppUpdateCheckResult.AlreadyLatest(remote)
        }

    suspend fun check(
        localVersionCode: Int,
        checksumsUrl: String,
        apkDownloadUrl: String
    ): AppUpdateCheckResult =
        withContext(Dispatchers.IO) {
            val text = fetchText(checksumsUrl)
                ?: return@withContext AppUpdateCheckResult.Failed("Unable to fetch update metadata")
            val remote = ReleaseChecksumsParser.parse(text)
                ?: return@withContext AppUpdateCheckResult.Failed("Invalid update metadata")
            evaluate(localVersionCode, remote, apkDownloadUrl)
        }

    private fun fetchText(url: String): String? {
        val connection = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
