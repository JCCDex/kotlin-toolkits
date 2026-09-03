package com.jccdex.toolkits.webviewbridge

import android.webkit.JavascriptInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal interface IPromiseGateway {
    val callbackMap: ConcurrentHashMap<String, (String) -> Unit>

    /** True while the bridge page is the current loaded page. Callbacks from any other page are
     *  rejected (H-W1): page JS could otherwise forge a signed/address result for an id it saw. */
    var pageActive: Boolean

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
    private val maxResultBytes = 1024 * 1024

    override val callbackMap: ConcurrentHashMap<String, (String) -> Unit> = ConcurrentHashMap()

    private val readyListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var ready = false

    @Volatile
    override var pageActive = false

    @Suppress("unused")
    @JavascriptInterface
    override fun onPromiseResult(
        id: String,
        resultJson: String
    ) {
        // H-W1: only accept results while the bridge page is active, and cap the payload size.
        if (!pageActive) return
        if (resultJson.length > maxResultBytes) return
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
