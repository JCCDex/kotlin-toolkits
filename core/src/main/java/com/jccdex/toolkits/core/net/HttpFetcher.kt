package com.jccdex.toolkits.core.net

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Redirect policy per site (C-2): each caller keeps its previous behavior — zero host-visible change. */
enum class RedirectPolicy { NONE, SAME_HOST_HTTPS }

/** Outcome of an [HttpFetcher] request. */
sealed class HttpResult<out T> {
    data class Success<T>(val value: T) : HttpResult<T>()

    data class Failure(val error: HttpError) : HttpResult<Nothing>()
}

sealed class HttpError {
    data class HttpException(val code: Int, val message: String) : HttpError()

    data object SizeExceeded : HttpError()

    data object SsrfBlocked : HttpError()

    data object RedirectExceeded : HttpError()

    data object InvalidUrl : HttpError()
}

/**
 * Unified HTTP fetcher (C-2/C-17): single home for timeouts, size caps, HTTPS enforcement,
 * same-host redirect handling, an SSRF hook, and optional certificate pinning.
 *
 * Blocking (no suspension points) and pure JVM (`java.net`/`javax.net.ssl`/`java.util.Base64`) with
 * **zero main dependencies** — preserves core's "pure model module" contract. Callers on a coroutine
 * context must wrap calls in `withContext(Dispatchers.IO)`.
 */
