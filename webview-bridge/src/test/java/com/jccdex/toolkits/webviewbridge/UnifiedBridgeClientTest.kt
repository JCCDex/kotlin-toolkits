package com.jccdex.toolkits.webviewbridge

import android.app.Application
import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedBridgeClientTest {
    private val appContext = ApplicationProvider.getApplicationContext<Application>()

    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun unifiedBridgePage_loadsUnifiedAssetUrl() {
        val gateway = RecordingGateway()
        var createdWebView: WebView? = null
        val client =
            WebviewBridgeClient(
                gateway = gateway,
                webViewFactory = { context ->
                    WebView(context).also { createdWebView = it }
                }
            )

        client.initialize(
            appContext,
            WebviewBridgeConfig(bridgeUrl = androidAssetUrl("unified-bridge.html"))
        )
        client.start()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(shadowOf(checkNotNull(createdWebView)).lastLoadedUrl)
            .isEqualTo("file:///android_asset/unified-bridge.html")
    }

    @Test
    fun unifiedBridge_dispatchesWalletAndDidMethodsWithDistinctCallbacks() =
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

            client.initialize(
                appContext,
                WebviewBridgeConfig(bridgeUrl = androidAssetUrl("unified-bridge.html"))
            )
            client.start()
            shadowOf(Looper.getMainLooper()).idle()
            gateway.onBridgeReady()

            val walletDeferred =
                async {
                    client.callJsMethod(
                        method = "validateMnemonic",
                        params =
                            JSONObject().apply {
                                put("mnemonic", testMnemonic)
                                put("language", "english")
                            },
                        timeoutMs = 5_000L,
                        readyWaitMs = 5_000L
                    )
                }
            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            val webView = checkNotNull(createdWebView)
            val walletScript = checkNotNull(shadowOf(webView).lastEvaluatedJavascript)
            assertThat(walletScript).contains("validateMnemonic")
            val walletId = extractPromiseId(walletScript)
            gateway.onPromiseResult(walletId, """{"result":true}""")
            runCurrent()
            assertThat(walletDeferred.await()).isEqualTo("true")

            val didDeferred =
                async {
                    client.callJsMethod(
                        method = "didStat",
                        params = JSONObject().apply { put("did", "did:swtc:abcdef") },
                        timeoutMs = 5_000L,
                        readyWaitMs = 5_000L
                    )
                }
            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            val didScript = checkNotNull(shadowOf(webView).lastEvaluatedJavascript)
            assertThat(didScript).contains("didStat")
            val didId = extractPromiseId(didScript)
            assertThat(didId).isNotEqualTo(walletId)
            gateway.onPromiseResult(didId, """{"result":{"exists":false}}""")
            runCurrent()
            assertThat(didDeferred.await()).contains("exists")
        }

    @Test
    fun reloadBridgePage_clearsReadyStateAndReloadsUrl() {
        val gateway = RecordingGateway()
        var createdWebView: WebView? = null
        val client =
            WebviewBridgeClient(
                gateway = gateway,
                webViewFactory = { context ->
                    WebView(context).also { createdWebView = it }
                }
            )

        client.initialize(
            appContext,
            WebviewBridgeConfig(bridgeUrl = androidAssetUrl("unified-bridge.html"))
        )
        client.start()
        shadowOf(Looper.getMainLooper()).idle()
        gateway.onBridgeReady()
        assertThat(gateway.isReady()).isTrue

        client.reloadBridgePage()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(gateway.clearAllCalled).isTrue
        assertThat(gateway.resetReadyCalled).isTrue
        assertThat(gateway.isReady()).isFalse
        assertThat(shadowOf(checkNotNull(createdWebView)).lastLoadedUrl)
            .isEqualTo("file:///android_asset/unified-bridge.html")
    }

    private fun extractPromiseId(script: String): String {
        val match = Regex(""",\s*"([^"]+)"\);\s*$""").find(script)
        return requireNotNull(match?.groupValues?.get(1)) { "No promise id in script: $script" }
    }

    private class RecordingGateway : IPromiseGateway {
        override val callbackMap = ConcurrentHashMap<String, (String) -> Unit>()
        private val readyListeners = mutableListOf<() -> Unit>()

        var ready = false
        var resetReadyCalled = false
        var clearAllCalled = false
        override var pageActive = true

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
}
