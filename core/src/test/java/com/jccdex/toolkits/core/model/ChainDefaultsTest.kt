package com.jccdex.toolkits.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainDefaultsTest {
    @Test
    fun `Evm getRpcUrls returns non-empty list for ETH`() {
        val urls = ChainDefaults.Evm.getRpcUrls(1L)
        assertTrue("ETH RPC URLs should not be empty", urls.isNotEmpty())
    }

    @Test
    fun `Evm getRpcUrls returns valid URL format`() {
        val urls = ChainDefaults.Evm.getRpcUrls(1L)
        assertTrue("Should have valid URLs", urls.all { it.startsWith("https://") })
    }

    @Test
    fun `Evm getRpcUrls returns empty list for unknown chain`() {
        val urls = ChainDefaults.Evm.getRpcUrls(999999L)
        assertTrue("Unknown chain should return empty list", urls.isEmpty())
    }

    @Test
    fun `Evm getDefaultRpcUrl returns first URL`() {
        val firstUrl = ChainDefaults.Evm.getDefaultRpcUrl(1L)
        assertNotNull("Default URL should not be null", firstUrl)
        assertTrue("Default URL should not be empty", firstUrl.isNotEmpty())
    }

    @Test
    fun `Evm getDefaultRpcUrl returns empty string for unknown chain`() {
        val url = ChainDefaults.Evm.getDefaultRpcUrl(999999L)
        assertEquals("Unknown chain should return empty string", "", url)
    }

    @Test
    fun `Swtc getRpcUrls returns non-empty list`() {
        val urls = ChainDefaults.Swtc.getRpcUrls()
        assertTrue("SWTC RPC URLs should not be empty", urls.isNotEmpty())
    }

    @Test
    fun `Swtc getRpcUrls returns valid URL format`() {
        val urls = ChainDefaults.Swtc.getRpcUrls()
        assertTrue("Should have valid URLs", urls.all { it.startsWith("https://") })
    }

    @Test
    fun `Swtc getDefaultRpcUrl returns first URL`() {
        val firstUrl = ChainDefaults.Swtc.getDefaultRpcUrl()
        assertNotNull("Default URL should not be null", firstUrl)
        assertTrue("Default URL should not be empty", firstUrl.isNotEmpty())
    }

    @Test
    fun `Evm and Swtc configurations are separated`() {
        val ethUrls = ChainDefaults.Evm.getRpcUrls(1L)
        val swtcUrls = ChainDefaults.Swtc.getRpcUrls()

        assertTrue(
            "EVM URLs should not contain SWTC URLs",
            ethUrls.none { it.contains("swtc", ignoreCase = true) }
        )
        assertTrue(
            "SWTC URLs should contain swtc",
            swtcUrls.any { it.contains("swtc", ignoreCase = true) }
        )
    }
}
