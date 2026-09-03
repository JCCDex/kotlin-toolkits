package com.jccdex.toolkits.webviewbridge

import android.annotation.SuppressLint
import android.app.Activity
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
import kotlin.time.Duration.Companion.milliseconds

// H-DID4-3: bridge asset SHA-256 at commit time. Regenerate (`shasum -a 256`) when assets change.
// Covers the bridge html/glue AND the crypto libraries they load via <script> (the code that
// actually processes private keys).
private const val DID_BRIDGE_HTML_SHA256 = "bee6888e302fbbb683f2d2d67d12d3a9446cb185ded4d5d9224482b30085e5cf"
private const val WALLET_BRIDGE_HTML_SHA256 = "e3bf162ff6557f27c7c83db175a41c699bb0d55b4254533e81688e1352076b8f"
private const val UNIFIED_BRIDGE_HTML_SHA256 = "0f3f58176516835677d4d6e717e900d137f85860397bcbe71f22b883a61b80f5"
private const val BRIDGE_PROMISE_CORE_JS_SHA256 = "1d83388ed040e7cbab7e0804c82f570f4f326b9e730a2435f043daea08c3575c"
private const val DID_BRIDGE_JS_SHA256 = "060527f8fee09f5c4f8467f1b664d8fcc2c5aea9c345d4dff455e989c9a2efc8"
private const val WALLET_BRIDGE_JS_SHA256 = "a5b9ab508bebeb04d3a17d8d8ccbd2b02af48a47d6edb5691e329d4f98045580"
private const val DID_032_MIN_JS_SHA256 = "046d17d4289ead0a0351bac80a85613d9df4264a5ba443999c7a7df6b01bffee"
private const val JCC_WALLET_MIN_JS_SHA256 = "326eed9f7f56cee174cdc9a61fceae0759ec98ab42b88e1f73841e1d2bf7bd0c"
private const val JINGTUM_LIB_MIN_JS_SHA256 = "f33207237f5cff0f1eb6e28fcc877913989e5cbbd40ff0844276207bb43d6102"
private const val ETH_SIG_UTIL_MIN_JS_SHA256 = "42e0a03e016e85131f9f93af08d3ab0ae9b5763bddbc1cf58c57bc94011bcbd0"
private const val ETHEREUMJS_TX_MIN_JS_SHA256 = "4be85e8ab88ba331470b3b3022bfc62ce9621aa097dfc65b80fd40f39b2ad351"

class WebviewBridgeClient {
    private val gateway: IPromiseGateway
    private val webViewFactory: (Context) -> WebView

    constructor() {
        gateway = PromiseGatewayImpl()
        webViewFactory = { context -> WebView(context) }
    }

    internal constructor(gateway: IPromiseGateway) {
        this.gateway = gateway
        this.webViewFactory = { context -> WebView(context) }
    }

    internal constructor(
        gateway: IPromiseGateway,
        webViewFactory: (Context) -> WebView
    ) {
        this.gateway = gateway
        this.webViewFactory = webViewFactory
    }

    private var appContext: Context? = null
    private var config: WebviewBridgeConfig = WebviewBridgeConfig()
    private var webViewRef: WeakReference<WebView>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    // M-W8: a started WebView is bound to the config it was created with; re-initializing would
    // silently change config while the old WebView keeps the old jsInterfaceName.
    @Volatile
    private var started = false

    fun initialize(
        context: Context,
        config: WebviewBridgeConfig = WebviewBridgeConfig()
    ) {
        // M-W8: re-initializing after start silently breaks the bridge — the existing WebView keeps
        // the old config, and a changed jsInterfaceName orphans the interface registration.
        check(!started) { "WebviewBridgeClient already started; call destroy() before re-initializing" }
        appContext = context.applicationContext
        this.config = config
    }

    /**
     * L-10: Attach an Activity context for better WebView lifecycle management.
     * On some devices, WebView requires an Activity context to properly release resources.
     * Call this from your Activity's onCreate/onStart, and call [detach] from onDestroy.
     *
     * Note: This is optional. If not called, the WebView uses applicationContext which works
     * on most devices but may cause leaks on certain Android versions.
     *
     * @param activityContext The Activity context to attach (must be an Activity)
     * @throws IllegalArgumentException if context is not an Activity
     */
    fun attach(activityContext: Context) {
        require(activityContext is Activity) {
            "attach() requires an Activity context, got: ${activityContext::class.java.simpleName}"
        }
        appContext = activityContext
    }

