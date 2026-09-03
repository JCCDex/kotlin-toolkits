package com.jccdex.toolkits.webviewbridge

private const val ANDROID_ASSET_PREFIX = "file:///android_asset/"

fun androidAssetUrl(assetName: String): String = ANDROID_ASSET_PREFIX + assetName

data class WebviewBridgeConfig(
    val bridgeUrl: String = androidAssetUrl("wallet-bridge.html"),
    val jsInterfaceName: String = "JSBridge",
    val consoleTag: String = "WebViewConsole"
) {
    init {
        // M-W9: bridgeUrl must point to a known local bridge page — rejects remote URLs and unknown
        // assets that would otherwise share the JS interface. (The old default "bridge.html" did not
        // exist in assets — a latent bug fixed here by pointing at wallet-bridge.html.)
        val assetName = bridgeUrl.removePrefix(ANDROID_ASSET_PREFIX)
        require(assetName in BRIDGE_PAGES) {
            "bridgeUrl must be a known android_asset bridge page (${BRIDGE_PAGES.joinToString()}); got: $bridgeUrl"
        }
    }

    companion object {
        /** M-W9: only these SDK bridge pages may be loaded into the bridge WebView. */
        val BRIDGE_PAGES: Set<String> =
            setOf(
                "wallet-bridge.html",
                "did-bridge.html",
                "unified-bridge.html"
            )
    }
}
