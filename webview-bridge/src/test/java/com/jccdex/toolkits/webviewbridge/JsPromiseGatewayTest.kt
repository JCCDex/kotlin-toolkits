package com.jccdex.toolkits.webviewbridge

import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test

class JsPromiseGatewayTest {
    @After
    fun tearDown() {
        JsPromiseGateway.clearAll()
    }

    @Test
    fun onBridgeReady_releasesReadyListeners() {
        var called = false

        JsPromiseGateway.resetReady()
        JsPromiseGateway.addReadyListener {
            called = true
        }

        JsPromiseGateway.onBridgeReady()

        assertThat(called).isTrue
        assertThat(JsPromiseGateway.isReady()).isTrue
    }

    @Test
    fun addReadyListener_invokesImmediatelyWhenAlreadyReady() {
        JsPromiseGateway.resetReady()
        JsPromiseGateway.onBridgeReady()

        var called = false
        JsPromiseGateway.addReadyListener {
            called = true
        }

        assertThat(called).isTrue
    }

    @Test
    fun onPromiseResult_removesCallbackAndInvokesIt() {
        var payload: String? = null
        JsPromiseGateway.callbackMap["id-1"] = { result ->
            payload = result
        }

        JsPromiseGateway.onPromiseResult("id-1", """{"result":"ok"}""")

        assertThat(payload).isEqualTo("""{"result":"ok"}""")
        assertThat(JsPromiseGateway.callbackMap).isEmpty()
    }
}
