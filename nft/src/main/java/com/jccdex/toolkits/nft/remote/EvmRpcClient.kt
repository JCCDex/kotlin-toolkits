package com.jccdex.toolkits.nft.remote

import com.jccdex.toolkits.core.json.optStringSafe
import com.jccdex.toolkits.core.net.HttpFetcher
import com.jccdex.toolkits.core.net.HttpResult
import com.jccdex.toolkits.core.net.RedirectPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class EvmRpcClient(
    private val rpcUrls: List<String>,
    private val connectTimeout: Int = 10_000,
    private val readTimeout: Int = 10_000
) {
    // C-2: HTTP converged to core HttpFetcher (http allowed as before; same-host redirects; size cap added).
    private val httpFetcher =
        HttpFetcher(
            connectTimeoutMs = connectTimeout,
            readTimeoutMs = readTimeout,
            httpsOnly = false,
            redirectPolicy = RedirectPolicy.SAME_HOST_HTTPS
        )

    suspend fun ethCall(
        contract: String,
        data: String
    ): String? =
        withContext(Dispatchers.IO) {
            rpcUrls.firstNotNullOfOrNull { rpcUrl ->
                runCatching {
                    fetchRpcResult(rpcUrl, contract, data)
                }.onFailure {
                    if (it is CancellationException) throw it
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
                }.onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            }
        }

    private fun fetchRpcResult(
        rpcUrl: String,
        contract: String,
        data: String
    ): String? {
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
        return when (val result = httpFetcher.postJson(rpcUrl, body)) {
            is HttpResult.Success -> JSONObject(result.value).optStringSafe("result")
            is HttpResult.Failure -> null
        }
    }

    private fun fetchJsonRpc(
        rpcUrl: String,
        method: String,
        params: List<Any>
    ): JSONObject? {
        val body =
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", JSONArray(params))
                put("id", 1)
            }.toString()
        return when (val result = httpFetcher.postJson(rpcUrl, body)) {
            is HttpResult.Success -> JSONObject(result.value)
            is HttpResult.Failure -> null
        }
    }
}
