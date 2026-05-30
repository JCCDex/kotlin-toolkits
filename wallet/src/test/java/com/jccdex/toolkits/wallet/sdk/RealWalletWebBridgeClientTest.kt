package com.jccdex.toolkits.wallet.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.webviewbridge.WebviewBridgeClient
import com.jccdex.toolkits.webviewbridge.WebviewBridgeConfig
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RealWalletWebBridgeClientTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun delegatesToWebviewBridgeClient() =
        runTest {
            val bridgeClient = mockk<WebviewBridgeClient>(relaxed = true)
            coEvery {
                bridgeClient.callJsMethod(
                    method = "signMessage",
                    params = any(),
                    timeoutMs = any(),
                    readyWaitMs = any()
                )
            } returns "signed"
            coEvery {
                bridgeClient.callJsMethodAs(
                    method = "deriveChild",
                    params = any(),
                    clazz = String::class.java,
                    timeoutMs = any(),
                    readyWaitMs = any()
                )
            } returns "derived"

            val client = RealWalletWebBridgeClient(bridgeClient)
            val config = WebviewBridgeConfig(bridgeUrl = "file:///android_asset/wallet-bridge.html")
            client.initialize(context, config)
            client.start()

            val callResult = client.call("signMessage", JSONObject().apply { put("data", "hello") })
            val callAsResult = client.callAs("deriveChild", null, String::class.java)
            client.destroy()

            verify { bridgeClient.initialize(context.applicationContext, config) }
            verify { bridgeClient.start() }
            verify { bridgeClient.destroy() }
            assertThat(callResult).isEqualTo("signed")
            assertThat(callAsResult).isEqualTo("derived")
        }
}
