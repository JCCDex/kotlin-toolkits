package com.jccdex.toolkits.webviewbridge

import android.content.Context
import org.json.JSONObject

object WebviewBridgeEngine {
    private val defaultClient = WebviewBridgeClient(JsPromiseGateway)

    fun initialize(
        context: Context,
        config: WebviewBridgeConfig = WebviewBridgeConfig()
    ) = defaultClient.initialize(context, config)

    internal fun isInitializedForTest(): Boolean = defaultClient.isInitializedForTest()

    internal fun currentConfigForTest(): WebviewBridgeConfig = defaultClient.currentConfigForTest()

    fun start() = defaultClient.start()

    suspend fun callJsMethod(
        method: String,
        params: JSONObject? = null,
        timeoutMs: Long = 30_000L,
        readyWaitMs: Long = 15_000L
    ): String = defaultClient.callJsMethod(method, params, timeoutMs, readyWaitMs)

    suspend fun <T> callJsMethodAs(
        method: String,
        params: JSONObject? = null,
        clazz: Class<T>,
        timeoutMs: Long = 30_000L,
        readyWaitMs: Long = 15_000L
    ): T = defaultClient.callJsMethodAs(method, params, clazz, timeoutMs, readyWaitMs)

    fun destroy() = defaultClient.destroy()
}
