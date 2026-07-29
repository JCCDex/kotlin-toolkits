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
    }

    override fun sendSuccessResponse(network: String, nonce: String, result: Any?) {
        super.sendSuccessResponse(network, nonce, result)

        val resultStr = when (result) {
            is JSONArray -> result.toString()
            is JSONObject -> result.toString()
            is String -> if (result.startsWith("0x") || result.startsWith("{")) {
                result
            } else {
                "\"$result\""
            }
            null -> "null"
            else -> result.toString()
        }

        val callback = "window.ccdao.sendResponse(\"$nonce\", $resultStr)"
        webView.post {
            webView.evaluateJavascript(callback) { value ->
                android.util.Log.d(TAG, "Success response sent: $value")
            }
        }
    }

    override fun sendErrorResponse(network: String, nonce: String, error: String) {
        super.sendErrorResponse(network, nonce, error)

        val errorObj = JSONObject().apply {
            put("code", -1)
            put("message", error)
        }

        val callback = "window.ccdao.sendError(\"$nonce\", $errorObj)"
        webView.post {
            webView.evaluateJavascript(callback) { value ->
                android.util.Log.d(TAG, "Error response sent: $value")
            }
        }
    }

    override fun sendErrorResponseWithCode(network: String, nonce: String, code: Int, error: String) {
        android.util.Log.e(TAG, "Error response with code: network=$network, nonce=$nonce, code=$code, error=$error")

        val errorObj = JSONObject().apply {
            put("code", code)
            put("message", error)
        }

        val callback = "window.ccdao.sendError(\"$nonce\", $errorObj)"
        webView.post {
            webView.evaluateJavascript(callback) { value ->
                android.util.Log.d(TAG, "Error response with code sent: $value")
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
