package com.jccdex.toolkits.wallet.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.webviewbridge.WebviewBridgeClient
import com.jccdex.toolkits.webviewbridge.WebviewBridgeConfig
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidWalletWebRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun realBridgeClientDelegatesToWebviewBridgeClient() =
        kotlinx.coroutines.test.runTest {
            val bridgeClient = mockk<WebviewBridgeClient>(relaxed = true)
            coEvery {
                bridgeClient.callJsMethod(method = "ping", params = any(), timeoutMs = any(), readyWaitMs = any())
            } returns "pong"

            val client = RealWalletWebBridgeClient(bridgeClient)
            client.initialize(context, WebviewBridgeConfig(bridgeUrl = "file:///android_asset/wallet-bridge.html"))
            client.start()

            val result = client.call("ping", null)

            client.destroy()

            verify { bridgeClient.initialize(context.applicationContext, any()) }
            verify { bridgeClient.start() }
            verify { bridgeClient.destroy() }
            assertThat(result).isEqualTo("pong")
        }

    @Test
    fun delegatesToWebviewBridgeClient() = kotlinx.coroutines.test.runTest {
        val client = RecordingBridgeClient()
        val runtime = AndroidWalletWebRuntime(context) { client }

        runtime.start()
        val callResult = runtime.call("signMessage", JSONObject().apply { put("data", "hello") })
        val callAsResult = runtime.callAs("deriveChild", null, String::class.java)
        runtime.destroy()

        assertThat(client.initialized).isTrue
        assertThat(client.started).isTrue
        assertThat(client.lastMethod).isEqualTo("deriveChild")
        assertThat(callResult).isEqualTo("signed")
        assertThat(callAsResult).isEqualTo("derived")
        assertThat(client.destroyed).isTrue
    }

    private class RecordingBridgeClient : IWalletWebBridgeClient {
        var initialized = false
        var started = false
        var destroyed = false
        var lastMethod: String? = null

        override fun initialize(
            context: android.content.Context,
            config: com.jccdex.toolkits.webviewbridge.WebviewBridgeConfig
        ) {
            initialized = true
        }

        override fun start() {
            started = true
        }

        override suspend fun call(
            method: String,
            params: JSONObject?,
            timeoutMs: Long,
            readyWaitMs: Long
        ): String {
            lastMethod = method
            return "signed"
        }

        override suspend fun <T> callAs(
            method: String,
            params: JSONObject?,
            clazz: Class<T>,
            timeoutMs: Long,
            readyWaitMs: Long
        ): T {
            lastMethod = method
            @Suppress("UNCHECKED_CAST")
            return "derived" as T
        }

        override fun destroy() {
            destroyed = true
        }
    }
}
