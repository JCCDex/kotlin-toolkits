package com.jccdex.toolkits.webviewbridge

import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test

class PromiseGatewayImplTest {
    private val gateway = PromiseGatewayImpl()

    @After
    fun tearDown() {
        gateway.clearAll()
    }

    @Test
    fun onBridgeReady_notifiesReadyListeners() {
        gateway.resetReady()
        var called = false
        gateway.addReadyListener {
            called = true
        }

        gateway.onBridgeReady()

        assertThat(called).isTrue
        assertThat(gateway.isReady()).isTrue
    }

    @Test
    fun onPromiseResult_invokesCallback_whenPageActive() {
        gateway.pageActive = true
        var result: String? = null
        gateway.callbackMap["id-1"] = { result = it }

        gateway.onPromiseResult("id-1", """{"result":"ok"}""")

        assertThat(result).isEqualTo("""{"result":"ok"}""")
        assertThat(gateway.callbackMap).isEmpty()
    }

    @Test
    fun onPromiseResult_ignoredWhenPageInactive() {
        gateway.pageActive = false
        var invoked = false
        gateway.callbackMap["id-1"] = { invoked = true }

        gateway.onPromiseResult("id-1", """{"result":"forged"}""")

        assertThat(invoked).isFalse
        assertThat(gateway.callbackMap).containsKey("id-1")
    }

    @Test
    fun onPromiseResult_ignoredWhenPayloadTooLarge() {
        gateway.pageActive = true
        var invoked = false
        gateway.callbackMap["id-1"] = { invoked = true }

        gateway.onPromiseResult("id-1", "x".repeat(1024 * 1024 + 1))

        assertThat(invoked).isFalse
        assertThat(gateway.callbackMap).containsKey("id-1")
    }

    @Test
    fun onPromiseResult_ignoresUnknownId() {
        gateway.onPromiseResult("missing", """{"result":"ok"}""")

        assertThat(gateway.callbackMap).isEmpty()
    }

    @Test
    fun onBridgeReady_notifiesAllReadyListeners() {
        gateway.resetReady()
        var firstCalled = false
        var secondCalled = false
        gateway.addReadyListener {
            firstCalled = true
        }
        gateway.addReadyListener {
            secondCalled = true
        }

        gateway.onBridgeReady()

        assertThat(firstCalled).isTrue
        assertThat(secondCalled).isTrue
        assertThat(gateway.isReady()).isTrue
    }

    @Test
    fun addReadyListener_invokesImmediatelyWhenAlreadyReady() {
        gateway.resetReady()
        gateway.onBridgeReady()

        var called = false
        gateway.addReadyListener {
            called = true
        }

        assertThat(called).isTrue
        assertThat(gateway.isReady()).isTrue
    }

    @Test
    fun resetReady_clearsReadyStateAndCallbacks() {
        gateway.onBridgeReady()
        gateway.resetReady()

        assertThat(gateway.isReady()).isFalse
        assertThat(gateway.callbackMap).isEmpty()
    }

    @Test
    fun clearAll_resetsReadyStateAndCallbacks() {
        gateway.callbackMap["id-1"] = {}
        gateway.onBridgeReady()

        gateway.clearAll()

        assertThat(gateway.isReady()).isFalse
        assertThat(gateway.callbackMap).isEmpty()
    }

    @Test
    fun addReadyListener_returnsRemover() {
        gateway.resetReady()
        var called = false
        val remover = gateway.addReadyListener { called = true }

        remover.invoke()
        gateway.onBridgeReady()

        assertThat(called).isFalse
    }
}
