package com.jccdex.toolkits.dappconnect

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.webkit.WebView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import com.jccdex.toolkits.dappconnect.middleware.EthMiddleware
import com.jccdex.toolkits.dappconnect.middleware.IEthMiddleware
import com.jccdex.toolkits.dappconnect.middleware.ISwtcMiddleware
import com.jccdex.toolkits.dappconnect.middleware.SwtcMiddleware
import com.jccdex.toolkits.dappconnect.provider.AccountProvider
import com.jccdex.toolkits.dappconnect.provider.ChainProvider
import com.jccdex.toolkits.dappconnect.provider.NftProvider
import com.jccdex.toolkits.dappconnect.provider.NodeProvider
import com.jccdex.toolkits.dappconnect.provider.SecretProvider
import com.jccdex.toolkits.did.sdk.DidSdk
import com.jccdex.toolkits.wallet.sdk.WalletSdk

object DAppConnectSdk {

    private var didSdk: DidSdk? = null

    fun initialize(context: Context) {
        WalletSdk.initialize(context)
    }

    fun start() {
        WalletSdk.start()
    }

    fun destroy() {
        WalletSdk.destroy()
    }

    fun setDidSdk(sdk: DidSdk) {
        didSdk = sdk
    }

    fun getDidSdk(): DidSdk? = didSdk

    // ── Middleware factory ──

    fun createEthMiddleware(
        accountProvider: AccountProvider,
        secretProvider: SecretProvider,
        nodeProvider: NodeProvider,
        chainProvider: ChainProvider? = null,
        initialChain: com.jccdex.toolkits.core.model.ChainType = com.jccdex.toolkits.core.model.ChainType.BSC
    ): EthMiddleware = EthMiddleware(
        accountProvider = accountProvider,
        secretProvider = secretProvider,
        nodeProvider = nodeProvider,
        chainProvider = chainProvider,
        initialChain = initialChain
    )

    fun createSwtcMiddleware(
        accountProvider: AccountProvider,
        secretProvider: SecretProvider,
        nodeProvider: NodeProvider
    ): SwtcMiddleware = SwtcMiddleware(
        accountProvider = accountProvider,
        secretProvider = secretProvider,
        nodeProvider = nodeProvider
    )

    // ── WebAppInterface factory ──

    fun createWebAppInterface(
        webView: WebView,
        ethMiddleware: IEthMiddleware,
        swtcMiddleware: ISwtcMiddleware,
        accountProvider: AccountProvider? = null,
        secretProvider: SecretProvider? = null,
        nftProvider: NftProvider? = null,
        didDocumentMutationListener: DidDocumentMutationListener? = null
    ): WebAppInterface =
        createWebAppInterfaceWithWebView(
            webView,
            ethMiddleware,
            swtcMiddleware,
            accountProvider,
            secretProvider,
            nftProvider,
            didDocumentMutationListener
        )

    // ── Provider JS ──

    /** Load the EIP-1193 provider script that implements window.ethereum / window.ccdao */
    fun loadProviderJs(context: Context): String {
        return loadAssetAsString(context.resources, "ccdao-eip1193-provider.js")
    }

    /** Build the init JS: set chainId, rpcUrl, and selected address in the provider */
    fun loadInitJs(chainIdHex: String, rpcUrl: String): String {
        val qChain = jsQuote(chainIdHex)
        val qRpc = jsQuote(rpcUrl)
        return """
(function () {
  try {
    if (window._ccdaoProviderState) {
      window._ccdaoProviderState.chainId = $qChain;
      window._ccdaoProviderState.rpcUrl = $qRpc;
      console.log('[CCDAO Init] Provider state updated: chainId=$qChain rpcUrl=$qRpc');
    }
  } catch (e) {
    console.error('[CCDAO Init] Failed to update provider', e);
  }
})();
"""
            .trimIndent()
    }

    /** Build JS to update the selected address in the provider */
    fun loadAddressJs(address: String, isSwtc: Boolean): String {
        val fn = if (isSwtc) "_updateSwtcSelectedAddress" else "_updateSelectedAddress"
        return "if (window.$fn) { window.$fn(${jsQuote(address)}); }"
    }

    /** Build JS to update chainId and trigger chainChanged event */
    fun loadUpdateChainIdJs(chainIdHex: String, rpcUrl: String): String {
        return "if (window._updateChainId) { window._updateChainId(${jsQuote(chainIdHex)}, ${jsQuote(rpcUrl)}); }"
    }

    /** Quote a string for safe embedding in a JS string literal (double-quoted). */
    private fun jsQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

    /**
     * Render a drawable resource to a PNG data URI.
     *
     * Used to provide the wallet icon for EIP-6963 provider announcements.
     * Falls back to an inline SVG "D" shield when the drawable cannot be loaded.
     *
     * @param context       Android context
     * @param drawableResId drawable resource (e.g. [R.drawable.app_logo])
     * @param size          output bitmap size in pixels (square), default 128
     * @return a "data:image/png;base64,…" URI
     */
    fun loadIconAsDataUri(
        context: Context,
        drawableResId: Int,
        size: Int = 128
    ): String {
        val drawable =
            ContextCompat.getDrawable(context, drawableResId)
                ?: return FALLBACK_ICON_DATA_URI
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        return "data:image/png;base64,$base64"
    }

    /**
     * Build a self-executing JS snippet that patches [window.dispatchEvent] so
     * EIP-6963 `announceProvider` events carry the real wallet [iconDataUri]
     * instead of the hardcoded letter placeholder in the provider script.
     *
     * @param iconDataUri a data URI (PNG or SVG) for the wallet icon
     * @return JS code to inject into the WebView
     */
    fun loadEip6963IconOverrideJs(iconDataUri: String): String {
        val escaped = iconDataUri.replace("\\", "\\\\").replace("'", "\\'")
        return """
(function(){var i='$escaped';var o=window.dispatchEvent.bind(window);window.dispatchEvent=function(e){if(e.type==='eip6963:announceProvider'&&e.detail&&e.detail.info){var n={uuid:e.detail.info.uuid,name:e.detail.info.name,icon:i,rdns:e.detail.info.rdns};var ne=new CustomEvent('eip6963:announceProvider',{detail:{info:Object.freeze(n),provider:e.detail.provider}});o(ne);return true}return o(e)}})();
        """.trimIndent()
    }

    // ── URL safety ──

    /** Validate that [url] uses http/https and a well-formed host. Blocks file://, javascript:, etc. */
    fun isSafeUrl(url: String): Boolean {
        val pattern =
            Regex(
                "^(https?)://[a-zA-Z0-9][-a-zA-Z0-9]{0,62}(\\.[a-zA-Z0-9][-a-zA-Z0-9]{0,62})+\\.?(:[0-9]{1,5})?(/.*)?$",
                RegexOption.IGNORE_CASE
            )
        return pattern.matches(url) || android.util.Patterns.WEB_URL.matcher(url).matches()
    }

    // ── internal ──

    private fun loadAssetAsString(resources: Resources, assetName: String): String =
        resources.assets.open(assetName).bufferedReader().use { it.readText() }

    /** Fallback SVG data URI — a "D" shield mark — when the drawable cannot be loaded. */
    private val FALLBACK_ICON_DATA_URI: String =
        "data:image/svg+xml," +
            "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 56 56'%3E" +
            "%3Crect width='56' height='56' rx='16' fill='%233B82F6'/%3E" +
            "%3Ctext x='28' y='38' text-anchor='middle' fill='white' font-size='32' " +
            "font-family='Arial,sans-serif' font-weight='bold'%3ED%3C/text%3E" +
            "%3C/svg%3E"
}
