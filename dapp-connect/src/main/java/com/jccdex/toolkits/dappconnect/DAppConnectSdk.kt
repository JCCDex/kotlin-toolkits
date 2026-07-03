package com.jccdex.toolkits.dappconnect

import android.content.Context
import android.content.res.Resources
import android.webkit.WebView
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
        return """
(function () {
  try {
    if (window._ccdaoProviderState) {
      window._ccdaoProviderState.chainId = '$chainIdHex';
      window._ccdaoProviderState.rpcUrl = '$rpcUrl';
      console.log('[CCDAO Init] Provider state updated: chainId=$chainIdHex rpcUrl=$rpcUrl');
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
        return "if (window.$fn) { window.$fn('$address'); }"
    }

    /** Build JS to update chainId and trigger chainChanged event */
    fun loadUpdateChainIdJs(chainIdHex: String, rpcUrl: String): String {
        return "if (window._updateChainId) { window._updateChainId('$chainIdHex', '$rpcUrl'); }"
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
}
