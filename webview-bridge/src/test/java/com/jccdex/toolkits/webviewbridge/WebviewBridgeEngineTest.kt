package com.jccdex.toolkits.webviewbridge

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WebviewBridgeEngineTest {
    private val appContext = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        WebviewBridgeEngine.destroy()
        JsPromiseGateway.clearAll()
    }

    @Test
    fun initialize_setsContextAndConfig() {
        val config = WebviewBridgeConfig(bridgeUrl = androidAssetUrl("custom.html"))

        WebviewBridgeEngine.initialize(appContext, config)

        assertThat(WebviewBridgeEngine.isInitializedForTest()).isTrue
        assertThat(WebviewBridgeEngine.currentConfigForTest()).isEqualTo(config)
    }

    @Test
    fun start_and_destroy_are_safe_after_initialize() {
        WebviewBridgeEngine.initialize(appContext)

        WebviewBridgeEngine.start()
        WebviewBridgeEngine.destroy()

        assertThat(WebviewBridgeEngine.isInitializedForTest()).isTrue
    }

    @Test
    fun androidAssetUrl_buildsFileUrl() {
        assertThat(androidAssetUrl("wallet-bridge.html")).isEqualTo("file:///android_asset/wallet-bridge.html")
    }

    @Test
    fun config_defaults_areStable() {
        val config = WebviewBridgeConfig()

        assertThat(config.bridgeUrl).isEqualTo("file:///android_asset/bridge.html")
        assertThat(config.jsInterfaceName).isEqualTo("JSBridge")
        assertThat(config.consoleTag).isEqualTo("WebViewConsole")
    }

    @Test
    fun callJsMethodAs_throwsWhenNotInitialized() {
        WebviewBridgeEngine.destroy()

        assertThatThrownBy {
            runTest {
                WebviewBridgeEngine.callJsMethodAs("ping", null, String::class.java)
            }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun callJsMethod_afterDestroy_recreatesWebViewAndResolves() =
        runTest {
            WebviewBridgeEngine.initialize(appContext)
            WebviewBridgeEngine.start()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            JsPromiseGateway.onBridgeReady()

            WebviewBridgeEngine.destroy()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            JsPromiseGateway.clearAll()

            val deferred =
                async {
                    WebviewBridgeEngine.callJsMethod(
                        method = "afterDestroy",
                        params = null,
                        timeoutMs = 5_000L,
                        readyWaitMs = 5_000L
                    )
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            JsPromiseGateway.onBridgeReady()
            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            runCurrent()

            val promiseId = checkNotNull(JsPromiseGateway.callbackMap.keys.firstOrNull())
            JsPromiseGateway.onPromiseResult(promiseId, """{"result":"restarted"}""")
            runCurrent()

            assertThat(deferred.await()).isEqualTo("restarted")
        }

    @Test
    fun callMethods_delegateToDefaultClient() =
        runTest {
            WebviewBridgeEngine.initialize(appContext)
            WebviewBridgeEngine.start()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            JsPromiseGateway.onBridgeReady()

            val first =
                async {
                    WebviewBridgeEngine.callJsMethod(
                        method = "generateMnemonic",
                        params = JSONObject().apply { put("length", 128) }
                    )
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            runCurrent()

            val firstId = checkNotNull(JsPromiseGateway.callbackMap.keys.firstOrNull())
            JsPromiseGateway.onPromiseResult(firstId, """{"result":"ok"}""")
            runCurrent()

            assertThat(first.await()).isEqualTo("ok")

            val second =
                async {
                    WebviewBridgeEngine.callJsMethodAs(
                        method = "getValue",
                        params = null,
                        clazz = String::class.java
                    )
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            runCurrent()

            val secondId = checkNotNull(JsPromiseGateway.callbackMap.keys.firstOrNull())
            JsPromiseGateway.onPromiseResult(secondId, """{"result":"raw"}""")
            runCurrent()

            assertThat(second.await()).isEqualTo("raw")
        }
}
