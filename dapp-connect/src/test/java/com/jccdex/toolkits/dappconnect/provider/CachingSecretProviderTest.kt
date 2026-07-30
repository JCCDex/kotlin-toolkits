package com.jccdex.toolkits.dappconnect.provider

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CachingSecretProviderTest {

    private open class SpySecretProvider : SecretProvider {
        var callCount = 0
        var lastAddress: String? = null
        var lastOrigin: String? = null

        override suspend fun getPrivateKeyForAddress(address: String, origin: String): String? {
            callCount++
            lastAddress = address
            lastOrigin = origin
            return "key-$address"
        }

        override suspend fun getSecretForAddress(address: String, origin: String): String? {
            callCount++
            lastAddress = address
            lastOrigin = origin
            return "secret-$address"
        }
    }

    @Test
    fun `concurrent calls for same address delegate once`() = runTest {
        val spy = SpySecretProvider()
        val cache = CachingSecretProvider(spy)

        val results =
            (1..3).map {
                async { cache.getPrivateKeyForAddress("0x123", "test") }
            }.map { it.await() }

        assertEquals(listOf("key-0x123", "key-0x123", "key-0x123"), results)
        assertEquals(1, spy.callCount)
    }

    @Test
    fun `concurrent secret calls for same address delegate once`() = runTest {
        val spy = SpySecretProvider()
        val cache = CachingSecretProvider(spy)

        val results =
            (1..3).map {
                async { cache.getSecretForAddress("0x123", "test") }
            }.map { it.await() }

        assertEquals(listOf("secret-0x123", "secret-0x123", "secret-0x123"), results)
        assertEquals(1, spy.callCount)
    }

    @Test
    fun `cache returns value from delegate`() = runTest {
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
        assertEquals(1, spy.callCount)
    }

    @Test
    fun `different address calls delegate separately`() = runTest {
        val spy = SpySecretProvider()
        val cache = CachingSecretProvider(spy)

        cache.getPrivateKeyForAddress("0x123", "test")
        cache.getPrivateKeyForAddress("0x456", "test")

        assertEquals(2, spy.callCount)
    }

    @Test
    fun `cache respects ttl expiry`() = runTest {
        val spy = SpySecretProvider()
        // TTL = 0 means immediate expiry
        val cache = CachingSecretProvider(spy, ttlMillis = 0)

        cache.getPrivateKeyForAddress("0x123", "test")
        delay(1)
        cache.getPrivateKeyForAddress("0x123", "test")

        assertEquals(2, spy.callCount)
    }

    // ── H-01: cross-origin cache isolation ──

    @Test
    fun `different origins do not share cached private key`() = runTest {
        val spy = SpySecretProvider()
        val cache = CachingSecretProvider(spy)

        cache.getPrivateKeyForAddress("0x123", "dapp-a.com")
        cache.getPrivateKeyForAddress("0x123", "dapp-b.com")

        assertEquals(2, spy.callCount)
    }

    @Test
    fun `different origins do not share cached secret`() = runTest {
        val spy = SpySecretProvider()
        val cache = CachingSecretProvider(spy)

        cache.getSecretForAddress("0x123", "dapp-a.com")
        cache.getSecretForAddress("0x123", "dapp-b.com")

        assertEquals(2, spy.callCount)
    }

    @Test
    fun `same origin reuses cache across calls`() = runTest {
        val spy = SpySecretProvider()
        val cache = CachingSecretProvider(spy)

        cache.getPrivateKeyForAddress("0x123", "dapp-a.com")
        cache.getSecretForAddress("0x123", "dapp-a.com")
        val result = cache.getPrivateKeyForAddress("0x123", "dapp-a.com")

        assertEquals("key-0x123", result)
        // pk and sec are separate caches — each only called once
        assertEquals(2, spy.callCount)
    }

    @Test
    fun `clearCache forces new delegate calls`() = runTest {
        val spy = SpySecretProvider()
        val cache = CachingSecretProvider(spy)

        cache.getPrivateKeyForAddress("0x123", "test")
        cache.clearCache()
        cache.getPrivateKeyForAddress("0x123", "test")

        assertEquals(2, spy.callCount)
    }
}
