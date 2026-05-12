package com.jccdex.toolkits.webviewbridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object WebviewBridgeEngine {
    private var appContext: Context? = null
    private var config: WebviewBridgeConfig = WebviewBridgeConfig()
    private var webViewRef: WeakReference<WebView>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    fun initialize(
        context: Context,
        config: WebviewBridgeConfig = WebviewBridgeConfig()
    ) {
        appContext = context.applicationContext
        this.config = config
    }

    internal fun isInitializedForTest(): Boolean = appContext != null

    internal fun currentConfigForTest(): WebviewBridgeConfig = config

    private fun getWebView(): WebView? = webViewRef?.get()

    /**
     * Creates the headless [WebView] on the **main thread** only.
     * Must never be called from a background dispatcher (Android throws
     * "WebView cannot be initialized on a thread that has no Looper").
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun startInternal() {
        if (getWebView() != null) return

        val ctx =
            appContext
                ?: throw IllegalStateException("WebviewBridgeEngine not initialized. Call initialize(context) first.")

        JsPromiseGateway.resetReady()

        val webView =
            WebView(ctx).also { w ->
                w.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }

                w.addJavascriptInterface(JsPromiseGateway, config.jsInterfaceName)

                w.webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView?,
                            url: String?
                        ) {
                            super.onPageFinished(view, url)
                            try {
                                w.evaluateJavascript(
                                    "if(window.${config.jsInterfaceName} && window.${config.jsInterfaceName}.onBridgeReady){window.${config.jsInterfaceName}.onBridgeReady();}",
                                    null
                                )
                            } catch (_: Throwable) {
                            }
                        }
                    }

                w.webChromeClient =
                    object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            consoleMessage?.let {
                                Log.d(
                                    config.consoleTag,
                                    "${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}}"
                                )
                            }
                            return true
                        }
                    }

                w.visibility = View.INVISIBLE
                w.loadUrl(config.bridgeUrl)
            }

        webViewRef = WeakReference(webView)
    }

    /**
     * Idempotent: safe from any thread. If not on the main looper, work is posted to the main thread.
     */
    fun start() {
        if (getWebView() != null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startInternal()
        } else {
            mainHandler.post { startInternal() }
        }
    }

    private suspend fun ensureWebViewStarted() {
        if (getWebView() != null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startInternal()
            return
        }
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                try {
                    if (getWebView() == null) {
                        startInternal()
                    }
                    cont.resume(Unit)
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                }
            }
        }
    }

    private suspend fun awaitReady(timeoutMs: Long) {
        if (JsPromiseGateway.isReady()) return

        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                if (JsPromiseGateway.isReady()) {
                    cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }

                val remover =
                    JsPromiseGateway.addReadyListener {
                        if (!cont.isCompleted) {
                            try {
                                cont.resume(Unit)
                            } catch (_: Throwable) {
                            }
                        }
                    }

                cont.invokeOnCancellation {
                    try {
                        remover()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    suspend fun callJsMethod(
        method: String,
        params: JSONObject? = null,
        timeoutMs: Long = 30_000L,
        readyWaitMs: Long = 15_000L
    ): String {
        if (appContext == null) {
            throw IllegalStateException("WebviewBridgeEngine not initialized. Call initialize(context) first.")
        }

        ensureWebViewStarted()

        awaitReady(readyWaitMs.coerceAtMost(timeoutMs))

        return withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val id = UUID.randomUUID().toString()

                JsPromiseGateway.callbackMap[id] = { resultJson ->
                    try {
                        val o = JSONObject(resultJson)
                        if (o.has("error")) {
                            cont.resumeWithException(Exception(o.optString("error")))
                        } else if (o.has("result")) {
                            when (val result = o.get("result")) {
                                is String -> cont.resume(result)
                                else -> cont.resume(result.toString())
                            }
                        } else {
                            cont.resumeWithException(Exception("Invalid response format"))
                        }
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }

                val paramsJs = params?.toString() ?: "null"
                val js = "PromiseBridge.call(${JSONObject.quote(method)}, $paramsJs, ${JSONObject.quote(id)});"

                mainHandler.post {
                    try {
                        val webView = getWebView()
                        if (webView == null) {
                            JsPromiseGateway.callbackMap.remove(id)
                            cont.resumeWithException(IllegalStateException("WebView is not available"))
                            return@post
                        }
                        webView.evaluateJavascript(js, null)
                    } catch (e: Exception) {
                        JsPromiseGateway.callbackMap.remove(id)
                        cont.resumeWithException(e)
                    }
                }

                cont.invokeOnCancellation {
                    JsPromiseGateway.callbackMap.remove(id)
                }
            }
        }
    }

    suspend fun <T> callJsMethodAs(
        method: String,
        params: JSONObject? = null,
        clazz: Class<T>,
        timeoutMs: Long = 30_000L,
        readyWaitMs: Long = 15_000L
    ): T {
        val raw = callJsMethod(method, params, timeoutMs, readyWaitMs)
        if (clazz == String::class.java) {
            @Suppress("UNCHECKED_CAST")
            return raw as T
        }
        return gson.fromJson(raw.trim(), clazz)
    }

    fun destroy() {
        mainHandler.post {
            try {
                val webView = getWebView()
                if (webView != null) {
                    try {
                        webView.removeJavascriptInterface(config.jsInterfaceName)
                    } catch (_: Throwable) {
                    }
                    try {
                        webView.loadUrl("about:blank")
                    } catch (_: Throwable) {
                    }
                    try {
                        webView.stopLoading()
                    } catch (_: Throwable) {
                    }
                    try {
                        webView.removeAllViews()
                    } catch (_: Throwable) {
                    }
                    try {
                        webView.destroy()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            } finally {
                webViewRef?.clear()
                webViewRef = null
                JsPromiseGateway.clearAll()
            }
        }
    }
}
