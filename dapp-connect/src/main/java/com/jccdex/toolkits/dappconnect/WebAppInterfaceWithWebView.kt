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

    companion object {
        private const val TAG = "WebAppInterfaceWithWebView"

        /**
         * Builds `window.ccdao.<fn>(<quoted-nonce>, <payload>)`.
         * [nonce] is always [JSONObject.quote]d so page-controlled values cannot break out of the JS string.
         */
        internal fun jsCallback(fn: String, nonce: String, payloadJs: String): String =
            "window.ccdao.$fn(${JSONObject.quote(nonce)}, $payloadJs)"

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

    override fun sendSuccessResponse(network: String, nonce: String, result: Any?) {
        super.sendSuccessResponse(network, nonce, result)

        val callback = jsCallback("sendResponse", nonce, resultToJs(result))
        webView.post {
            webView.evaluateJavascript(callback) { _ ->
                android.util.Log.d(TAG, "Success response sent")
            }
        }
    }

    override fun sendErrorResponse(network: String, nonce: String, error: String) {
        super.sendErrorResponse(network, nonce, error)

        val errorObj = JSONObject().apply {
            put("code", -1)
            put("message", error)
        }

        val callback = jsCallback("sendError", nonce, errorObj.toString())
        webView.post {
            webView.evaluateJavascript(callback) { _ ->
                android.util.Log.d(TAG, "Error response sent")
            }
        }
    }

    override fun sendErrorResponseWithCode(network: String, nonce: String, code: Int, error: String) {
        android.util.Log.e(TAG, "Error response with code: network=$network, code=$code")

        val errorObj = JSONObject().apply {
            put("code", code)
            put("message", error)
        }

        val callback = jsCallback("sendError", nonce, errorObj.toString())
        webView.post {
            webView.evaluateJavascript(callback) { _ ->
                android.util.Log.d(TAG, "Error response with code sent")
            }
        }
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
