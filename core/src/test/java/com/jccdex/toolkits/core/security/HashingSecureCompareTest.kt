package com.jccdex.toolkits.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class HashingSecureCompareTest {
    // ── Hashing ──

    @Test
    fun `sha256Hex of bytes is lowercase hex`() {
        // Well-known SHA-256 of "hello".
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Hashing.sha256Hex("hello".toByteArray())
        )
    }

    @Test
    fun `sha256 of stream and bytes agree`() {
        val bytes = "stream-content".toByteArray()
        assertEquals(
            Hashing.sha256(bytes).toList(),
            Hashing.sha256(ByteArrayInputStream(bytes)).toList()
        )
    }

    @Test
    fun `sha256Hex of file matches bytes`() {
        val file = File.createTempFile("hashing-test", ".tmp")
        try {
            file.writeText("file-content")
            assertEquals(Hashing.sha256Hex("file-content".toByteArray()), Hashing.sha256Hex(file))
        } finally {
            file.delete()
        }
    }

    // ── SecureCompare ──

    @Test
    fun `constantTimeHexEquals matches case-insensitively`() {
        assertTrue(SecureCompare.constantTimeHexEquals("ABC123", "abc123"))
        assertTrue(SecureCompare.constantTimeHexEquals("AbC", "aBc"))
    }

    @Test
    fun `constantTimeHexEquals rejects mismatches and length differences`() {
        assertFalse(SecureCompare.constantTimeHexEquals("abc", "abd"))
        assertFalse(SecureCompare.constantTimeHexEquals("abc", "abcd"))
        assertFalse(SecureCompare.constantTimeHexEquals("", "abc"))
    }

    @Test
    fun `constantTimeEquals compares byte arrays`() {
        assertTrue(SecureCompare.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(SecureCompare.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(SecureCompare.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }
}
