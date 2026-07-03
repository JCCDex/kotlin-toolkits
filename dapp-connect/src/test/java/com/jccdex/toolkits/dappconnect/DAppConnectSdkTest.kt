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

    // Note: reject tests (file, javascript, empty, malformed, ftp) use
    // android.util.Patterns.WEB_URL which requires Robolectric. Those are
    // covered by integration tests in the app layer.

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
