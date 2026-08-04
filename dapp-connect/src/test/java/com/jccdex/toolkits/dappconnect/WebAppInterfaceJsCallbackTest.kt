package com.jccdex.toolkits.dappconnect

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Legacy evaluateJavascript callback escaping tests (pre–C-03).
 * Production delivery uses [NativeResponseChannel]; these remain to lock quote behavior
 * of the unused [WebAppInterfaceWithWebView.jsCallback] helper.
 */
@RunWith(RobolectricTestRunner::class)
class WebAppInterfaceJsCallbackTest {

    @Test
    fun jsCallback_quotesMaliciousNonce() {
        val malicious = """abc");alert(1);//"""
        val js = WebAppInterfaceWithWebView.jsCallback("sendResponse", malicious, "null")

        assertEquals(
            "window.ccdao.sendResponse(${JSONObject.quote(malicious)}, null)",
            js
        )
        // Quoted form must not leave an unescaped quote that closes the string early.
        assertFalse(js.contains("""sendResponse("abc")"""))
        assertTrue(js.contains("\\\""))
    }

    @Test
    fun jsCallback_acceptsUuidAndNumericFallbackId() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val numericId = "42"

        assertEquals(
            "window.ccdao.sendResponse(\"$uuid\", null)",
            WebAppInterfaceWithWebView.jsCallback("sendResponse", uuid, "null")
        )
        assertEquals(
            "window.ccdao.sendError(\"$numericId\", {})",
            WebAppInterfaceWithWebView.jsCallback("sendError", numericId, "{}")
        )
    }

    @Test
    fun resultToJs_quotesHexStringLiteral() {
        // Unquoted 0x123 would parse as number 291 in JS.
        assertEquals(JSONObject.quote("0x123"), WebAppInterfaceWithWebView.resultToJs("0x123"))
        assertEquals("\"0x123\"", WebAppInterfaceWithWebView.resultToJs("0x123"))
    }

    @Test
    fun resultToJs_quotesStringsWithQuotesAndNewlines() {
        val value = "hello\"world\n"
        assertEquals(JSONObject.quote(value), WebAppInterfaceWithWebView.resultToJs(value))
    }

    @Test
    fun resultToJs_passesThroughJsonAndNull() {
        val obj = JSONObject().put("a", 1)
        assertEquals(obj.toString(), WebAppInterfaceWithWebView.resultToJs(obj))
        assertEquals("null", WebAppInterfaceWithWebView.resultToJs(null))
    }
}
