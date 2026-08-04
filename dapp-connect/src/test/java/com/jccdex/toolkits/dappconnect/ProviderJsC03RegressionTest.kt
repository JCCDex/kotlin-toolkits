package com.jccdex.toolkits.dappconnect

import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guards for C-03: provider must not expose forgeable response globals.
 * Reads the asset from the module source tree (no Android resource merge required).
 */
class ProviderJsC03RegressionTest {

    @Test
    fun providerJs_doesNotExposeWindowSendResponseOrRequestQueue() {
        val js = loadProviderAsset()

        assertFalse(
            js.contains("window.ccdao.sendResponse =") ||
                js.contains("window.ccdao.sendResponse="),
            "sendResponse must not be assigned on window (C-03)"
        )
        assertFalse(
            js.contains("window.ccdao.sendError =") ||
                js.contains("window.ccdao.sendError="),
            "sendError must not be assigned on window (C-03)"
        )
        assertFalse(
            js.contains("window._ccdaoRequestQueue"),
            "request queue must stay in IIFE closure (C-03)"
        )
        assertTrue(
            js.contains(NativeResponseChannel.HANDSHAKE),
            "provider must listen for native port handshake"
        )
        assertTrue(
            js.contains("requestQueue"),
            "provider must keep an in-closure requestQueue"
        )
    }

    private fun loadProviderAsset(): String {
        val candidates =
            listOf(
                File("src/main/assets/ccdao-eip1193-provider.js"),
                File("dapp-connect/src/main/assets/ccdao-eip1193-provider.js")
            )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("ccdao-eip1193-provider.js not found from ${File(".").absolutePath}")
        return file.readText()
    }
}
