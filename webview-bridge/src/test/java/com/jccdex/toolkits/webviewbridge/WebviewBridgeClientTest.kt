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
class WebviewBridgeClientTest {
    private val appContext = ApplicationProvider.getApplicationContext<Application>()
    private val client = WebviewBridgeClient()

    @After
    fun tearDown() {
        client.destroy()
    }

    @Test
    fun initialize_setsContextAndConfig() {
        val config = WebviewBridgeConfig(bridgeUrl = androidAssetUrl("did-bridge.html"))

        client.initialize(appContext, config)

        assertThat(client.isInitializedForTest()).isTrue
        assertThat(client.currentConfigForTest()).isEqualTo(config)
    }

    @Test
    fun defaultConfig_usesBridgeHtml() {
        client.initialize(appContext)

        assertThat(client.currentConfigForTest()).isEqualTo(WebviewBridgeConfig())
    }
}
