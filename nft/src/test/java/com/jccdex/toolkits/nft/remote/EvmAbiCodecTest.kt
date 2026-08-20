package com.jccdex.toolkits.nft.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvmAbiCodecTest {
    @Test
    fun `buildTokenUriCallData with normal tokenId`() {
        val result = EvmAbiCodec.buildTokenUriCallData("123")
        assertEquals("0xc87b56dd000000000000000000000000000000000000000000000000000000000000007b", result)
    }

    @Test
    fun `buildTokenUriCallData with tokenId zero`() {
        val result = EvmAbiCodec.buildTokenUriCallData("0")
        assertEquals("0xc87b56dd0000000000000000000000000000000000000000000000000000000000000000", result)
    }

    @Test
    fun `buildTokenUriCallData with large tokenId`() {
        val largeTokenId = "999999999999999999999999999999999999999"
        val result = EvmAbiCodec.buildTokenUriCallData(largeTokenId)
        assertEquals(true, result!!.startsWith("0xc87b56dd"))
    }

    @Test
    fun `buildTokenUriCallData with invalid tokenId`() {
        assertNull(EvmAbiCodec.buildTokenUriCallData("invalid"))
        assertNull(EvmAbiCodec.buildTokenUriCallData("-1"))
        assertNull(EvmAbiCodec.buildTokenUriCallData(""))
        assertNull(EvmAbiCodec.buildTokenUriCallData("  "))
    }

    @Test
    fun `decodeAbiString with normal string`() {
        val hex =
            "0x0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000003" +
                "6162630000000000000000000000000000000000000000000000000000000000"
        val result = EvmAbiCodec.decodeAbiString(hex)
        assertEquals("abc", result)
    }

    @Test
    fun `decodeAbiString with empty result`() {
        assertNull(EvmAbiCodec.decodeAbiString(null))
        assertNull(EvmAbiCodec.decodeAbiString(""))
        assertNull(EvmAbiCodec.decodeAbiString("0x"))
        assertNull(
            EvmAbiCodec.decodeAbiString(
                "0x0000000000000000000000000000000000000000000000000000000000000020"
            )
        )
    }

    @Test
    fun `decodeAbiString without 0x prefix`() {
        val hex =
            "0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000003" +
                "6162630000000000000000000000000000000000000000000000000000000000"
        val result = EvmAbiCodec.decodeAbiString(hex)
        assertEquals("abc", result)
    }

    @Test
    fun `decodeAbiString with very long string`() {
        val longText = "a".repeat(1000)
        val length = longText.length
        val lengthHex = length.toString(16).padStart(64, '0')
        val dataHex =
            longText.toByteArray(Charsets.UTF_8).joinToString("") {
                it.toString(16).padStart(2, '0')
            }
        val hex = "0x0000000000000000000000000000000000000000000000000000000000000020$lengthHex$dataHex"

        val result = EvmAbiCodec.decodeAbiString(hex)
        assertEquals(longText, result)
    }

    @Test
    fun `decodeBytes32 with normal string`() {
        val hex = "0x48656c6c6f20576f726c64000000000000000000000000000000000000000000"
        val result = EvmAbiCodec.decodeBytes32(hex)
        assertEquals("Hello World", result)
    }

    @Test
    fun `decodeBytes32 with empty result`() {
        assertNull(EvmAbiCodec.decodeBytes32(null))
        assertNull(EvmAbiCodec.decodeBytes32(""))
        assertNull(EvmAbiCodec.decodeBytes32("0x"))
    }
}
