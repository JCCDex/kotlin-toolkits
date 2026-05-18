package com.jccdex.toolkits.webviewbridge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
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
}
