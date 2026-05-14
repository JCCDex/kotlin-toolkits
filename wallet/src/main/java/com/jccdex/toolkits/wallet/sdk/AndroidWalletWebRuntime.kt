package com.jccdex.toolkits.wallet.sdk

import android.content.Context
import com.jccdex.toolkits.webviewbridge.WebviewBridgeClient
import com.jccdex.toolkits.webviewbridge.WebviewBridgeConfig
import com.jccdex.toolkits.webviewbridge.androidAssetUrl
import org.json.JSONObject

internal interface IWalletBridge {
    fun start()

    suspend fun call(
        method: String,
        params: JSONObject? = null,
        timeoutMs: Long = 30_000L,
        readyWaitMs: Long = 15_000L
    ): String

    suspend fun <T> callAs(
        method: String,
        params: JSONObject? = null,
        clazz: Class<T>,
        timeoutMs: Long = 30_000L,
        readyWaitMs: Long = 15_000L
    ): T

    fun destroy()
}

internal class AndroidWalletWebRuntime(
    context: Context
) : IWalletBridge {
    private val client =
        WebviewBridgeClient().apply {
            initialize(
                context = context.applicationContext,
                config = WebviewBridgeConfig(bridgeUrl = androidAssetUrl("wallet-bridge.html"))
            )
            start()
        }

    override fun start() = client.start()

    override suspend fun call(
        method: String,
        params: JSONObject?,
        timeoutMs: Long,
        readyWaitMs: Long
    ): String = client.callJsMethod(method, params, timeoutMs, readyWaitMs)

    override suspend fun <T> callAs(
        method: String,
        params: JSONObject?,
        clazz: Class<T>,
        timeoutMs: Long,
        readyWaitMs: Long
    ): T = client.callJsMethodAs(method, params, clazz, timeoutMs, readyWaitMs)

    override fun destroy() = client.destroy()
}
