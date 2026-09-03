package com.jccdex.toolkits.webviewbridge

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ToolkitBridgeRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun shutdown_destroysSharedBridgeClient() {
        val client = SharedWebviewBridge.client(context)
        assertThat(client.isInitializedForTest()).isTrue

        ToolkitBridgeRuntime.shutdown()

        val recreated = SharedWebviewBridge.client(context)
        assertThat(recreated).isNotSameAs(client)
        ToolkitBridgeRuntime.shutdown()
    }

    @Test
    fun reloadSharedBridge_keepsClientAlive() {
        val client = SharedWebviewBridge.client(context)
        ToolkitBridgeRuntime.reloadSharedBridge()
        shadowOf(Looper.getMainLooper()).idle()

        val same = SharedWebviewBridge.client(context)
        assertThat(same).isSameAs(client)
        ToolkitBridgeRuntime.shutdown()
    }
}