    /**
     * L-10: Detach the Activity context. Call from Activity's onDestroy.
     * This removes the Activity reference to prevent leaks, but does NOT destroy the WebView.
     * The WebView will continue using applicationContext after detach.
     */
    fun detach() {
        // Only remove Activity reference, revert to applicationContext
        // Do NOT destroy WebView - user may want to re-attach to a new Activity
        appContext = appContext?.applicationContext
    }

    /**
     * SHA-256 of bridge assets at commit time (H-DID4-3). Regenerate (`shasum -a 256`) whenever
     * an asset in `webview-bridge/src/main/assets` changes.
     *
     * NOTE: this is a *detection* control only — the hashes live in the same APK, so a full
     * repackager can rewrite them too. Real tamper-resistance needs native/signed hashes (H-DID4-4).
     */
    private val bridgeAssetHashes =
        mapOf(
            "did-bridge.html" to DID_BRIDGE_HTML_SHA256,
            "wallet-bridge.html" to WALLET_BRIDGE_HTML_SHA256,
            "unified-bridge.html" to UNIFIED_BRIDGE_HTML_SHA256,
            "bridge-promise-core.js" to BRIDGE_PROMISE_CORE_JS_SHA256,
            "did-bridge.js" to DID_BRIDGE_JS_SHA256,
            "wallet-bridge.js" to WALLET_BRIDGE_JS_SHA256,
            "did-0.3.2.min.js" to DID_032_MIN_JS_SHA256,
            "jcc-wallet-4.0.8.min.js" to JCC_WALLET_MIN_JS_SHA256,
            "jingtum-lib.min.js" to JINGTUM_LIB_MIN_JS_SHA256,
            "eth-sig-util.min.js" to ETH_SIG_UTIL_MIN_JS_SHA256,
            "ethereumjs-tx-5.4.0.min.js" to ETHEREUMJS_TX_MIN_JS_SHA256
        )

    /** M-W9: the complete set of assets the bridge may load (html pages + <script> dependencies). */
    private val bridgeAssetNames: Set<String> = bridgeAssetHashes.keys + WebviewBridgeConfig.BRIDGE_PAGES

