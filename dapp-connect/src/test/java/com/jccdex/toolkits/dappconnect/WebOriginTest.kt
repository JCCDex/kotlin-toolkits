package com.jccdex.toolkits.dappconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebOriginTest {
    @Test
    fun normalize_stripsPathQueryFragment() {
        assertEquals(
            "https://example.com",
            WebOrigin.normalize("https://example.com/app?x=1#y")
        )
        assertEquals(
            "http://example.com",
            WebOrigin.normalize("http://EXAMPLE.com/foo")
        )
    }

    @Test
    fun normalize_keepsNonDefaultPort() {
        assertEquals(
            "https://example.com:8443",
            WebOrigin.normalize("https://example.com:8443/path")
        )
    }

    @Test
    fun normalize_rejectsNonHttp() {
        assertNull(WebOrigin.normalize("file:///tmp/x"))
        assertNull(WebOrigin.normalize(""))
        assertNull(WebOrigin.normalize("not-a-url"))
    }
}
