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
class WebviewBridgeClientTest {
    private val appContext = ApplicationProvider.getApplicationContext<Application>()
    private val client = WebviewBridgeClient()

    @After
    fun tearDown() {
        client.destroy()
    }

    @Test
    fun androidAssetUrl_buildsFileUrl() {
        assertThat(androidAssetUrl("wallet-bridge.html")).isEqualTo("file:///android_asset/wallet-bridge.html")
    }

    @Test
    fun config_defaults_areStable() {
        val config = WebviewBridgeConfig()

        assertThat(config.bridgeUrl).isEqualTo("file:///android_asset/wallet-bridge.html")
        assertThat(config.jsInterfaceName).isEqualTo("JSBridge")
        assertThat(config.consoleTag).isEqualTo("WebViewConsole")
    }

    @Test
    fun config_rejectsRemoteBridgeUrl() {
        assertThatThrownBy { WebviewBridgeConfig(bridgeUrl = "https://evil.com/page.html") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun config_rejectsUnknownAsset() {
        assertThatThrownBy { WebviewBridgeConfig(bridgeUrl = androidAssetUrl("bridge.html")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun config_acceptsKnownBridgePages() {
        assertThat(WebviewBridgeConfig(bridgeUrl = androidAssetUrl("wallet-bridge.html")).bridgeUrl)
            .isEqualTo("file:///android_asset/wallet-bridge.html")
        assertThat(WebviewBridgeConfig(bridgeUrl = androidAssetUrl("did-bridge.html")).bridgeUrl)
            .isEqualTo("file:///android_asset/did-bridge.html")
        assertThat(WebviewBridgeConfig(bridgeUrl = androidAssetUrl("unified-bridge.html")).bridgeUrl)
            .isEqualTo("file:///android_asset/unified-bridge.html")
    }

    @Test
    fun start_and_destroy_are_safe_after_initialize() {
        client.initialize(appContext)
        client.start()
        client.destroy()

        assertThat(client.isInitializedForTest()).isTrue
    }

    @Test
    fun initialize_afterStart_throws() {
        client.initialize(appContext)
        client.start()

        assertThatThrownBy { client.initialize(appContext) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun callJsMethod_afterDestroy_recreatesWebViewAndResolves() =
        runTest {
            val gateway = PromiseGatewayImpl()
            val bridgeClient = WebviewBridgeClient(gateway)
            bridgeClient.initialize(appContext)
            bridgeClient.start()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            gateway.onBridgeReady()

            bridgeClient.destroy()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            gateway.clearAll()

            val deferred =
                async {
                    bridgeClient.callJsMethod(
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
            runCurrent()

            val promiseId = checkNotNull(gateway.callbackMap.keys.firstOrNull())
            gateway.pageActive = true
            gateway.onPromiseResult(promiseId, """{"result":"restarted"}""")
            runCurrent()

            assertThat(deferred.await()).isEqualTo("restarted")
            bridgeClient.destroy()
        }

    @Test
    fun callMethods_delegateToClient() =
        runTest {
            val gateway = PromiseGatewayImpl()
            val bridgeClient = WebviewBridgeClient(gateway)
            bridgeClient.initialize(appContext)
            bridgeClient.start()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            gateway.onBridgeReady()

            val first =
                async {
                    bridgeClient.callJsMethod(
                        method = "generateMnemonic",
                        params = JSONObject().apply { put("length", 128) }
                    )
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            runCurrent()

            val firstId = checkNotNull(gateway.callbackMap.keys.firstOrNull())
            gateway.pageActive = true
            gateway.onPromiseResult(firstId, """{"result":"ok"}""")
            runCurrent()

            assertThat(first.await()).isEqualTo("ok")

            val second =
                async {
                    bridgeClient.callJsMethodAs(
                        method = "getValue",
                        params = null,
                        clazz = String::class.java
                    )
                }

            runCurrent()
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            runCurrent()

            val secondId = checkNotNull(gateway.callbackMap.keys.firstOrNull())
            gateway.pageActive = true
            gateway.onPromiseResult(secondId, """{"result":"raw"}""")
            runCurrent()

            assertThat(second.await()).isEqualTo("raw")
            bridgeClient.destroy()
        }

    @Test
    fun initialize_setsContextAndConfig() {
        val config = WebviewBridgeConfig(bridgeUrl = androidAssetUrl("did-bridge.html"))

        client.initialize(appContext, config)

        assertThat(client.isInitializedForTest()).isTrue
        assertThat(client.currentConfigForTest()).isEqualTo(config)
    }

    @Test
    fun isInitializedForTest_isFalse_beforeInitialize() {
        assertThat(client.isInitializedForTest()).isFalse
    }

    @Test
    fun defaultConfig_usesBridgeHtml() {
        client.initialize(appContext)

        assertThat(client.currentConfigForTest()).isEqualTo(WebviewBridgeConfig())
    }

    @Test
    fun callJsMethod_throwsWhenNotInitialized() {
        assertThatThrownBy {
            runTest {
                client.callJsMethod("ping")
            }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun callJsMethodAs_throwsWhenNotInitialized() {
        assertThatThrownBy {
            runTest {
                client.callJsMethodAs("ping", null, String::class.java)
            }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun start_throwsWhenNotInitialized() {
        assertThatThrownBy {
            client.start()
            shadowOf(Looper.getMainLooper()).idle()
        }.isInstanceOf(IllegalStateException::class.java)
    }

    // L-10: attach/detach lifecycle tests
    @Test
    fun attach_acceptsActivityContext() {
        client.initialize(appContext)
        // Use Robolectric to create an Activity
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        client.attach(activity)

        assertThat(client.isInitializedForTest()).isTrue
    }

    @Test
    fun attach_rejectsNonActivityContext() {
        client.initialize(appContext)

        // Application context is not an Activity, should throw
        assertThatThrownBy {
            client.attach(appContext)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("requires an Activity context")
    }

    @Test
    fun detach_removesActivityReferenceNotDestroy() {
        client.initialize(appContext)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        client.attach(activity)

        // detach should only remove reference, not destroy
        client.detach()

        // Client is still initialized (not destroyed)
        assertThat(client.isInitializedForTest()).isTrue
    }

    @Test
    fun canReinitializeAfterDetach() {
        val config = WebviewBridgeConfig(bridgeUrl = androidAssetUrl("did-bridge.html"))

        client.initialize(appContext, config)
        client.detach()

        // Should be able to initialize again
        client.initialize(appContext, config)
        assertThat(client.isInitializedForTest()).isTrue
    }
}
