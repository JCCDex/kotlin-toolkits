package com.jccdex.toolkits.nft.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves SWTC NFT metadata URIs from chain via `erc_info`, independent of wallet ownership.
 * Aligns with CCDAOConnector `image-cache.js` (`resolveSwtcNft`).
 */
class SwtcChainNftClient(
    private val rpcNodes: List<String> = DEFAULT_RPC_NODES
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
        val connection = (URL(nodeUrl).openConnection() as? HttpURLConnection) ?: return null
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
