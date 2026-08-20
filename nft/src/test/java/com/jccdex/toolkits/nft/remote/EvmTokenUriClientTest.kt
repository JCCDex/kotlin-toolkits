package com.jccdex.toolkits.nft.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvmTokenUriClientTest {
    @Test
    fun `createDefault returns client with default nodes`() {
        val client = EvmTokenUriClientFactory.createDefault()
        assertEquals(true, client is EvmTokenUriClient)
    }

    @Test
    fun `create with custom provider returns client`() {
        val client =
            EvmTokenUriClientFactory.create { chainId ->
                when (chainId) {
                    1L -> listOf("https://eth.test.com")
                    else -> emptyList()
                }
            }
        assertEquals(true, client is EvmTokenUriClient)
    }

    @Test
    fun `resolveEthrTokenUri with empty rpcUrls returns null`() =
        runBlocking {
            val client = EvmTokenUriClientFactory.create { emptyList() }
            val result = client.resolveEthrTokenUri("0xContract", "1", 1L)
            assertNull(result)
        }

    @Test
    fun `resolveEthrTokenUri with invalid tokenId returns null`() =
        runBlocking {
            val client = EvmTokenUriClientFactory.create { listOf("https://eth.test.com") }
            val result = client.resolveEthrTokenUri("0xContract", "invalid", 1L)
            assertNull(result)
        }

    @Test
    fun `resolveEthrTokenUri with chainId without nodes returns null`() =
        runBlocking {
            val client =
                EvmTokenUriClientFactory.create { chainId ->
                    when (chainId) {
                        1L -> listOf("https://eth.test.com")
                        else -> emptyList()
                    }
                }
            val result = client.resolveEthrTokenUri("0xContract", "1", 999L)
            assertNull(result)
        }
}
