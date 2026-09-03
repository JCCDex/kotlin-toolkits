package com.jccdex.toolkits.dappconnect

import android.webkit.WebView
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class WebAppInterfaceWithWebViewTest {
    private class OriginProbe(webView: WebView) :
        WebAppInterfaceWithWebView(
            webView,
            mockk(relaxed = true),
            mockk(relaxed = true)
        ) {
        fun probeOrigin(): String = getOrigin()
    }

    @Test
    fun getOrigin_usesPresetFromSetOrigin() {
        val context = RuntimeEnvironment.getApplication()
        val webView = WebView(context)
        webView.loadUrl("https://dapp.example.com/dapp")

        val iface = OriginProbe(webView)
        iface.setOrigin("https://preset.example.com")

        // Hosts (jdid/ccdao) must call setOrigin on navigation; preset is authoritative.
        assertEquals("https://preset.example.com", iface.probeOrigin())
    }

    @Test
    fun getOrigin_fallsBackToPresetWhenNoWebUrl() {
        val context = RuntimeEnvironment.getApplication()
        val webView = WebView(context)

        val iface = OriginProbe(webView)
        iface.setOrigin("https://preset.example.com")

        assertEquals("https://preset.example.com", iface.probeOrigin())
    }
}
