package com.jccdex.toolkits.nft.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

const val DEFAULT_IPFS_GATEWAY_BASE_URL = "https://ipfs.jccdex.cn/ipfs/"

private object NftMetadataImageCache {
    private val resolvedByMetadataUrl = ConcurrentHashMap<String, String?>()
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun getOrFetch(
        metadataUrl: String,
        fetch: suspend () -> String?
    ): String? {
        val key = metadataUrl.trim()
        if (key.isBlank()) {
            return null
        }
        if (resolvedByMetadataUrl.containsKey(key)) {
            return resolvedByMetadataUrl[key]
        }
        val mutex = fetchLocks.getOrPut(key) { Mutex() }
        return mutex.withLock {
            if (resolvedByMetadataUrl.containsKey(key)) {
                resolvedByMetadataUrl[key]
            } else {
                fetch()?.also { resolvedByMetadataUrl[key] = it }
            }
        }
    }
}

fun isLoadableRemoteAssetUrl(url: String?): Boolean {
    val value = url?.trim().orEmpty()
    return value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("data:", ignoreCase = true)
}

private fun String.looksLikeImageAssetUrl(): Boolean {
    val value = trim()
    if (!isLoadableRemoteAssetUrl(value)) {
        return false
    }
    if (value.startsWith("data:", ignoreCase = true)) {
        return true
    }
    val path = runCatching { URL(value).path.orEmpty() }.getOrNull()?.lowercase(Locale.ROOT).orEmpty()
    return path.endsWith(".png") ||
        path.endsWith(".jpg") ||
        path.endsWith(".jpeg") ||
        path.endsWith(".webp") ||
        path.endsWith(".gif") ||
        path.endsWith(".svg") ||
        path.endsWith(".avif") ||
        path.endsWith(".bmp")
}

fun normalizeRemoteAssetUrl(
    rawUrl: String?,
    baseUrl: String? = null
): String? {
    val value = rawUrl?.trim()?.takeIf { it.isNotBlank() && !it.looksLikeJsonPayload() } ?: return null
    return when {
        value.startsWith("ipfs://", ignoreCase = true) -> {
            val path = value.substringAfter("ipfs://", "").removePrefix("ipfs/").trimStart('/')
            path.takeIf { it.isNotBlank() }?.let { "$DEFAULT_IPFS_GATEWAY_BASE_URL$it" }
        }

        value.startsWith("/ipfs/", ignoreCase = true) -> {
            val path = value.removePrefix("/").removePrefix("ipfs/").trimStart('/')
            path.takeIf { it.isNotBlank() }?.let { "$DEFAULT_IPFS_GATEWAY_BASE_URL$it" }
        }

        value.startsWith("ipfs/", ignoreCase = true) -> {
            val path = value.removePrefix("ipfs/").trimStart('/')
            path.takeIf { it.isNotBlank() }?.let { "$DEFAULT_IPFS_GATEWAY_BASE_URL$it" }
        }

        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("data:", ignoreCase = true) ->
            canonicalizeHttpIpfsUrl(value) ?: value

        value.looksLikeIpfsIdentifier() -> "$DEFAULT_IPFS_GATEWAY_BASE_URL${value.trimStart('/')}"

        else -> resolveRelativeAssetUrl(baseUrl, value)
    }
}

fun extractMetadataImageUrl(
    metadataBody: String,
    metadataUri: String
): String? {
    val metadata = runCatching { JSONObject(metadataBody) }.getOrNull() ?: return null
    val payload = metadata.optJSONObject("data") ?: metadata
    return sequenceOf("image", "image_url", "imageUrl")
        .mapNotNull { key -> payload.optString(key).takeIf { it.isNotBlank() } }
        .mapNotNull { normalizeRemoteAssetUrl(it, metadataUri) }
        .firstOrNull()
}

suspend fun resolveRemoteImageUrl(
    imageUrl: String?,
    metadataUri: String?
): String? {
    val normalizedMetadataUri = normalizeRemoteAssetUrl(metadataUri)

    imageUrl
        ?.trim()
        ?.takeIf { it.looksLikeJsonPayload() }
        ?.let { extractMetadataImageUrl(it, normalizedMetadataUri.orEmpty()) }
        ?.let { inlineImage ->
            return inlineImage
        }

    normalizeRemoteAssetUrl(imageUrl, normalizedMetadataUri)?.let { resolved ->
        if (isLoadableRemoteAssetUrl(resolved)) {
            return resolved
        }
    }

    if (normalizedMetadataUri.isNullOrBlank()) {
        return null
    }
    if (normalizedMetadataUri.looksLikeImageAssetUrl()) {
        return normalizedMetadataUri
    }

    val metadataImage =
        runCatching {
            NftMetadataImageCache.getOrFetch(normalizedMetadataUri) {
                fetchMetadataImage(normalizedMetadataUri)
            }
        }.getOrNull()
    val resolved = normalizeRemoteAssetUrl(metadataImage, normalizedMetadataUri)
    if (isLoadableRemoteAssetUrl(resolved)) {
        return resolved
    }

    return null
}

object SsrfGuard {
    /** Replaced in tests to bypass SSRF check. */
    @Volatile var enabled: Boolean = true

    fun check(url: String): Boolean {
        if (!enabled) return true
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        if (parsed.protocol !in setOf("http", "https", "ipfs")) return false
        val host = parsed.host ?: return false
        if (host.isBlank()) return false
        // Fail closed: unresolved host must not be treated as safe.
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return !addr.isLoopbackAddress && !addr.isSiteLocalAddress && !addr.isLinkLocalAddress
    }
}

suspend fun fetchMetadataImage(metadataUrl: String): String? =
    withContext(Dispatchers.IO) {
        if (!SsrfGuard.check(metadataUrl)) return@withContext null
        val connection =
            (URL(metadataUrl).openConnection() as? HttpURLConnection)
                ?: return@withContext null
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299 || body.isBlank()) {
                return@withContext null
            }
            extractMetadataImageUrl(body, metadataUrl)
        } finally {
            connection.disconnect()
        }
    }

private fun String.looksLikeJsonPayload(): Boolean {
    val trimmed = trim()
    return trimmed.startsWith("{") || trimmed.startsWith("[")
}

private fun String.looksLikeIpfsIdentifier(): Boolean {
    val trimmed = trimStart('/')
    return trimmed.startsWith("bafy", ignoreCase = true) || trimmed.startsWith("Qm")
}

private fun resolveRelativeAssetUrl(
    baseUrl: String?,
    rawValue: String
): String =
    baseUrl
        ?.let { base -> runCatching { URL(URL(base), rawValue).toString() }.getOrNull() }
        ?: rawValue

private fun canonicalizeHttpIpfsUrl(rawUrl: String): String? {
    if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) {
        return null
    }
    val parsed = runCatching { URL(rawUrl) }.getOrNull() ?: return null
    val path = parsed.path.orEmpty()
    val marker = "/ipfs/"
    val index = path.lowercase(Locale.ROOT).indexOf(marker)
    if (index < 0) {
        return null
    }
    val ipfsPath = path.substring(index + marker.length).trimStart('/')
    if (ipfsPath.isBlank()) {
        return null
    }
    return "$DEFAULT_IPFS_GATEWAY_BASE_URL$ipfsPath"
}