    /** Logs a severe error if any bridge asset no longer matches its embedded hash (H-DID4-3). */
    private fun verifyBridgeAssets(context: Context) {
        val assets = context.assets
        bridgeAssetHashes.forEach { (name, expected) ->
            val actual =
                try {
                    assets.open(name).use { stream ->
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                } catch (_: Exception) {
                    "<missing>"
                }
            if (actual != expected) {
                Log.e(
                    "WebviewBridgeClient",
                    "Bridge asset integrity check FAILED for $name (asset tampered or hash outdated)"
                )
            }
        }
    }

    internal fun isInitializedForTest(): Boolean = appContext != null

    internal fun currentConfigForTest(): WebviewBridgeConfig = config

    private fun getWebView(): WebView? = webViewRef?.get()

    /** M-W9: exact match against the known SDK bridge pages (no prefix matching). */
    private fun isAllowedBridgeUrl(url: String): Boolean =
        url.removePrefix("file:///android_asset/") in WebviewBridgeConfig.BRIDGE_PAGES

    /** M-W9: only known bridge assets (html pages + their <script> dependencies) may be loaded. */
    private fun isAllowedBridgeAsset(url: String): Boolean {
        val prefix = "file:///android_asset/"
        if (url.startsWith(prefix) && url.removePrefix(prefix) in bridgeAssetNames) {
            return true
        }
        // Allow DID-related network requests (IPFS, jccdex.cn API for DID resolution)
        if (url.startsWith("https://") && (
                url.contains("ipfs") ||
                    url.contains("jccdex.cn") ||
                    url.contains("ipns")
            )
        ) {
            return true
        }
        return false
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun startInternal() {
        if (getWebView() != null) return

        val ctx =
            appContext
                ?: throw IllegalStateException("WebviewBridgeClient not initialized. Call initialize(context) first.")

        verifyBridgeAssets(ctx)
        gateway.resetReady()

        val webView =
            webViewFactory(ctx).also { w ->
                // H-DID4: bridge handles private keys — WebView debugging must stay off.
                WebView.setWebContentsDebuggingEnabled(false)
                w.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // M-W10: allowFileAccess=true + file-URL access off — ensures android_asset loads
                    // across API levels (some WebViews treat allowFileAccess=false as blocking
                    // android_asset too) while JS cannot fetch file:// cross-origin.
                    allowFileAccess = true
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }

                w.addJavascriptInterface(gateway, config.jsInterfaceName)

                w.webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString().orEmpty()
                            // M-W9: only the known SDK bridge pages may navigate the main frame —
                            // exact match, not prefix (blocks file:///android_asset/../../ and siblings).
                            return !isAllowedBridgeUrl(url)
                        }

                        // M-W10: bridge load failures must be observable (was a silent 15s awaitReady timeout).
                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            Log.w(TAG, "Bridge load error for ${request?.url}: ${error?.description}")
                        }

                        // M-W9: sub-resources restricted to the known bridge assets — iframes or
                        // sibling pages cannot pull in arbitrary content (script/img/frame).
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val url = request?.url?.toString().orEmpty()
                            if (isAllowedBridgeAsset(url)) {
                                return null // let the WebView load it normally
                            }
                            Log.w(TAG, "Blocked bridge sub-resource: $url")
                            return android.webkit.WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                403,
                                "Forbidden",
                                emptyMap(),
                                java.io.ByteArrayInputStream(ByteArray(0))
                            )
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            // Any navigation invalidates the bridge page until it finishes loading (H-W1).
                            gateway.pageActive = false
                        }

                        override fun onPageFinished(
                            view: WebView?,
                            url: String?
                        ) {
                            super.onPageFinished(view, url)
                            // M-W9: pageActive only for a known bridge page (exact match, not prefix).
                            gateway.pageActive = isAllowedBridgeUrl(url.orEmpty())
                            try {
                                w.evaluateJavascript(
                                    "if(window.${config.jsInterfaceName} && " +
                                        "window.${config.jsInterfaceName}.onBridgeReady){" +
                                        "window.${config.jsInterfaceName}.onBridgeReady();}",
                                    null
                                )
                            } catch (_: Throwable) {
                            }
                        }
                    }

                w.webChromeClient =
                    object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            return true
                        }
                    }

                w.visibility = View.INVISIBLE
                w.loadUrl(config.bridgeUrl)
            }

        webViewRef = WeakReference(webView)
        started = true
    }

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
        if (gateway.isReady()) return

        withTimeout(timeoutMs.milliseconds) {
            suspendCancellableCoroutine { cont ->
                if (gateway.isReady()) {
                    cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }

                val remover =
                    gateway.addReadyListener {
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
            throw IllegalStateException("WebviewBridgeClient not initialized. Call initialize(context) first.")
        }

        ensureWebViewStarted()
        awaitReady(readyWaitMs.coerceAtMost(timeoutMs))

        return withTimeout(timeoutMs.milliseconds) {
            suspendCancellableCoroutine { cont ->
                val id = UUID.randomUUID().toString()

                gateway.callbackMap[id] = { resultJson ->
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

                // L-15: All interpolated values are JSON-encoded to prevent injection.
                // method/id use JSONObject.quote(), params is already a JSONObject (or null).
                // Future maintainers: do NOT add unquoted string interpolation here.
                val paramsJs = params?.toString() ?: "null"
                val js = "PromiseBridge.call(${JSONObject.quote(method)}, $paramsJs, ${JSONObject.quote(id)});"

                mainHandler.post {
                    try {
                        val webView = getWebView()
                        if (webView == null) {
                            gateway.callbackMap.remove(id)
                            cont.resumeWithException(IllegalStateException("WebView is not available"))
                            return@post
                        }
                        webView.evaluateJavascript(js, null)
                    } catch (e: Exception) {
                        gateway.callbackMap.remove(id)
                        cont.resumeWithException(e)
                    }
                }

                cont.invokeOnCancellation {
                    gateway.callbackMap.remove(id)
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

    /**
     * Reloads the bridge page on the existing WebView, clearing pending callbacks and JS heap state.
     * Keeps the WebView instance alive (e.g. wallet reset while [DidSdk] still uses the shared bridge).
     */
    fun reloadBridgePage() {
        val block = {
            if (appContext == null) {
                throw IllegalStateException("WebviewBridgeClient not initialized. Call initialize(context) first.")
            }
            gateway.clearAll()
            gateway.resetReady()
            gateway.pageActive = false
            val webView = getWebView()
            if (webView != null) {
                webView.loadUrl(config.bridgeUrl)
            } else {
                startInternal()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    fun destroy() {
        val block = {
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
                gateway.clearAll()
                // M-W8: synchronous on the main thread so destroy() → initialize() is safe.
                started = false
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "WebviewBridgeClient"
    }
}
