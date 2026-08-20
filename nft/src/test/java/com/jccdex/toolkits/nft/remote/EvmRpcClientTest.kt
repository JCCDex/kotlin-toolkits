package com.jccdex.toolkits.nft.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EvmRpcClientTest {
    @Test
    fun `ethCall with empty rpcUrls returns null`() =
        runBlocking {
            val client = EvmRpcClient(emptyList())
            val result = client.ethCall("0xContract", "0xData")
            assertNull(result)
        }

    @Test
    fun `DEFAULT_RPC_NODES contains expected chains`() {
        val chains = EvmRpcClient.DEFAULT_RPC_NODES.keys
        assertEquals(true, chains.contains(1L)) // Ethereum
        assertEquals(true, chains.contains(137L)) // Polygon
        assertEquals(true, chains.contains(56L)) // BSC
        assertEquals(true, chains.contains(8453L)) // Base
        assertEquals(true, chains.contains(42161L)) // Arbitrum
    }

    @Test
    fun `concurrent ethCall should be thread safe`() =
        runBlocking {
            val successCount = AtomicInteger(0)
            val failureCount = AtomicInteger(0)
            val client = EvmRpcClient(emptyList())

            val results =
                (1..10).map {
                    async {
                        val result = client.ethCall("0xContract", "0xData")
                        if (result == null) {
                            failureCount.incrementAndGet()
                        } else {
                            successCount.incrementAndGet()
                        }
                        result
                    }
                }.awaitAll()

            assertEquals(10, failureCount.get())
            assertEquals(0, successCount.get())
            assertEquals(10, results.filter { it == null }.size)
        }

    @Test
    fun `ethCall with single node failure returns null`() =
        runBlocking {
            val client = EvmRpcClient(listOf("http://invalid-node-test.invalid"))
            val result = client.ethCall("0xContract", "0xData")
            assertNull(result)
        }
}
