package com.jccdex.toolkits.webviewbridge

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
}
