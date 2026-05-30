package com.jccdex.toolkits.webviewbridge

private const val ANDROID_ASSET_PREFIX = "file:///android_asset/"

fun androidAssetUrl(assetName: String): String = ANDROID_ASSET_PREFIX + assetName

data class WebviewBridgeConfig(
    val bridgeUrl: String = androidAssetUrl("bridge.html"),
    val jsInterfaceName: String = "JSBridge",
    val consoleTag: String = "WebViewConsole"
)
