package com.jccdex.toolkits.webviewbridge

data class WebviewBridgeConfig(
    val bridgeUrl: String = "file:///android_asset/bridge.html",
    val jsInterfaceName: String = "JSBridge",
    val consoleTag: String = "WebViewConsole",
)
