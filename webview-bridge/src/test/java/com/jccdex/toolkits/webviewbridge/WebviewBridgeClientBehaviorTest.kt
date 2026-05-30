package com.jccdex.toolkits.webviewbridge

import android.app.Application
import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun start_twice_reusesExistingWebView() {
        val gateway = RecordingGateway()
        var factoryCalls = 0
        val client =
            WebviewBridgeClient(
                gateway = gateway,
                webViewFactory = { context ->
                    factoryCalls += 1
                    WebView(context)
                }
            )

        client.initialize(appContext)
        client.start()
        shadowOf(Looper.getMainLooper()).idle()
        client.start()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(factoryCalls).isEqualTo(1)
        assertThat(gateway.resetReadyCalled).isTrue
    }

    @Test
    fun start_fromBackgroundDispatcher_initializesWebView() =
        runTest {
            val gateway = RecordingGateway()
            val startLatch = CountDownLatch(1)
            var createdWebView: WebView? = null
            val client =
                WebviewBridgeClient(
                    gateway = gateway,
                    webViewFactory = { context ->
                        WebView(context).also {
                            createdWebView = it
                        }
                    }
                )

            client.initialize(appContext, WebviewBridgeConfig(bridgeUrl = androidAssetUrl("did-bridge.html")))

            Thread {
                client.start()
                startLatch.countDown()
            }.start()

            assertThat(startLatch.await(2, TimeUnit.SECONDS)).isTrue
            val webView = awaitValue { createdWebView }
            val shadow = shadowOf(webView)

            assertThat(gateway.resetReadyCalled).isTrue
            assertThat(shadow.lastLoadedUrl).isEqualTo("file:///android_asset/did-bridge.html")
            assertThat(shadow.getJavascriptInterface("JSBridge")).isSameAs(gateway)
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

            val webView = awaitValue { createdWebView }
            val script = awaitValue { shadowOf(webView).lastEvaluatedJavascript }
            val id = extractPromiseId(script)
            gateway.onPromiseResult(id, """{"result":"ok"}""")
            runCurrent()

            assertThat(deferred.await()).isEqualTo("ok")
            assertThat(script).contains("PromiseBridge.call")
            assertThat(script).contains("\"generateMnemonic\"")
        }

    @Test
    fun callJsMethod_fromBackgroundThread_initializesWebView_and_resolves() {
        val gateway = RecordingGateway()
        val callStartedLatch = CountDownLatch(1)
        val resultFuture = CompletableFuture<String>()
        var createdWebView: WebView? = null
        val client =
            WebviewBridgeClient(
                gateway = gateway,
                webViewFactory = { context ->
                    WebView(context).also {
                        createdWebView = it
                    }
                }
            )

        client.initialize(appContext)

        Thread {
            try {
                resultFuture.complete(
                    runBlocking {
                        callStartedLatch.countDown()
                        client.callJsMethod(
                            method = "generateMnemonic",
                            params = JSONObject().apply { put("length", 128) },
                            timeoutMs = 5_000L,
                            readyWaitMs = 5_000L
                        )
                    }
                )
            } catch (t: Throwable) {
                resultFuture.completeExceptionally(t)
            }
        }.start()

        assertThat(callStartedLatch.await(2, TimeUnit.SECONDS)).isTrue
        val webView = awaitValue { createdWebView }
        gateway.onBridgeReady()
        val script = awaitValue { shadowOf(webView).lastEvaluatedJavascript }
        val id = extractPromiseId(script)
        gateway.onPromiseResult(id, """{"result":"ok"}""")

        assertThat(resultFuture.get(2, TimeUnit.SECONDS)).isEqualTo("ok")
    }

    @Test
    fun callJsMethod_usesReadyFastPath_whenAlreadyReady() =
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
            client.start()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            gateway.onBridgeReady()

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

            val webView = checkNotNull(createdWebView)
            val script = checkNotNull(shadowOf(webView).lastEvaluatedJavascript)
            val id = extractPromiseId(script)
            gateway.onPromiseResult(id, """{"result":"ok"}""")
            runCurrent()

            assertThat(deferred.await()).isEqualTo("ok")
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
    fun callJsMethodAs_returnsRawStringWhenRequested() =
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
            client.start()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            gateway.onBridgeReady()

            val deferred =
                async {
                    client.callJsMethodAs(
                        method = "getValue",
                        params = null,
                        clazz = String::class.java
                    )
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()

            val webView = checkNotNull(createdWebView)
            val script = checkNotNull(shadowOf(webView).lastEvaluatedJavascript)
            val id = extractPromiseId(script)
            gateway.onPromiseResult(id, """{"result":"raw-string"}""")
            runCurrent()

            assertThat(deferred.await()).isEqualTo("raw-string")
        }

    @Test
    fun callJsMethod_coercesNonStringResultToString() =
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

            val numberDeferred =
                async {
                    client.callJsMethod(
                        method = "getNumber",
                        params = null,
                        timeoutMs = 5_000L,
                        readyWaitMs = 5_000L
                    )
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            gateway.onBridgeReady()
            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()

            val webView = awaitValue { createdWebView }
            val numberScript = awaitValue { shadowOf(webView).lastEvaluatedJavascript }
            gateway.onPromiseResult(extractPromiseId(numberScript), """{"result":123}""")
            runCurrent()

            assertThat(numberDeferred.await()).isEqualTo("123")

            val objectDeferred =
                async {
                    client.callJsMethod(
                        method = "getObject",
                        params = null,
                        timeoutMs = 5_000L,
                        readyWaitMs = 5_000L
                    )
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            runCurrent()

            val objectScript = awaitValue { shadowOf(webView).lastEvaluatedJavascript }
            gateway.onPromiseResult(extractPromiseId(objectScript), """{"result":{"k":"v"}}""")
            runCurrent()

            assertThat(objectDeferred.await()).isEqualTo("""{"k":"v"}""")
        }

    @Test
    fun callJsMethod_timesOutWhenBridgeNeverBecomesReady() =
        runTest {
            val gateway = RecordingGateway()
            val client =
                WebviewBridgeClient(
                    gateway = gateway,
                    webViewFactory = { context -> WebView(context) }
                )

            client.initialize(appContext)

            val error =
                async {
                    runCatching {
                        client.callJsMethod(
                            method = "slow",
                            params = null,
                            timeoutMs = 200L,
                            readyWaitMs = 200L
                        )
                    }.exceptionOrNull()
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            runCurrent()

            assertThat(error.await()).isInstanceOf(TimeoutCancellationException::class.java)
        }

    @Test
    fun callJsMethod_propagatesWebViewFactoryFailure() =
        runTest {
            val client =
                WebviewBridgeClient(
                    gateway = RecordingGateway(),
                    webViewFactory = { throw IllegalStateException("webview-fail") }
                )

            client.initialize(appContext)

            val errorDeferred =
                async {
                    runCatching {
                        client.callJsMethod(
                            method = "ping",
                            params = null,
                            timeoutMs = 5_000L,
                            readyWaitMs = 5_000L
                        )
                    }.exceptionOrNull()
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            runCurrent()

            assertThat(errorDeferred.await())
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("webview-fail")
        }

    @Test
    fun callJsMethod_reportsInvalidResponseFormat() =
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

            val errorDeferred =
                async {
                    runCatching {
                        client.callJsMethod(
                            method = "fail",
                            params = null,
                            timeoutMs = 5_000L,
                            readyWaitMs = 5_000L
                        )
                    }.exceptionOrNull()
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            gateway.onBridgeReady()
            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()

            val webView = awaitValue { createdWebView }
            val script = awaitValue { shadowOf(webView).lastEvaluatedJavascript }
            val id = extractPromiseId(script)
            gateway.onPromiseResult(id, """{"status":"ok"}""")
            runCurrent()

            assertThat(errorDeferred.await())
                .isInstanceOf(Exception::class.java)
                .hasMessageContaining("Invalid response format")
        }

    @Test
    fun callJsMethod_reportsMalformedJsonResponse() =
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

            val errorDeferred =
                async {
                    runCatching {
                        client.callJsMethod(
                            method = "fail",
                            params = null,
                            timeoutMs = 5_000L,
                            readyWaitMs = 5_000L
                        )
                    }.exceptionOrNull()
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            gateway.onBridgeReady()
            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()

            val webView = awaitValue { createdWebView }
            val script = awaitValue { shadowOf(webView).lastEvaluatedJavascript }
            val id = extractPromiseId(script)
            gateway.onPromiseResult(id, """not-json""")
            runCurrent()

            assertThat(errorDeferred.await())
                .isInstanceOf(JSONException::class.java)
        }

    @Test
    fun callJsMethod_reportsErrorResponse() =
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

            val errorDeferred =
                async {
                    runCatching {
                        client.callJsMethod(
                            method = "fail",
                            params = null,
                            timeoutMs = 5_000L,
                            readyWaitMs = 5_000L
                        )
                    }.exceptionOrNull()
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            gateway.onBridgeReady()
            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()

            val webView = awaitValue { createdWebView }
            val script = awaitValue { shadowOf(webView).lastEvaluatedJavascript }
            val id = extractPromiseId(script)
            gateway.onPromiseResult(id, """{"error":"boom"}""")
            runCurrent()
            assertThat(errorDeferred.await())
                .isInstanceOf(Exception::class.java)
                .hasMessageContaining("boom")
        }

    @Test
    fun callJsMethod_afterDestroy_recreatesWebViewAndResolves() =
        runTest {
            val gateway = RecordingGateway()
            var factoryCalls = 0
            var createdWebView: WebView? = null
            val client =
                WebviewBridgeClient(
                    gateway = gateway,
                    webViewFactory = { context ->
                        factoryCalls += 1
                        WebView(context).also { createdWebView = it }
                    }
                )

            client.initialize(appContext)
            client.start()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            gateway.onBridgeReady()

            client.destroy()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            assertThat(gateway.clearAllCalled).isTrue

            val deferred =
                async {
                    client.callJsMethod(
                        method = "afterDestroy",
                        params = null,
                        timeoutMs = 5_000L,
                        readyWaitMs = 5_000L
                    )
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            gateway.onBridgeReady()
            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()

            val webView = awaitValue { createdWebView }
            assertThat(factoryCalls).isEqualTo(2)
            val script = awaitValue { shadowOf(webView).lastEvaluatedJavascript }
            gateway.onPromiseResult(extractPromiseId(script), """{"result":"restarted"}""")
            runCurrent()

            assertThat(deferred.await()).isEqualTo("restarted")
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
            try {
                callbackMap.remove(id)?.invoke(resultJson)
            } catch (_: Throwable) {
            }
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

    private fun <T> awaitValue(
        timeoutMs: Long = 2_000L,
        provider: () -> T?
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            provider()?.let { return it }
            Thread.sleep(10)
        }
        error("Timed out waiting for value")
    }

    private fun extractPromiseId(script: String): String {
        val match = Regex(""",\s*"([^"]+)"\);\s*$""").find(script)
        return requireNotNull(match?.groupValues?.get(1)) { "No promise id in script: $script" }
    }
}
