package com.jccdex.toolkits.nft.remote

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NftRemoteAssetResolverTest {
    @After
    fun tearDown() {
        SsrfGuard.enabled = true
    }

    // ── H-06: SSRF guard rejects private/internal URLs ──

    @Test
    fun `rejects loopback url`() {
        assertFalse(SsrfGuard.check("http://127.0.0.1:8080/metadata"))
        assertFalse(SsrfGuard.check("http://localhost/metadata"))
    }

    @Test
    fun `rejects site local url`() {
        assertFalse(SsrfGuard.check("http://10.0.0.1/metadata"))
        assertFalse(SsrfGuard.check("http://192.168.1.1/metadata"))
        assertFalse(SsrfGuard.check("http://172.16.0.1/metadata"))
    }

    @Test
    fun `rejects link local url`() {
        assertFalse(SsrfGuard.check("http://169.254.1.1/metadata"))
    }

    @Test
    fun `allows public https url`() {
        // Use a public IP so the assertion does not depend on outbound DNS.
        assertTrue(SsrfGuard.check("https://8.8.8.8/metadata.json"))
        assertTrue(SsrfGuard.check("https://1.1.1.1/ipfs/QmExample"))
    }

    @Test
    fun `rejects non-http scheme`() {
        assertFalse(SsrfGuard.check("file:///etc/passwd"))
        assertFalse(SsrfGuard.check("javascript:alert(1)"))
        assertFalse(SsrfGuard.check("ftp://example.com/metadata"))
    }

    @Test
    fun `rejects ipfs scheme`() {
        // java.net.URL does not parse ipfs:// — guard rejects as malformed
        assertFalse(SsrfGuard.check("ipfs://QmExample"))
    }

    @Test
    fun `rejects malformed url`() {
        assertFalse(SsrfGuard.check("not-a-url"))
        assertFalse(SsrfGuard.check(""))
    }

    @Test
    fun `rejects unresolved host fail-closed`() {
        // Non-resolvable hostname: DNS failure must not open the request.
        assertFalse(SsrfGuard.check("http://this-host-should-not-resolve.invalid/metadata"))
    }

    @Test
    fun `allows all when disabled`() {
        SsrfGuard.enabled = false

        assertTrue(SsrfGuard.check("http://127.0.0.1/metadata"))
        assertTrue(SsrfGuard.check("http://192.168.1.1/metadata"))
        assertTrue(SsrfGuard.check("https://example.com/metadata"))
    }

    // ── M-3/M-9N: readTextLimited bounds HTTP response size ──

    @Test
    fun `readTextLimited returns content within limit`() {
        assertEquals("hello", StringReader("hello").buffered().readTextLimited())
    }

    @Test
    fun `readTextLimited aborts on oversized body`() {
        assertNull(StringReader("x".repeat(MAX_HTTP_RESPONSE_CHARS + 1)).buffered().readTextLimited())
    }

    // ── M-8N: resolveRemoteImageUrl must not return internal/SSRF URLs ──

    @Test
    fun `resolveRemoteImageUrl returns http image url`() =
        runTest {
            assertEquals(
                "http://example.com/avatar.png",
                resolveRemoteImageUrl("http://example.com/avatar.png", null)
            )
        }

    @Test
    fun `resolveRemoteImageUrl returns safe external url`() =
        runTest {
            assertEquals("https://8.8.8.8/avatar.png", resolveRemoteImageUrl("https://8.8.8.8/avatar.png", null))
        }

    @Test
    fun `isLoadableRemoteAssetUrl allows http and https and caps data url`() {
        assertTrue(isLoadableRemoteAssetUrl("https://example.com/a.png"))
        assertTrue(isLoadableRemoteAssetUrl("http://example.com/a.png"))
        assertTrue(isLoadableRemoteAssetUrl("data:image/png;base64,AAAA"))
        assertFalse(isLoadableRemoteAssetUrl("data:image/png;base64," + "A".repeat(1024 * 1024 + 1)))
    }

    @Test
    fun `normalizeDisplayRemoteAssetUrl delegates to normalizeRemoteAssetUrl`() {
        assertEquals("http://example.com/image.png", normalizeDisplayRemoteAssetUrl("http://example.com/image.png"))
        assertEquals(
            "https://ipfs.jccdex.cn/ipfs/QmExample",
            normalizeDisplayRemoteAssetUrl("ipfs://QmExample")
        )
    }
}
