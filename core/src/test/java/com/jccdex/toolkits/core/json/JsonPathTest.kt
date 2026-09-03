package com.jccdex.toolkits.core.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonPathTest {
    private val doc = """{"a":{"b":{"c":"value"}},"list":[{"x":1}]}"""

    @Test
    fun `readString walks dot path`() {
        assertEquals("value", JsonPath.readString(doc, "$.a.b.c"))
        assertEquals("value", JsonPath.readString(doc, "a.b.c"))
    }

    @Test
    fun `readString returns null for missing paths`() {
        assertNull(JsonPath.readString(doc, "$.a.b.missing"))
        assertNull(JsonPath.readString(doc, "$.a.b.c.d"))
        assertNull(JsonPath.readString(doc, "$.missing.deep.path"))
    }

    @Test
    fun `readString defaultValue fallback`() {
        assertEquals("fallback", JsonPath.readString(doc, "$.a.missing", "fallback"))
        assertEquals("value", JsonPath.readString(doc, "$.a.b.c", "fallback"))
    }

    @Test
    fun `readString returns null for malformed doc`() {
        assertNull(JsonPath.readString("{not json", "$.a"))
    }

    @Test
    fun `readElement returns the element`() {
        val element = JsonPath.readElement(doc, "$.list")
        assertTrue(element?.isJsonArray == true)
    }

    @Test
    fun `readChainIdLong parses decimal hex and numeric chain ids`() {
        assertEquals(1L, JsonPath.readChainIdLong("""{"chainId":1}""", "$.chainId"))
        assertEquals(1L, JsonPath.readChainIdLong("""{"chainId":"1"}""", "$.chainId"))
        assertEquals(1L, JsonPath.readChainIdLong("""{"chainId":"0x1"}""", "$.chainId"))
        assertEquals(56L, JsonPath.readChainIdLong("""{"chainId":"0x38"}""", "$.chainId"))
        assertNull(JsonPath.readChainIdLong("""{"chainId":"abc"}""", "$.chainId"))
    }

    @Test
    fun `readEvmChainIdLong defaults ethr owner to mainnet when chain id missing`() {
        assertEquals(
            1L,
            JsonPath.readEvmChainIdLong(
                """
                {
                  "credentialSubject": {
                    "owner": "did:ethr:0x12898725Cf301693733D951bb992C30310dBfb3B",
                    "contractAddress": "0x5B5b422A4fEd431882606E7b0D6abb0ba84bDA3a",
                    "tokenId": "4"
                  }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `readEvmChainIdLong defaults ethr subject id to mainnet when chain id missing`() {
        assertEquals(
            1L,
            JsonPath.readEvmChainIdLong(
                """
                {
                  "id": "did:ethr:0x12898725Cf301693733D951bb992C30310dBfb3B#nft-0x5B5b422A4fEd431882606E7b0D6abb0ba84bDA3a-4-did:ethr:0x12898725Cf301693733D951bb992C30310dBfb3B",
                  "credentialSubject": {
                    "id": "did:ethr:0x12898725Cf301693733D951bb992C30310dBfb3B",
                    "contractAddress": "0x5B5b422A4fEd431882606E7b0D6abb0ba84bDA3a",
                    "tokenId": "4"
                  }
                }
                """.trimIndent()
            )
        )
    }
}
