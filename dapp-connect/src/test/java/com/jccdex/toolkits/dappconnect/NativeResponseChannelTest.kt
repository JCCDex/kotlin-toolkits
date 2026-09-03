package com.jccdex.toolkits.dappconnect

import android.webkit.WebMessagePort
import android.webkit.WebView
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NativeResponseChannelTest {
    @Test
    fun successPayload_putsQuotedSafeNonceAndStringResult() {
        val malicious = """abc");alert(1);//"""
        val json = NativeResponseChannel.successPayload(malicious, "0x123")
        val obj = JSONObject(json)

        assertEquals(malicious, obj.getString("nonce"))
        assertEquals("0x123", obj.getString("result"))
        assertTrue(json.contains("\\\""))
        assertFalse(json.contains(""":"abc");alert"""))
    }

    @Test
    fun successPayload_preservesJsonStructures() {
        val arr = JSONArray().put("0xabc")
        val obj = JSONObject(NativeResponseChannel.successPayload("n1", arr))
        assertEquals("0xabc", obj.getJSONArray("result").getString(0))

        val nested = JSONObject().put("ok", true)
        val obj2 = JSONObject(NativeResponseChannel.successPayload("n2", nested))
        assertTrue(obj2.getJSONObject("result").getBoolean("ok"))
    }

    @Test
    fun successPayload_nullResult() {
        val obj = JSONObject(NativeResponseChannel.successPayload("n", null))
        assertTrue(obj.isNull("result"))
    }

    @Test
    fun errorPayload_includesCodeAndMessage() {
        val obj = JSONObject(NativeResponseChannel.errorPayload("n", 4001, "User rejected"))
        assertEquals("n", obj.getString("nonce"))
        assertEquals(4001, obj.getJSONObject("error").getInt("code"))
        assertEquals("User rejected", obj.getJSONObject("error").getString("message"))
    }

    @Test
    fun handshakeConstant_matchesProviderContract() {
        assertEquals("__CCDAO_NATIVE_PORT__", NativeResponseChannel.HANDSHAKE)
    }

    @Test
    fun pendingQueue_capsAtMaxAndDropsOldest() {
        val webView = mockk<WebView>(relaxed = true)
        val port = mockk<WebMessagePort>(relaxed = true)
        every { webView.createWebMessageChannel() } returns arrayOf(port, port)
        val channel = NativeResponseChannel(webView)

        repeat(105) { channel.sendSuccess("nonce-$it", "r$it") }

        channel.install()

        verify(exactly = 100) { port.postMessage(any()) }
    }

    @Test
    fun handshakeTargetOrigin_usesWildcardForWebViewCompatibility() {
        val webView = mockk<WebView>(relaxed = true)
        every { webView.url } returns "https://app.jdid.cn/#/home"
        val channel = NativeResponseChannel(webView)

        assertEquals("*", channel.handshakeTargetOrigin())
    }

    @Test
    fun resolveStrictTargetOrigin_normalizesPageUrl() {
        val webView = mockk<WebView>(relaxed = true)
        every { webView.url } returns "https://example.com/dapp/page"
        val channel = NativeResponseChannel(webView)

        assertEquals("https://example.com", channel.resolveStrictTargetOrigin())
    }

    @Test
    fun resolveStrictTargetOrigin_nullForNonHttpUrl() {
        val webView = mockk<WebView>(relaxed = true)
        every { webView.url } returns "file:///android_asset/index.html"
        val channel = NativeResponseChannel(webView)

        assertNull(channel.resolveStrictTargetOrigin())
    }
}
