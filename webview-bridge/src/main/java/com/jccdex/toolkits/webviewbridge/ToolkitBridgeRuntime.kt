package com.jccdex.toolkits.webviewbridge

/**
 * Public lifecycle API for the shared wallet + DID hidden WebView (P2-8b).
 *
 * [com.jccdex.toolkits.wallet.sdk.WalletSdk.destroy] clears only the wallet facade.
 * Call [shutdown] when the host no longer needs wallet or DID crypto bridges (e.g. app exit,
 * user logout, or after [WalletSdk.destroy] when DID is also torn down).
 */
object ToolkitBridgeRuntime {
    fun shutdown() {
        SharedWebviewBridge.destroy()
    }

    /** Clears JS runtime state on the shared bridge without destroying the WebView (e.g. wallet reset). */
    fun reloadSharedBridge() {
        SharedWebviewBridge.reloadBridgePage()
    }
}
