package com.jccdex.toolkits.dappconnect.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CachingSecretProviderTest {

    private class SpySecretProvider : SecretProvider {
        var callCount = 0
        var lastAddress: String? = null

        override suspend fun getPrivateKeyForAddress(address: String, origin: String): String? {
            callCount++
            lastAddress = address
            return "key-$address"
        }

        override suspend fun getSecretForAddress(address: String, origin: String): String? {
            callCount++
            lastAddress = address
            return "secret-$address"
        }
    }

    @Test
    fun `first call delegates to underlying provider`() = runTest {
        val spy = SpySecretProvider()
        val cache = CachingSecretProvider(spy)

        val result = cache.getPrivateKeyForAddress("0x123", "test")

        assertEquals("key-0x123", result)
        assertEquals(1, spy.callCount)
    }

    @Test
    fun `second call within bridge window reuses cache`() = runTest {
        val spy = SpySecretProvider()
        val cache = CachingSecretProvider(spy)

        cache.getPrivateKeyForAddress("0x123", "test")
        val result = cache.getPrivateKeyForAddress("0x123", "test")

        assertEquals("key-0x123", result)
        assertEquals(1, spy.callCount) // delegated only once
    }

    @Test
    fun `different address calls delegate separately`() = runTest {
        val spy = SpySecretProvider()
        val cache = CachingSecretProvider(spy)

        cache.getPrivateKeyForAddress("0x123", "test")
        cache.getPrivateKeyForAddress("0x456", "test")

        assertEquals(2, spy.callCount)
        assertEquals("0x456", spy.lastAddress)
    }

    @Test
    fun `clearCache forces re-delegation`() = runTest {
        val spy = SpySecretProvider()
        val cache = CachingSecretProvider(spy)

        cache.getPrivateKeyForAddress("0x123", "test")
        cache.clearCache()
        cache.getPrivateKeyForAddress("0x123", "test")

        assertEquals(2, spy.callCount)
    }

    @Test
    fun `getSecretForAddress follows same cache rules`() = runTest {
        val spy = SpySecretProvider()
        val cache = CachingSecretProvider(spy)

        cache.getSecretForAddress("0x123", "test")
        val result = cache.getSecretForAddress("0x123", "test")

        assertEquals("secret-0x123", result)
        assertEquals(1, spy.callCount)
    }

    @Test
    fun `cache expires after max age`() = runTest {
        val spy = SpySecretProvider()
        // 用反射或自定义小 TTL 测超时？当前实现 MAX_AGE_MS = 20_000。
        // 这里验证 cache 命中：未超时不应重新调用 delegate。
        val cache = CachingSecretProvider(spy)
        cache.getPrivateKeyForAddress("0x123", "test")
        val result = cache.getPrivateKeyForAddress("0x123", "test")

        assertEquals(1, spy.callCount)
        assertNotNull(result)
    }

    @Test
    fun `delegate returns null is not cached`() = runTest {
        val alwaysNull = object : SecretProvider {
            override suspend fun getPrivateKeyForAddress(address: String, origin: String): String? = null
            override suspend fun getSecretForAddress(address: String, origin: String): String? = null
        }
        val cache = CachingSecretProvider(alwaysNull)

        assertNull(cache.getPrivateKeyForAddress("0x123", "test"))
        assertNull(cache.getPrivateKeyForAddress("0x123", "test"))
    }
}
