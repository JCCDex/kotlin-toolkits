package com.jccdex.toolkits.did.sdk

import android.content.Context
import com.jccdex.toolkits.did.port.IDidBridge
import com.jccdex.toolkits.did.service.IDidResolver
import com.jccdex.toolkits.webviewbridge.WebviewBridgeClient
import com.jccdex.toolkits.webviewbridge.WebviewBridgeConfig
import com.jccdex.toolkits.webviewbridge.androidAssetUrl
import org.json.JSONObject

internal interface IDidWebBridgeClient {
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

internal class RealDidWebBridgeClient(
    private val client: WebviewBridgeClient = WebviewBridgeClient()
) : IDidWebBridgeClient {
    override fun initialize(
        context: Context,
        config: WebviewBridgeConfig
    ) {
        client.initialize(context, config)
    }

    override fun start() = client.start()

    override suspend fun call(
        method: String,
        params: String?
    ): String = client.callJsMethod(method = method, params = params?.let(::JSONObject))

    override suspend fun <T> callAs(
        method: String,
        params: String?,
        clazz: Class<T>
    ): T = client.callJsMethodAs(method = method, params = params?.let(::JSONObject), clazz = clazz)

    override fun destroy() = client.destroy()
}

internal class AndroidDidWebRuntime(
    context: Context,
    private val clientFactory: ((Context) -> IDidWebBridgeClient)? = null
) : IDidBridge, IDidResolver {
    private val bridgeClient =
        (clientFactory?.invoke(context) ?: RealDidWebBridgeClient()).apply {
            initialize(
                context = context.applicationContext,
                config = WebviewBridgeConfig(bridgeUrl = androidAssetUrl("did-bridge.html"))
            )
            start()
        }

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
