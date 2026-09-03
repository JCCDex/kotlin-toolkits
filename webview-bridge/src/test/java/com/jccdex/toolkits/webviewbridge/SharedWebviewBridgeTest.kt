package com.jccdex.toolkits.webviewbridge

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharedWebviewBridgeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        SharedWebviewBridge.destroy()
    }

    @Test
    fun client_returnsSameInstance_andUsesUnifiedBridgePage() {
        val first = SharedWebviewBridge.client(context)
        val second = SharedWebviewBridge.client(context)

        assertThat(second).isSameAs(first)
        assertThat(first.currentConfigForTest().bridgeUrl)
            .isEqualTo(androidAssetUrl("unified-bridge.html"))
    }

    @Test
    fun shutdown_allowsRecreate() {
        val first = SharedWebviewBridge.client(context)
        SharedWebviewBridge.destroy()
        val second = SharedWebviewBridge.client(context)

        assertThat(second).isNotSameAs(first)
        assertThat(second.isInitializedForTest()).isTrue
        SharedWebviewBridge.destroy()
    }

    @Test
    fun reloadBridgePage_keepsSameClientInstance() {
        val first = SharedWebviewBridge.client(context)
        first.reloadBridgePage()
        shadowOf(Looper.getMainLooper()).idle()
        val second = SharedWebviewBridge.client(context)

        assertThat(second).isSameAs(first)
        assertThat(first.isInitializedForTest()).isTrue
    }
}
