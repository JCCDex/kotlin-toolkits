package com.jccdex.toolkits.did.sdk

import android.content.Context
import com.jccdex.toolkits.did.port.IDidBridge
import com.jccdex.toolkits.did.service.IDidResolver
import com.jccdex.toolkits.webviewbridge.WebviewBridgeClient
import com.jccdex.toolkits.webviewbridge.WebviewBridgeConfig
import com.jccdex.toolkits.webviewbridge.androidAssetUrl
import org.json.JSONObject

internal class AndroidDidWebRuntime(
    context: Context
) : IDidBridge, IDidResolver {
    private val bridgeClient =
        WebviewBridgeClient().apply {
            initialize(
                context = context.applicationContext,
                config = WebviewBridgeConfig(bridgeUrl = androidAssetUrl("did-bridge.html"))
            )
            start()
        }

    override suspend fun call(
        method: String,
        params: String?
    ): String = bridgeClient.callJsMethod(method = method, params = params?.let(::JSONObject))

    override suspend fun <T> callAs(
        method: String,
        params: String?,
        clazz: Class<T>
    ): T = bridgeClient.callJsMethodAs(method = method, params = params?.let(::JSONObject), clazz = clazz)

    override suspend fun resolve(did: String): String =
        bridgeClient.callJsMethod(
            method = "didResolve",
            params = JSONObject().apply { put("did", did) }
        )
}
