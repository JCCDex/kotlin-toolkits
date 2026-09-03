package com.jccdex.toolkits.did.sdk

import android.content.Context
import com.jccdex.toolkits.did.port.IDidBridge
import com.jccdex.toolkits.did.service.IDidResolver
import com.jccdex.toolkits.webviewbridge.SharedWebviewBridge
import com.jccdex.toolkits.webviewbridge.WebviewBridgeClient
import com.jccdex.toolkits.webviewbridge.WebviewBridgeConfig
import com.jccdex.toolkits.webviewbridge.androidAssetUrl
import org.json.JSONObject

internal interface IDidWebBridge {
    fun initialize(
        context: Context,
        config: WebviewBridgeConfig = WebviewBridgeConfig()
    )

    fun start()

    suspend fun call(
        method: String,
        params: String? = null
    ): String

    suspend fun <T> callAs(
        method: String,
        params: String? = null,
        clazz: Class<T>
    ): T

    fun destroy()
}

internal class RealDidWebBridgeClient private constructor(
    private val appContext: Context?,
    private val ownedClient: WebviewBridgeClient?
) : IDidWebBridge {
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
        params: String?
    ): String = bridge().callJsMethod(method = method, params = params?.let(::JSONObject))

    override suspend fun <T> callAs(
        method: String,
        params: String?,
        clazz: Class<T>
    ): T = bridge().callJsMethodAs(method = method, params = params?.let(::JSONObject), clazz = clazz)

    override fun destroy() {
        if (ownsClient) {
            bridge().destroy()
        }
    }
}

internal class AndroidDidWebRuntime(
    context: Context,
    clientFactory: ((Context) -> IDidWebBridge)? = null
) : IDidBridge, IDidResolver {
    private val bridgeClient: IDidWebBridge =
        clientFactory?.invoke(context)?.apply {
            initialize(
                context = context.applicationContext,
                config = WebviewBridgeConfig(bridgeUrl = androidAssetUrl("did-bridge.html"))
            )
            start()
        } ?: RealDidWebBridgeClient(context)

    override suspend fun call(
        method: String,
        params: String?
    ): String = bridgeClient.call(method = method, params = params)

    override suspend fun <T> callAs(
        method: String,
        params: String?,
        clazz: Class<T>
    ): T = bridgeClient.callAs(method = method, params = params, clazz = clazz)

    override suspend fun resolve(did: String): String =
        bridgeClient.call(
            method = "didResolve",
            params =
                JSONObject().apply {
                    put("did", did)
                }.toString()
        )
}
