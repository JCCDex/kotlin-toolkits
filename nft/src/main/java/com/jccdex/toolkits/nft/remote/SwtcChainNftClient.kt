package com.jccdex.toolkits.nft.remote

import com.jccdex.toolkits.core.model.ChainDefaults
import com.jccdex.toolkits.core.net.HttpFetcher
import com.jccdex.toolkits.core.net.HttpResult
import com.jccdex.toolkits.core.net.RedirectPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolves SWTC NFT metadata URIs from chain via `erc_info`, independent of wallet ownership.
 * Aligns with CCDAOConnector `image-cache.js` (`resolveSwtcNft`).
 */
class SwtcChainNftClient private constructor(
    private val rpcNodes: List<String>,
    private val certificatePins: List<String>,
    enforceHttps: Boolean
) {
    init {
        // M-10N: RPC nodes must be https (defaults are). The only public entry (create) always
        // enforces; the internal createForTest seam is for http-only MockWebServer tests.
        if (enforceHttps) {
            val httpNode = rpcNodes.firstOrNull { !it.startsWith("https://", ignoreCase = true) }
            require(httpNode == null) { "SWTC RPC node must use https; got: $httpNode" }
        }
    }

    // C-2/C-17: HTTP + cert pinning converged to core HttpFetcher (http allowed as before; same-host redirects).
    private val httpFetcher =
        HttpFetcher(
            connectTimeoutMs = 15_000,
            readTimeoutMs = 15_000,
            maxResponseBytes = MAX_HTTP_RESPONSE_CHARS,
            httpsOnly = false,
            redirectPolicy = RedirectPolicy.SAME_HOST_HTTPS,
            certificatePins = certificatePins.toSet()
        )

    suspend fun fetchMetadataUri(tokenId: String): String? =
        withContext(Dispatchers.IO) {
            val normalizedTokenId = tokenId.trim()
            if (normalizedTokenId.isBlank()) {
                return@withContext null
            }
            rpcNodes.firstNotNullOfOrNull { nodeUrl ->
                runCatching { requestErcInfoMetadataUri(nodeUrl, normalizedTokenId) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
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
    ): JSONObject? =
        when (val result = httpFetcher.postJson(nodeUrl, body.toString())) {
            is HttpResult.Success -> runCatching { JSONObject(result.value) }.getOrNull()
            is HttpResult.Failure -> null
        }

    companion object {
        /**
         * M-10N: public entry — always enforces https node URLs (default nodes are already https).
         * The constructor is private so hosts cannot bypass the check.
         */
        fun create(
            rpcNodes: List<String> = ChainDefaults.Swtc.getRpcUrls(),
            certificatePins: List<String> = emptyList()
        ): SwtcChainNftClient = SwtcChainNftClient(rpcNodes, certificatePins, enforceHttps = true)

        /** M-10N: internal test seam — MockWebServer is http-only. Not reachable outside the module. */
        internal fun createForTest(
            rpcNodes: List<String>,
            certificatePins: List<String> = emptyList()
        ): SwtcChainNftClient = SwtcChainNftClient(rpcNodes, certificatePins, enforceHttps = false)

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
