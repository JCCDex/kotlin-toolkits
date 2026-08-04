package com.jccdex.toolkits.dappconnect

import android.webkit.WebView
import com.jccdex.toolkits.dappconnect.middleware.IEthMiddleware
import com.jccdex.toolkits.dappconnect.middleware.ISwtcMiddleware
import com.jccdex.toolkits.dappconnect.provider.AccountProvider
import com.jccdex.toolkits.dappconnect.provider.NftProvider
import com.jccdex.toolkits.dappconnect.provider.SecretProvider
import org.json.JSONArray
import org.json.JSONObject

abstract class WebAppInterfaceWithWebView(
    private val webView: WebView,
    ethMiddleware: IEthMiddleware,
    swtcMiddleware: ISwtcMiddleware,
    accountProvider: AccountProvider? = null,
    secretProvider: SecretProvider? = null,
    nftProvider: NftProvider? = null,
    didDocumentMutationListener: DidDocumentMutationListener? = null
) : WebAppInterface(
        ethMiddleware,
        swtcMiddleware,
        accountProvider,
        secretProvider,
        nftProvider,
        didDocumentMutationListener
    ) {
    private val responseChannel = NativeResponseChannel(webView)

    companion object {
        private const val TAG = "WebAppInterfaceWithWebView"

        /**
         * Legacy evaluateJavascript callback builder (pre–C-03). Kept for unit tests that
         * assert [JSONObject.quote] escaping; production delivery uses [NativeResponseChannel].
         */
        internal fun jsCallback(
            fn: String,
            nonce: String,
            payloadJs: String
        ): String = "window.ccdao.$fn(${JSONObject.quote(nonce)}, $payloadJs)"

        /**
         * Serializes a bridge result as a JS expression argument.
         * Strings are always [JSONObject.quote]d so values like `0x123` stay string literals
         * (unquoted hex is parsed as a number by JS).
         */
        internal fun resultToJs(result: Any?): String =
            when (result) {
                is JSONArray -> result.toString()
                is JSONObject -> result.toString()
                is String -> JSONObject.quote(result)
                null -> "null"
                else -> result.toString()
            }
    }

    /**
     * Install / refresh the WebMessagePort used for RPC responses (C-03).
     * Call after `ccdao-eip1193-provider.js` has been evaluated on the page.
     */
    override fun installResponseChannel() {
        responseChannel.install()
    }

    override fun sendSuccessResponse(
        network: String,
        nonce: String,
        result: Any?
    ) {
        super.sendSuccessResponse(network, nonce, result)
        responseChannel.sendSuccess(nonce, result)
        android.util.Log.d(TAG, "Success response queued on port")
    }

    override fun sendErrorResponse(
        network: String,
        nonce: String,
        error: String
    ) {
        super.sendErrorResponse(network, nonce, error)
        responseChannel.sendError(nonce, code = -1, message = error)
        android.util.Log.d(TAG, "Error response queued on port")
    }

    override fun sendErrorResponseWithCode(
        network: String,
        nonce: String,
        code: Int,
        error: String
    ) {
        android.util.Log.e(TAG, "Error response with code: network=$network, code=$code")
        responseChannel.sendError(nonce, code = code, message = error)
    }
}

fun createWebAppInterfaceWithWebView(
    webView: WebView,
    ethMiddleware: IEthMiddleware,
    swtcMiddleware: ISwtcMiddleware,
    accountProvider: AccountProvider? = null,
    secretProvider: SecretProvider? = null,
    nftProvider: NftProvider? = null,
    didDocumentMutationListener: DidDocumentMutationListener? = null
): WebAppInterface {
    return object : WebAppInterfaceWithWebView(
        webView,
        ethMiddleware,
        swtcMiddleware,
        accountProvider,
        secretProvider,
        nftProvider,
        didDocumentMutationListener
    ) {}
}
