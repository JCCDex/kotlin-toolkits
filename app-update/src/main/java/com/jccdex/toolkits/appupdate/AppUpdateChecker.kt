package com.jccdex.toolkits.appupdate

import com.jccdex.toolkits.apkverify.ReleaseChecksums
import com.jccdex.toolkits.apkverify.ReleaseChecksumsParser
import com.jccdex.toolkits.core.net.HttpFetcher
import com.jccdex.toolkits.core.net.HttpResult
import com.jccdex.toolkits.core.net.RedirectPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_METADATA_BYTES = 1 * 1024 * 1024

    // C-2: HTTP converged to core HttpFetcher (same-host https redirects, 1MB metadata cap).
    private val httpFetcher =
        HttpFetcher(
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            maxResponseBytes = MAX_METADATA_BYTES,
            redirectPolicy = RedirectPolicy.SAME_HOST_HTTPS
        )

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
            val text =
                fetchText(checksumsUrl)
                    ?: return@withContext AppUpdateCheckResult.Failed("Unable to fetch update metadata")
            val remote =
                ReleaseChecksumsParser.parse(text)
                    ?: return@withContext AppUpdateCheckResult.Failed("Invalid update metadata")
            evaluate(localVersionCode, remote, apkDownloadUrl)
        }

    private fun fetchText(url: String): String? =
        when (val result = httpFetcher.get(url)) {
            is HttpResult.Success -> result.value
            is HttpResult.Failure -> null
        }
}
