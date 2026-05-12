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
        val config = WebviewBridgeConfig(bridgeUrl = "file:///android_asset/custom.html")

        WebviewBridgeEngine.initialize(appContext, config)

        assertThat(WebviewBridgeEngine.isInitializedForTest()).isTrue
        assertThat(WebviewBridgeEngine.currentConfigForTest()).isEqualTo(config)
    }
}
