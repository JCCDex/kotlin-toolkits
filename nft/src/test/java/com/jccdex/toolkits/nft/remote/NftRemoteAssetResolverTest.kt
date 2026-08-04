package com.jccdex.toolkits.nft.remote

import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
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
}
