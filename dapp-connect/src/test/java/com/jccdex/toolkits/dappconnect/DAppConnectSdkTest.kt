package com.jccdex.toolkits.dappconnect

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DAppConnectSdkTest {
    @Test
    fun `isSafeUrl accepts valid https url`() {
        assertTrue(DAppConnectSdk.isSafeUrl("https://example.com"))
    }

    @Test
    fun `isSafeUrl accepts valid http url`() {
        assertTrue(DAppConnectSdk.isSafeUrl("http://example.com"))
    }

    @Test
    fun `isSafeUrl accepts url with port`() {
        assertTrue(DAppConnectSdk.isSafeUrl("https://example.com:8443"))
    }

    @Test
    fun `isSafeUrl accepts url with path`() {
        assertTrue(DAppConnectSdk.isSafeUrl("https://example.com/path/to/page"))
    }

    @Test
    fun `isSafeUrl accepts url with query`() {
        assertTrue(DAppConnectSdk.isSafeUrl("https://example.com/?q=test"))
    }

    @Test
    fun `isSafeUrl accepts jdid domain`() {
        assertTrue(DAppConnectSdk.isSafeUrl("https://app.jdid.cn"))
    }

    @Test
    fun `isSafeUrl accepts ccda domain`() {
        assertTrue(DAppConnectSdk.isSafeUrl("https://ccda.ooo"))
    }

    // M-D1: non-http(s) schemes are rejected by the scheme short-circuit BEFORE the
    // WEB_URL fallback, so these run in plain JVM without Robolectric.
    @Test
    fun `isSafeUrl rejects ftp`() {
        assertFalse(DAppConnectSdk.isSafeUrl("ftp://example.com/file"))
    }

    @Test
    fun `isSafeUrl rejects rtsp`() {
        assertFalse(DAppConnectSdk.isSafeUrl("rtsp://example.com/stream"))
    }

    @Test
    fun `isSafeUrl rejects file`() {
        assertFalse(DAppConnectSdk.isSafeUrl("file:///android_asset/index.html"))
    }

    @Test
    fun `isSafeUrl rejects javascript scheme`() {
        assertFalse(DAppConnectSdk.isSafeUrl("javascript:alert(1)"))
    }

    @Test
    fun `isSafeUrl rejects data url`() {
        assertFalse(DAppConnectSdk.isSafeUrl("data:text/html,<h1>hi</h1>"))
    }

    // ── JS helpers ──

    @Test
    fun `loadAddressJs for EVM uses _updateSelectedAddress`() {
        val js = DAppConnectSdk.loadAddressJs("0x1234", isSwtc = false)
        assertTrue(js.contains("_updateSelectedAddress"))
    }

    @Test
    fun `loadAddressJs for SWTC uses _updateSwtcSelectedAddress`() {
        val js = DAppConnectSdk.loadAddressJs("j1234", isSwtc = true)
        assertTrue(js.contains("_updateSwtcSelectedAddress"))
    }
}
