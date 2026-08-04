package com.jccdex.toolkits.dappconnect

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native → JS response delivery via [WebMessagePort] (C-03).
 *
 * Replaces `evaluateJavascript("window.ccdao.sendResponse(...)")` so page scripts cannot
 * forge RPC completions by calling globals. Hosts must call [install] after injecting
 * `ccdao-eip1193-provider.js` (e.g. in `evaluateJavascript` completion callback).
 */
class NativeResponseChannel(
    private val webView: WebView
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var responsePort: WebMessagePort? = null
    private val pending = ArrayDeque<String>()

    /** Transfer a fresh JS-side port; flushes any responses queued before install. */
    fun install() {
        runOnMain {
            try {
                responsePort?.close()
                responsePort = null
                val channel = webView.createWebMessageChannel()
                val nativePort = channel[0]
                val jsPort = channel[1]
                // Native does not expect JS→native traffic on this port today.
                nativePort.setWebMessageCallback(
                    object : WebMessagePort.WebMessageCallback() {
                        override fun onMessage(
                            port: WebMessagePort?,
                            message: WebMessage?
                        ) = Unit
                    }
                )
                responsePort = nativePort
                webView.postWebMessage(
                    WebMessage(HANDSHAKE, arrayOf(jsPort)),
                    Uri.parse("*")
                )
                flushPending()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to install response channel", t)
            }
        }
    }

    fun sendSuccess(
        nonce: String,
        result: Any?
    ) {
        enqueue(successPayload(nonce, result))
    }

    fun sendError(
        nonce: String,
        code: Int,
        message: String
    ) {
        enqueue(errorPayload(nonce, code, message))
    }

    fun close() {
        runOnMain {
            pending.clear()
            try {
                responsePort?.close()
            } catch (_: Throwable) {
            }
            responsePort = null
        }
    }

    private fun enqueue(json: String) {
        runOnMain {
            val port = responsePort
            if (port == null) {
                pending.addLast(json)
                return@runOnMain
            }
            try {
                port.postMessage(WebMessage(json))
            } catch (t: Throwable) {
                Log.w(TAG, "postMessage failed; queueing until reinstall", t)
                responsePort = null
                pending.addLast(json)
            }
        }
    }

    private fun flushPending() {
        val port = responsePort ?: return
        while (pending.isNotEmpty()) {
            val json = pending.removeFirst()
            try {
                port.postMessage(WebMessage(json))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to flush pending response", t)
                pending.addFirst(json)
                responsePort = null
                return
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "NativeResponseChannel"

        /** Must match the listener in `ccdao-eip1193-provider.js`. */
        const val HANDSHAKE = "__CCDAO_NATIVE_PORT__"

        fun successPayload(
            nonce: String,
            result: Any?
        ): String {
            val obj = JSONObject()
            obj.put("nonce", nonce)
            when (result) {
                null -> obj.put("result", JSONObject.NULL)
                is JSONObject -> obj.put("result", result)
                is JSONArray -> obj.put("result", result)
                is String -> obj.put("result", result)
                is Number -> obj.put("result", result)
                is Boolean -> obj.put("result", result)
                else -> obj.put("result", result.toString())
            }
            return obj.toString()
        }

        fun errorPayload(
            nonce: String,
            code: Int,
            message: String
        ): String {
            val error =
                JSONObject().apply {
                    put("code", code)
                    put("message", message)
                }
            return JSONObject()
                .put("nonce", nonce)
                .put("error", error)
                .toString()
        }
    }
}