class HttpFetcher(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxResponseBytes: Int = 5 * 1024 * 1024,
    private val httpsOnly: Boolean = true,
    private val redirectPolicy: RedirectPolicy = RedirectPolicy.SAME_HOST_HTTPS,
    private val maxRedirects: Int = 3,
    private val ssrfCheck: ((String) -> Boolean)? = null,
    private val certificatePins: Set<String> = emptySet()
) {
    fun get(url: String): HttpResult<String> = getBytesFollowingRedirects(url).map { String(it, Charsets.UTF_8) }

    fun getBytes(url: String): HttpResult<ByteArray> = getBytesFollowingRedirects(url)

    fun postJson(
        url: String,
        body: String
    ): HttpResult<String> = postJsonRequest(url, body).map { String(it, Charsets.UTF_8) }

    /**
     * Streams a response to [target] with a hard byte cap; on success [target] is complete.
     *
     * Does not buffer the full body in memory (unlike [getBytes]). Redirects follow [redirectPolicy].
     *
     * @param onProgress optional per-chunk progress callback `(bytesRead, contentLength?)`.
     * @param cancelCheck optional cooperative cancellation hook — thrown exceptions propagate
     *        (the hook should throw [java.util.concurrent.CancellationException] when cancelled).
     */
    fun downloadToFile(
        url: String,
        target: File,
        onProgress: ((bytesRead: Long, contentLength: Long?) -> Unit)? = null,
        cancelCheck: (() -> Unit)? = null
    ): HttpResult<File> {
        var current = url
        repeat(maxRedirects + 1) { hop ->
            val connection =
                try {
                    cancelCheck?.invoke()
                    if (ssrfCheck?.invoke(current) == false) {
                        return HttpResult.Failure(HttpError.SsrfBlocked)
                    }
                    openOnce(current) ?: return HttpResult.Failure(HttpError.InvalidUrl)
                } catch (e: java.util.concurrent.CancellationException) {
                    target.delete()
                    throw e
                }
            try {
                connection.requestMethod = "GET"
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirectPolicy == RedirectPolicy.NONE) {
                        return HttpResult.Failure(HttpError.HttpException(code, "HTTP $code"))
                    }
                    if (hop >= maxRedirects) {
                        return HttpResult.Failure(HttpError.RedirectExceeded)
                    }
                    val location =
                        connection.getHeaderField("Location")
                            ?: return HttpResult.Failure(HttpError.InvalidUrl)
                    current =
                        resolveSameHost(connection.url, location)?.toString()
                            ?: return HttpResult.Failure(HttpError.InvalidUrl)
                    return@repeat
                }
                if (code !in 200..299) {
                    return HttpResult.Failure(HttpError.HttpException(code, "HTTP $code"))
                }
                val contentLength =
                    connection.contentLengthLong.takeIf { it >= 0 }
                        ?: connection.contentLength.toLong().takeIf { it >= 0 }
                return streamBodyToFile(connection, target, contentLength, onProgress, cancelCheck)
            } catch (e: java.util.concurrent.CancellationException) {
                target.delete()
                throw e
            } catch (e: Exception) {
                target.delete()
                return HttpResult.Failure(HttpError.HttpException(-1, e.message ?: "request failed"))
            } finally {
                connection.disconnect()
            }
        }
        return HttpResult.Failure(HttpError.RedirectExceeded)
    }

    private fun streamBodyToFile(
        connection: HttpURLConnection,
        target: File,
        contentLength: Long?,
        onProgress: ((bytesRead: Long, contentLength: Long?) -> Unit)?,
        cancelCheck: (() -> Unit)?
    ): HttpResult<File> {
        val input: InputStream = connection.inputStream
        return try {
            var sizeExceeded = false
            target.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    cancelCheck?.invoke()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxResponseBytes) {
                        // Close the stream first, then delete — unlink-while-open can leave a
                        // remnant if delete fails while the FD is still held.
                        sizeExceeded = true
                        return@use
                    }
                    output.write(buffer, 0, n)
                    onProgress?.invoke(total, contentLength)
                }
            }
            if (sizeExceeded) {
                target.delete()
                return HttpResult.Failure(HttpError.SizeExceeded)
            }
            HttpResult.Success(target)
        } finally {
            input.close()
        }
    }

    private fun postJsonRequest(
        url: String,
        body: String
    ): HttpResult<ByteArray> {
        if (ssrfCheck?.invoke(url) == false) {
            return HttpResult.Failure(HttpError.SsrfBlocked)
        }
        val connection = openOnce(url) ?: return HttpResult.Failure(HttpError.InvalidUrl)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            val payload = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { stream ->
                stream.write(payload)
                stream.flush()
            }
            readBody(connection)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            HttpResult.Failure(HttpError.HttpException(-1, e.message ?: "request failed"))
        } finally {
            connection.disconnect()
        }
    }

    private fun getBytesFollowingRedirects(initialUrl: String): HttpResult<ByteArray> {
        var current = initialUrl
        repeat(maxRedirects + 1) { hop ->
            if (ssrfCheck?.invoke(current) == false) {
                return HttpResult.Failure(HttpError.SsrfBlocked)
            }
            val connection = openOnce(current) ?: return HttpResult.Failure(HttpError.InvalidUrl)
            try {
                connection.requestMethod = "GET"
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirectPolicy == RedirectPolicy.NONE) {
                        return HttpResult.Failure(HttpError.HttpException(code, "HTTP $code"))
                    }
                    if (hop >= maxRedirects) {
                        return HttpResult.Failure(HttpError.RedirectExceeded)
                    }
                    val location =
                        connection.getHeaderField("Location")
                            ?: return HttpResult.Failure(HttpError.InvalidUrl)
                    current =
                        resolveSameHost(connection.url, location)?.toString()
                            ?: return HttpResult.Failure(HttpError.InvalidUrl)
                    return@repeat
                }
                return readBody(connection, code)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                return HttpResult.Failure(HttpError.HttpException(-1, e.message ?: "request failed"))
            } finally {
                connection.disconnect()
            }
        }
        return HttpResult.Failure(HttpError.RedirectExceeded)
    }

    private fun openOnce(url: String): HttpURLConnection? {
        val parsed =
            try {
                URL(url)
            } catch (e: Exception) {
                return null
            }
        if (httpsOnly && parsed.protocol != "https") return null
        if (parsed.host.isNullOrBlank()) return null
        val connection = (parsed.openConnection() as? HttpURLConnection) ?: return null
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        if (certificatePins.isNotEmpty() && connection is HttpsURLConnection) {
            // Build once, reuse across requests (P-12N).
            connection.sslSocketFactory = pinnedSslSocketFactory
        }
        return connection
    }

    private val pinnedSslSocketFactory: SSLSocketFactory by lazy {
        createPinnedSslSocketFactory()
    }

    /** Resolves [location] against [base] only if it stays https and on the same host. */
    private fun resolveSameHost(
        base: URL,
        location: String
    ): URL? {
        val resolved =
            try {
                base.toURI().resolve(location).toURL()
            } catch (e: Exception) {
                return null
            }
        if (httpsOnly && resolved.protocol != "https") return null
        if (!resolved.host.equals(base.host, ignoreCase = true)) return null
        return resolved
    }

    /** Reads the response body with a byte cap; returns [HttpError.SizeExceeded] when over. */
    private fun readBody(connection: HttpURLConnection): HttpResult<ByteArray> =
        readBody(connection, connection.responseCode)

    private fun readBody(
        connection: HttpURLConnection,
        code: Int
    ): HttpResult<ByteArray> {
        if (code !in 200..299) {
            return HttpResult.Failure(HttpError.HttpException(code, "HTTP $code"))
        }
        val input: InputStream = connection.inputStream
        return try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxResponseBytes) {
                    return HttpResult.Failure(HttpError.SizeExceeded)
                }
                out.write(buffer, 0, n)
            }
            HttpResult.Success(out.toByteArray())
        } finally {
            input.close()
        }
    }

    private fun createPinnedSslSocketFactory(): SSLSocketFactory {
        val defaultTrustManagers =
            run {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                tmf.trustManagers.filterIsInstance<X509TrustManager>()
            }
        // Guard against ROMs returning no default trust manager (M-16N): fail with a clear error.
        val delegate =
            defaultTrustManagers.firstOrNull()
                ?: throw IllegalStateException("No default X509 trust manager available for pinning")
        val pinnedTm = PinnedTrustManager(delegate, certificatePins)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(pinnedTm), null)
        return sslContext.socketFactory
    }

    private class PinnedTrustManager(
        private val delegate: X509TrustManager,
        private val pins: Set<String>
    ) : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String
        ) {
            delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String
        ) {
            delegate.checkServerTrusted(chain, authType)
            for (cert in chain) {
                val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
                val hash = "sha256/" + Base64.getEncoder().encodeToString(digest)
                if (hash in pins) return
            }
            throw SSLException("Certificate pinning failure")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }
}

/** Maps a [HttpResult] to [T] (shortcut for the common null-on-failure pattern). */
inline fun <T, R> HttpResult<T>.map(transform: (T) -> R): HttpResult<R> =
    when (this) {
        is HttpResult.Success -> HttpResult.Success(transform(value))
        is HttpResult.Failure -> this
    }
