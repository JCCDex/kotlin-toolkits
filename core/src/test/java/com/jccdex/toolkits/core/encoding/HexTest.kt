package com.jccdex.toolkits.core.encoding

import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {
    @Test
    fun `toHex produces lowercase hex`() {
        assertEquals("deadbeef", byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()).toHex())
        assertEquals("00", byteArrayOf(0).toHex())
        assertEquals("", ByteArray(0).toHex())
    }

    @Test
    fun `fromHex round-trips`() {
        val bytes = byteArrayOf(0x12, 0x34, 0xab.toByte(), 0xcd.toByte())
        assertEquals(bytes.toList(), "1234abcd".fromHex().toList())
        assertEquals(bytes.toList(), bytes.toHex().fromHex().toList())
    }
}
