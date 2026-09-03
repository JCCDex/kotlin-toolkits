package com.jccdex.toolkits.wallet.sdk

import android.content.Context
import com.jccdex.toolkits.webviewbridge.SharedWebviewBridge
import com.jccdex.toolkits.webviewbridge.WebviewBridgeClient
import com.jccdex.toolkits.webviewbridge.WebviewBridgeConfig
import com.jccdex.toolkits.webviewbridge.androidAssetUrl
import org.json.JSONObject

internal interface IWalletBridge {
    fun initialize(
        context: Context,
        config: WebviewBridgeConfig = WebviewBridgeConfig()
    )

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

internal class RealWalletWebBridgeClient private constructor(
    private val appContext: Context?,
    private val ownedClient: WebviewBridgeClient?
) : IWalletBridge {
    constructor(context: Context) : this(context.applicationContext, null)

    constructor(client: WebviewBridgeClient) : this(appContext = null, ownedClient = client)

    private val ownsClient: Boolean get() = ownedClient != null

    private fun bridge(): WebviewBridgeClient = ownedClient ?: SharedWebviewBridge.client(checkNotNull(appContext))

    override fun initialize(
        context: Context,
        config: WebviewBridgeConfig
    ) {
        if (ownsClient) {
            bridge().initialize(context, config)
        }
    }

    override fun start() {
        if (ownsClient) {
            bridge().start()
        }
    }

    override suspend fun call(
        method: String,
        params: JSONObject?,
        timeoutMs: Long,
        readyWaitMs: Long
    ): String = bridge().callJsMethod(method, params, timeoutMs, readyWaitMs)

    override suspend fun <T> callAs(
        method: String,
        params: JSONObject?,
        clazz: Class<T>,
        timeoutMs: Long,
        readyWaitMs: Long
    ): T = bridge().callJsMethodAs(method, params, clazz, timeoutMs, readyWaitMs)

    override fun destroy() {
        if (ownsClient) {
            bridge().destroy()
        }
    }
}

internal class AndroidWalletWebRuntime(
    context: Context,
    clientFactory: ((Context) -> IWalletBridge)? = null
) : IWalletBridge {
    private val client: IWalletBridge =
        clientFactory?.invoke(context)?.apply {
            initialize(
                context = context.applicationContext,
                config = WebviewBridgeConfig(bridgeUrl = androidAssetUrl("wallet-bridge.html"))
            )
            start()
        } ?: RealWalletWebBridgeClient(context)

    override fun initialize(
        context: Context,
        config: WebviewBridgeConfig
    ) = client.initialize(context, config)

    override fun start() = client.start()

    override suspend fun call(
        method: String,
        params: JSONObject?,
        timeoutMs: Long,
        readyWaitMs: Long
    ): String = client.call(method, params, timeoutMs, readyWaitMs)

    override suspend fun <T> callAs(
        method: String,
        params: JSONObject?,
        clazz: Class<T>,
        timeoutMs: Long,
        readyWaitMs: Long
    ): T = client.callAs(method, params, clazz, timeoutMs, readyWaitMs)

    override fun destroy() = client.destroy()
}
