package com.jccdex.toolkits.nft.remote

import com.google.gson.JsonObject
import com.jccdex.toolkits.core.json.optStringSafe
import com.jccdex.toolkits.core.net.HttpFetcher
import com.jccdex.toolkits.core.net.HttpResult
import com.jccdex.toolkits.core.net.RedirectPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

const val DEFAULT_IPFS_GATEWAY_BASE_URL = "https://ipfs.jccdex.cn/ipfs/"

// NFT metadata/image hosts vary (http tokenUri, IPFS gateways, etc.) — SSRF stays off here to
// match pre-hardening behaviour; Coil loads the resolved URL on device.
private val httpFetcher =
    HttpFetcher(
        maxResponseBytes = MAX_HTTP_RESPONSE_CHARS,
        httpsOnly = false,
        redirectPolicy = RedirectPolicy.NONE,
        ssrfCheck = null
    )

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
    if (value.startsWith("http://", ignoreCase = true)) return true
    if (value.startsWith("https://", ignoreCase = true)) return true
    if (value.startsWith("data:", ignoreCase = true)) {
        return value.length <= MAX_DATA_URL_LENGTH
    }
    return false
}

/** M-12N: cap on inline data: URLs (length ≈ decoded size for base64). */
private const val MAX_DATA_URL_LENGTH = 1024 * 1024 // 1 MB

private val METADATA_IMAGE_KEYS = listOf("image", "image_url", "imageUrl")

private fun JSONObject.metadataPayload(): JSONObject = optJSONObject("data") ?: this

private fun JSONObject.firstNonBlankImageField(): String? =
    METADATA_IMAGE_KEYS.firstNotNullOfOrNull { key -> optStringSafe(key) }

private fun JsonObject.metadataPayload(): JsonObject = get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: this

private fun JsonObject.firstNonBlankImageField(): String? =
    METADATA_IMAGE_KEYS.firstNotNullOfOrNull { key ->
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotBlank() }
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
    return extractMetadataImageUrl(metadata, metadataUri)
}

fun extractMetadataImageUrl(
    metadata: JSONObject,
    metadataUri: String
): String? =
    metadata
        .metadataPayload()
        .firstNonBlankImageField()
        ?.let { normalizeRemoteAssetUrl(it, metadataUri) }

fun extractMetadataImageUrl(
    metadata: JsonObject,
    metadataUri: String
): String? =
    metadata
        .metadataPayload()
        .firstNonBlankImageField()
        ?.let { normalizeRemoteAssetUrl(it, metadataUri) }

/**
 * Normalizes [rawUrl] for host image loaders (Coil). Same as [normalizeRemoteAssetUrl] but omits
 * non-loadable schemes.
 */
fun normalizeDisplayRemoteAssetUrl(
    rawUrl: String?,
    baseUrl: String? = null
): String? = normalizeRemoteAssetUrl(rawUrl, baseUrl)?.takeIf { isLoadableRemoteAssetUrl(it) }

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
            if (isLoadableRemoteAssetUrl(inlineImage)) {
                return inlineImage
            }
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
        }.onFailure { if (it is CancellationException) throw it }
            .getOrNull()
    val resolved = normalizeRemoteAssetUrl(metadataImage, normalizedMetadataUri)
    if (isLoadableRemoteAssetUrl(resolved)) {
        return resolved
    }

    return null
}

object SsrfGuard {
    /** Replaced in tests to bypass SSRF check (internal: hosts cannot disable at runtime). */
    @Volatile internal var enabled: Boolean = true

    fun check(url: String): Boolean {
        if (!enabled) return true
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        if (parsed.protocol !in setOf("http", "https")) return false
        val host = parsed.host ?: return false
        if (host.isBlank()) return false
        // Fail closed: unresolved host must not be treated as safe.
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return !addr.isLoopbackAddress && !addr.isSiteLocalAddress && !addr.isLinkLocalAddress
    }
}

suspend fun fetchMetadataImage(metadataUrl: String): String? =
    withContext(Dispatchers.IO) {
        when (val result = httpFetcher.get(metadataUrl)) {
            is HttpResult.Success ->
                result.value
                    .takeIf { it.isNotBlank() }
                    ?.let { extractMetadataImageUrl(it, metadataUrl) }
            is HttpResult.Failure -> null
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

/** Maximum chars accepted from a single HTTP response body (M-3/M-9N OOM guard). */
internal const val MAX_HTTP_RESPONSE_CHARS = 5 * 1024 * 1024

/** Reads [this] reader, aborting (null) if it exceeds [maxChars] (defense against OOM / DoS). */
internal fun BufferedReader.readTextLimited(maxChars: Int = MAX_HTTP_RESPONSE_CHARS): String? {
    val sb = StringBuilder()
    val buf = CharArray(8192)
    var total = 0
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > maxChars) return null
        sb.append(buf, 0, n)
    }
    return sb.toString()
}
