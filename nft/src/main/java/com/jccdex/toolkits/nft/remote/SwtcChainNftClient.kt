package com.jccdex.toolkits.nft.remote

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Resolves SWTC NFT metadata URIs from chain via `erc_info`, independent of wallet ownership.
 * Aligns with CCDAOConnector `image-cache.js` (`resolveSwtcNft`).
 */
class SwtcChainNftClient(
    private val rpcNodes: List<String> = DEFAULT_RPC_NODES,
    private val certificatePins: List<String> = emptyList()
) {
    suspend fun fetchMetadataUri(tokenId: String): String? =
        withContext(Dispatchers.IO) {
            val normalizedTokenId = tokenId.trim()
            if (normalizedTokenId.isBlank()) {
                return@withContext null
            }
            rpcNodes.firstNotNullOfOrNull { nodeUrl ->
                runCatching { requestErcInfoMetadataUri(nodeUrl, normalizedTokenId) }.getOrNull()
            }
        }

    private fun requestErcInfoMetadataUri(
        nodeUrl: String,
        tokenId: String
    ): String? {
        val body =
            JSONObject()
                .apply {
                    put("method", "erc_info")
                    put(
                        "params",
                        JSONArray().put(
                            JSONObject().apply {
                                put("tokenid", tokenId)
                            }
                        )
                    )
                }
        val response = postJson(nodeUrl, body) ?: return null
        if (response.has("error")) {
            return null
        }
        return parseErcInfoMetadataUri(response)
    }

    private fun postJson(
        nodeUrl: String,
        body: JSONObject
    ): JSONObject? {
        val connection = openPinnedConnection(nodeUrl) ?: return null
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(body.toString())
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299 || text.isBlank()) {
                return null
            }
            JSONObject(text)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun openPinnedConnection(nodeUrl: String): HttpURLConnection? {
        val connection = (URL(nodeUrl).openConnection() as? HttpURLConnection) ?: return null
        if (connection is HttpsURLConnection && certificatePins.isNotEmpty()) {
            connection.sslSocketFactory = createPinnedSslSocketFactory()
        }
        return connection
    }

    private fun createPinnedSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val defaultTrustManagers =
            run {
                val tmf =
                    javax.net.ssl.TrustManagerFactory
                        .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as java.security.KeyStore?)
                tmf.trustManagers.filterIsInstance<X509TrustManager>()
            }
        val pinnedTm = PinnedTrustManager(defaultTrustManagers.first(), certificatePins.toSet())
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
                val hash = "sha256/" + Base64.encodeToString(digest, Base64.NO_WRAP)
                if (hash in pins) return
            }
            throw SSLException("Certificate pinning failure")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    companion object {
        val DEFAULT_RPC_NODES =
            listOf(
                "https://srje115qd43qw2.swtc.top"
            )

        fun parseErcInfoMetadataUri(response: JSONObject): String? {
            val tokenInfosElement =
                response
                    .optJSONObject("result")
                    ?.optJSONObject("TokenInfo")
                    ?.opt("TokenInfos")
                    ?: return null
            val tokenInfosJson =
                when (tokenInfosElement) {
                    is JSONArray -> tokenInfosElement.toString()
                    is String -> tokenInfosElement.takeIf { it.isNotBlank() }
                    else -> tokenInfosElement.toString()
                } ?: return null
            return extractSwtcMetadataUri(tokenInfosJson)
        }
    }
}
