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

    // ── 废弃端点清单（宿主升级时按此清理本地节点库） ─────────────────────────

    @Test
    fun `Evm getDeprecatedRpcUrls returns retired polygon endpoint`() {
        val deprecated = ChainDefaults.Evm.getDeprecatedRpcUrls(137L)

        assertTrue(
            "polygon-rpc.com should be listed as retired",
            deprecated.contains("https://polygon-rpc.com")
        )
    }

    @Test
    fun `Evm getDeprecatedRpcUrls returns empty set for unknown chain`() {
        assertTrue(
            "Unknown chain should have no retired endpoints",
            ChainDefaults.Evm.getDeprecatedRpcUrls(999999L).isEmpty()
        )
    }

    /**
     * 关键不变式：废弃端点不能同时出现在当前默认清单里，否则会把已经下线的节点当默认发出去。
     */
    @Test
    fun `Evm current and deprecated rpc urls are disjoint for every chain`() {
        evmChainIds().forEach { chainId ->
            val current = ChainDefaults.Evm.getRpcUrls(chainId).toSet()
            val deprecated = ChainDefaults.Evm.getDeprecatedRpcUrls(chainId)

            assertTrue(
                "chainId=$chainId must not serve a retired endpoint as current: " +
                    (current intersect deprecated),
                (current intersect deprecated).isEmpty()
            )
        }
    }

    @Test
    fun `Evm polygon default endpoint is the verified publicnode url`() {
        assertEquals(
            "https://polygon-bor-rpc.publicnode.com",
            ChainDefaults.Evm.getDefaultRpcUrl(137L)
        )
    }

    /**
     * 宿主（如 ccdao 的 purgeDeprecatedEndpoints）用**精确字符串**匹配本地节点行来删除废弃节点，
     * 因此这里的 URL 格式就是跨仓契约：必须是 https 且无尾斜杠，否则清理会静默失效。
     */
    @Test
    fun `Evm rpc urls are https without trailing slash so hosts can match them exactly`() {
        val chainIds = evmChainIds()
        val current = chainIds.flatMap { ChainDefaults.Evm.getRpcUrls(it) }
        val deprecated = chainIds.flatMap { ChainDefaults.Evm.getDeprecatedRpcUrls(it).toList() }
        val all = current + deprecated

        assertTrue("ChainDefaults should know at least one EVM endpoint", all.isNotEmpty())

        val notHttps = all.filterNot { it.startsWith("https://") }
        assertTrue("every endpoint must use https: $notHttps", notHttps.isEmpty())

        val withTrailingSlash = all.filter { it.endsWith("/") }
        assertTrue("no endpoint may end with '/': $withTrailingSlash", withTrailingSlash.isEmpty())
    }

    /**
     * SWTC 侧同样要暴露废弃清单能力（当前为空），否则宿主的清理逻辑无法对称覆盖 SWTC，
     * 官方节点退役时又得改一次宿主代码。
     */
    @Test
    fun `Swtc getDeprecatedRpcUrls is available and disjoint from current urls`() {
        val deprecated = ChainDefaults.Swtc.getDeprecatedRpcUrls()
        val current = ChainDefaults.Swtc.getRpcUrls().toSet()

        assertTrue("retired SWTC endpoints must never be served as current", (current intersect deprecated).isEmpty())
    }

    private fun evmChainIds(): List<Long> = ChainType.entries.filter { it.isEvmChain() }.mapNotNull { it.evmChainId }
}
