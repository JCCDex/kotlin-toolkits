package com.jccdex.toolkits.webviewbridge

import android.content.Context

/**
 * SDK-internal holder for the process-wide shared hidden WebView (P2-8b).
 *
 * Host apps must use [ToolkitBridgeRuntime.shutdown] to tear down the shared bridge — not this object directly.
 */
object SharedWebviewBridge {
    private val lock = Any()

    @Volatile
    private var client: WebviewBridgeClient? = null

    fun client(context: Context): WebviewBridgeClient {
        synchronized(lock) {
            client?.let { return it }
            return WebviewBridgeClient().also { bridge ->
                bridge.initialize(
                    context = context.applicationContext,
                    config = WebviewBridgeConfig(bridgeUrl = androidAssetUrl("unified-bridge.html"))
                )
                bridge.start()
                client = bridge
            }
        }
    }

    /** Reloads [unified-bridge.html] on the shared client without tearing down the WebView. */
    fun reloadBridgePage() {
        synchronized(lock) {
            client?.reloadBridgePage()
        }
    }

    fun destroy() {
        synchronized(lock) {
            client?.destroy()
            client = null
        }
    }
}
