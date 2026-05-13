package com.jccdex.toolkits.webviewbridge

import android.webkit.JavascriptInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal interface IPromiseGateway {
    val callbackMap: ConcurrentHashMap<String, (String) -> Unit>

    fun onPromiseResult(
        id: String,
        resultJson: String
    )

    fun onBridgeReady()

    fun isReady(): Boolean

    fun addReadyListener(listener: () -> Unit): () -> Unit

    fun resetReady()

    fun clearAll()
}

internal open class PromiseGatewayImpl : IPromiseGateway {
    override val callbackMap: ConcurrentHashMap<String, (String) -> Unit> = ConcurrentHashMap()

    private val readyListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var ready = false

    @Suppress("unused")
    @JavascriptInterface
    override fun onPromiseResult(
        id: String,
        resultJson: String
    ) {
        callbackMap.remove(id)?.invoke(resultJson)
    }

    @Suppress("unused")
    @JavascriptInterface
    override fun onBridgeReady() {
        ready = true
        for (listener in readyListeners) {
            try {
                listener.invoke()
            } catch (_: Throwable) {
            }
        }
        readyListeners.clear()
    }

    override fun isReady(): Boolean = ready

    override fun addReadyListener(listener: () -> Unit): () -> Unit {
        if (ready) {
            try {
                listener.invoke()
            } catch (_: Throwable) {
            }
            return {}
        }

        readyListeners.add(listener)
        return { readyListeners.remove(listener) }
    }

    override fun resetReady() {
        ready = false
        readyListeners.clear()
    }

    override fun clearAll() {
        callbackMap.clear()
        readyListeners.clear()
        ready = false
    }
}

object JsPromiseGateway : IPromiseGateway {
    private val delegate = PromiseGatewayImpl()

    override val callbackMap: ConcurrentHashMap<String, (String) -> Unit>
        get() = delegate.callbackMap

    @Suppress("unused")
    @JavascriptInterface
    override fun onPromiseResult(
        id: String,
        resultJson: String
    ) {
        delegate.onPromiseResult(id, resultJson)
    }

    @Suppress("unused")
    @JavascriptInterface
    override fun onBridgeReady() {
        delegate.onBridgeReady()
    }

    override fun isReady(): Boolean = delegate.isReady()

    override fun addReadyListener(listener: () -> Unit): () -> Unit = delegate.addReadyListener(listener)

    override fun resetReady() {
        delegate.resetReady()
    }

    override fun clearAll() {
        delegate.clearAll()
    }
}
