package com.jccdex.toolkits.nft.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class EvmRpcClient(
    private val rpcUrls: List<String>,
    private val connectTimeout: Int = 10_000,
    private val readTimeout: Int = 10_000
) {
    suspend fun ethCall(
        contract: String,
        data: String
    ): String? =
        withContext(Dispatchers.IO) {
            rpcUrls.firstNotNullOfOrNull { rpcUrl ->
                runCatching {
                    fetchRpcResult(rpcUrl, contract, data)
                }.onFailure {
                    println("EvmRpcClient: RPC failed for chain, fallback to next node")
                }.getOrNull()
            }
        }

    suspend fun call(
        method: String,
        params: List<Any>
    ): JSONObject? =
        withContext(Dispatchers.IO) {
            rpcUrls.firstNotNullOfOrNull { rpcUrl ->
                runCatching {
                    fetchJsonRpc(rpcUrl, method, params)
                }.getOrNull()
            }
        }

    private fun fetchRpcResult(
        rpcUrl: String,
        contract: String,
        data: String
    ): String? {
        val connection = (URL(rpcUrl).openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeout
            connection.readTimeout = readTimeout
            connection.doOutput = true
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Content-Type", "application/json")

            val body =
                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", "eth_call")
                    put(
                        "params",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("to", contract)
                                    put("data", data)
                                }
                            )
                            put("latest")
                        }
                    )
                    put("id", 1)
                }.toString()

            connection.outputStream.bufferedWriter().use { it.write(body) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299 || response.isBlank()) {
                return null
            }

            JSONObject(response).optString("result").takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchJsonRpc(
        rpcUrl: String,
        method: String,
        params: List<Any>
    ): JSONObject? {
        val connection = (URL(rpcUrl).openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeout
            connection.readTimeout = readTimeout
            connection.doOutput = true
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Content-Type", "application/json")

            val body =
                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", method)
                    put("params", JSONArray(params))
                    put("id", 1)
                }.toString()

            connection.outputStream.bufferedWriter().use { it.write(body) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299 || response.isBlank()) {
                return null
            }

            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        val DEFAULT_RPC_NODES =
            mapOf(
                1L to
                    listOf(
                        "https://ethereum.publicnode.com",
                        "https://eth.llamarpc.com"
                    ),
                137L to
                    listOf(
                        "https://polygon-rpc.com",
                        "https://polygon.publicnode.com"
                    ),
                56L to
                    listOf(
                        "https://bsc-dataseed.binance.org"
                    ),
                8453L to
                    listOf(
                        "https://mainnet.base.org"
                    ),
                42161L to
                    listOf(
                        "https://arb1.arbitrum.io/rpc"
                    )
            )
    }
}
