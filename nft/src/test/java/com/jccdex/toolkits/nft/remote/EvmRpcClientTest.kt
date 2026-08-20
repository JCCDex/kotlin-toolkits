package com.jccdex.toolkits.nft.remote

import com.jccdex.toolkits.core.model.ChainDefaults
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `ChainDefaults Evm contains expected chains`() {
        val ethUrls = ChainDefaults.Evm.getRpcUrls(1L)
        val polygonUrls = ChainDefaults.Evm.getRpcUrls(137L)
        val bscUrls = ChainDefaults.Evm.getRpcUrls(56L)
        val baseUrls = ChainDefaults.Evm.getRpcUrls(8453L)
        val arbUrls = ChainDefaults.Evm.getRpcUrls(42161L)

        assertTrue("ETH should have URLs", ethUrls.isNotEmpty())
        assertTrue("Polygon should have URLs", polygonUrls.isNotEmpty())
        assertTrue("BSC should have URLs", bscUrls.isNotEmpty())
        assertTrue("Base should have URLs", baseUrls.isNotEmpty())
        assertTrue("Arbitrum should have URLs", arbUrls.isNotEmpty())
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
