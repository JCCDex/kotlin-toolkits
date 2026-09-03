package com.jccdex.toolkits.did.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.webviewbridge.WebviewBridgeClient
import com.jccdex.toolkits.webviewbridge.WebviewBridgeConfig
import com.jccdex.toolkits.webviewbridge.androidAssetUrl
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidDidWebRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun delegatesBridgeCallsToInjectedClient() =
        runTest {
            val client = RecordingDidWebBridgeClient()
            val runtime = AndroidDidWebRuntime(context) { client }

            val callResult = runtime.call("generateDidDoc", """{"did":"did:ethr:0x1"}""")
            val typedResult = runtime.callAs("didStat", null, String::class.java)
            val resolved = runtime.resolve("did:ethr:0xabc")

            assertThat(client.initialized).isTrue
            assertThat(client.started).isTrue
            assertThat(client.config?.bridgeUrl).contains("did-bridge.html")
            assertThat(callResult).isEqualTo("doc")
            assertThat(typedResult).isEqualTo("stat")
            assertThat(client.lastMethod).isEqualTo("didResolve")
            assertThat(client.lastParams).contains("did:ethr:0xabc")
            assertThat(resolved).isEqualTo("doc")
        }

    @Test
    fun realBridgeClientDelegatesToWebviewBridgeClient() =
        runTest {
            val bridgeClient = mockk<WebviewBridgeClient>(relaxed = true)
            coEvery {
                bridgeClient.callJsMethod(method = "ping", params = any())
            } returns "pong"
            coEvery {
                bridgeClient.callJsMethodAs(method = "typed", params = any(), clazz = String::class.java)
            } returns "typed-result"

            val client = RealDidWebBridgeClient(bridgeClient)
            client.initialize(context, WebviewBridgeConfig(bridgeUrl = androidAssetUrl("did-bridge.html")))
            client.start()

            assertThat(client.call("ping", null)).isEqualTo("pong")
            assertThat(client.callAs("typed", """{"a":1}""", String::class.java)).isEqualTo("typed-result")

            client.destroy()
            verify { bridgeClient.initialize(context.applicationContext, any()) }
            verify { bridgeClient.start() }
            verify { bridgeClient.destroy() }
        }

    private class RecordingDidWebBridgeClient : IDidWebBridge {
        var initialized = false
        var started = false
        var config: WebviewBridgeConfig? = null
        var lastMethod: String? = null
        var lastParams: String? = null

        override fun initialize(
            context: android.content.Context,
            config: WebviewBridgeConfig
        ) {
            initialized = true
            this.config = config
        }

        override fun start() {
            started = true
        }

        override suspend fun call(
            method: String,
            params: String?
        ): String {
            lastMethod = method
            lastParams = params
            return "doc"
        }

        override suspend fun <T> callAs(
            method: String,
            params: String?,
            clazz: Class<T>
        ): T {
            lastMethod = method
            @Suppress("UNCHECKED_CAST")
            return "stat" as T
        }

        override fun destroy() = Unit
    }
}
