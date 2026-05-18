package com.jccdex.toolkits.webviewbridge

import android.app.Application
import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WebviewBridgeClientBehaviorTest {
    private val appContext = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        JsPromiseGateway.clearAll()
    }

    @Test
    fun start_initializesWebView_and_loadsBridgeUrl() {
        val gateway = RecordingGateway()
        var createdWebView: WebView? = null
        val client =
            WebviewBridgeClient(
                gateway = gateway,
                webViewFactory = { context ->
                    WebView(context).also { createdWebView = it }
                }
            )

        client.initialize(appContext, WebviewBridgeConfig(bridgeUrl = androidAssetUrl("did-bridge.html")))
        client.start()
        shadowOf(Looper.getMainLooper()).idle()

        val webView = checkNotNull(createdWebView)
        val shadow = shadowOf(webView)

        assertThat(gateway.resetReadyCalled).isTrue
        assertThat(shadow.lastLoadedUrl).isEqualTo("file:///android_asset/did-bridge.html")
        assertThat(shadow.getJavascriptInterface("JSBridge")).isSameAs(gateway)
        assertThat(shadow.webViewClient).isNotNull
        assertThat(shadow.webChromeClient).isNotNull
    }

    @Test
    fun pageFinished_evaluatesBridgeReadyScript() {
        val gateway = RecordingGateway()
        var createdWebView: WebView? = null
        val client =
            WebviewBridgeClient(
                gateway = gateway,
                webViewFactory = { context ->
                    WebView(context).also { createdWebView = it }
                }
            )

        client.initialize(appContext, WebviewBridgeConfig(jsInterfaceName = "BridgeJs"))
        client.start()
        shadowOf(Looper.getMainLooper()).idle()

        val webView = checkNotNull(createdWebView)
        val clientCallback = checkNotNull(shadowOf(webView).webViewClient)

        clientCallback.onPageFinished(webView, "file:///android_asset/bridge.html")

        assertThat(shadowOf(webView).lastEvaluatedJavascript).contains("BridgeJs.onBridgeReady()")
    }

    @Test
    fun callJsMethod_returnsResultFromPromiseBridge() =
        runTest {
            val gateway = RecordingGateway()
            var createdWebView: WebView? = null
            val client =
                WebviewBridgeClient(
                    gateway = gateway,
                    webViewFactory = { context ->
                        WebView(context).also { createdWebView = it }
                    }
                )

            client.initialize(appContext)

            val deferred =
                async {
                    client.callJsMethod(
                        method = "generateMnemonic",
                        params = JSONObject().apply { put("length", 128) },
                        timeoutMs = 5_000L,
                        readyWaitMs = 5_000L
                    )
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            gateway.onBridgeReady()
            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()

            val webView = checkNotNull(createdWebView)
            val script = checkNotNull(shadowOf(webView).lastEvaluatedJavascript)
            val id = extractPromiseId(script)
            gateway.onPromiseResult(id, """{"result":"ok"}""")
            runCurrent()

            assertThat(deferred.await()).isEqualTo("ok")
            assertThat(script).contains("PromiseBridge.call")
            assertThat(script).contains("\"generateMnemonic\"")
        }

    @Test
    fun callJsMethodAs_parsesJsonResult() =
        runTest {
            val gateway = RecordingGateway()
            var createdWebView: WebView? = null
            val client =
                WebviewBridgeClient(
                    gateway = gateway,
                    webViewFactory = { context ->
                        WebView(context).also { createdWebView = it }
                    }
                )

            client.initialize(appContext)

            data class Result(val value: String)

            val deferred =
                async {
                    client.callJsMethodAs(
                        method = "getValue",
                        params = null,
                        clazz = Result::class.java,
                        timeoutMs = 5_000L,
                        readyWaitMs = 5_000L
                    )
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            gateway.onBridgeReady()
            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()

            val webView = checkNotNull(createdWebView)
            val script = checkNotNull(shadowOf(webView).lastEvaluatedJavascript)
            val id = extractPromiseId(script)
            gateway.onPromiseResult(id, """{"result":{"value":"alpha"}}""")
            runCurrent()

            assertThat(deferred.await()).isEqualTo(Result("alpha"))
        }

    @Test
    fun destroy_clearsWebViewAndGateway() {
        val gateway = RecordingGateway()
        var createdWebView: WebView? = null
        val client =
            WebviewBridgeClient(
                gateway = gateway,
                webViewFactory = { context ->
                    WebView(context).also { createdWebView = it }
                }
            )

        client.initialize(appContext)
        client.start()
        shadowOf(Looper.getMainLooper()).idle()

        client.destroy()
        shadowOf(Looper.getMainLooper()).idle()

        val webView = checkNotNull(createdWebView)
        val shadow = shadowOf(webView)

        assertThat(gateway.clearAllCalled).isTrue
        assertThat(shadow.wasDestroyCalled()).isTrue
    }

    private class RecordingGateway : IPromiseGateway {
        override val callbackMap = ConcurrentHashMap<String, (String) -> Unit>()
        private val readyListeners = mutableListOf<() -> Unit>()

        var ready = false
        var resetReadyCalled = false
        var clearAllCalled = false

        override fun onPromiseResult(
            id: String,
            resultJson: String
        ) {
            callbackMap.remove(id)?.invoke(resultJson)
        }

        override fun onBridgeReady() {
            ready = true
            val listeners = readyListeners.toList()
            readyListeners.clear()
            listeners.forEach { it.invoke() }
        }

        override fun isReady(): Boolean = ready

        override fun addReadyListener(listener: () -> Unit): () -> Unit {
            if (ready) {
                listener.invoke()
                return {}
            }
            readyListeners += listener
            return { readyListeners.remove(listener) }
        }

        override fun resetReady() {
            ready = false
            readyListeners.clear()
            resetReadyCalled = true
        }

        override fun clearAll() {
            callbackMap.clear()
            readyListeners.clear()
            ready = false
            clearAllCalled = true
        }
    }

    private fun extractPromiseId(script: String): String {
        val match = Regex(""",\s*"([^"]+)"\);\s*$""").find(script)
        return requireNotNull(match?.groupValues?.get(1)) { "No promise id in script: $script" }
    }
}
