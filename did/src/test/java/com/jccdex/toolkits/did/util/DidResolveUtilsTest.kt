package com.jccdex.toolkits.did.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DidResolveUtilsTest {
    @Test
    fun `isMissingDidDocument detects tombstone and bridge null`() {
        assertTrue(DidResolveUtils.isMissingDidDocument("{}"))
        assertTrue(DidResolveUtils.isMissingDidDocument("null"))
        assertFalse(DidResolveUtils.isMissingDidDocument(""))
        assertFalse(DidResolveUtils.isMissingDidDocument("""{"service":[]}"""))
        assertFalse(
            DidResolveUtils.isMissingDidDocument(
                """{"id":"did:ethr:0x123","updated":"2025-01-01T00:00:00Z"}"""
            )
        )
    }
}
