package com.jccdex.toolkits.webviewbridge

import android.webkit.JavascriptInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object JsPromiseGateway {
    val callbackMap: ConcurrentHashMap<String, (String) -> Unit> = ConcurrentHashMap()

    private val readyListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var ready = false

    @Suppress("unused")
    @JavascriptInterface
    fun onPromiseResult(
        id: String,
        resultJson: String
    ) {
        callbackMap.remove(id)?.invoke(resultJson)
    }

    @Suppress("unused")
    @JavascriptInterface
    fun onBridgeReady() {
        ready = true
        for (listener in readyListeners) {
            try {
                listener.invoke()
            } catch (_: Throwable) {
            }
        }
        readyListeners.clear()
    }

    fun isReady(): Boolean = ready

    fun addReadyListener(listener: () -> Unit): () -> Unit {
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

    fun resetReady() {
        ready = false
        readyListeners.clear()
    }

    fun clearAll() {
        callbackMap.clear()
        readyListeners.clear()
        ready = false
    }
}
