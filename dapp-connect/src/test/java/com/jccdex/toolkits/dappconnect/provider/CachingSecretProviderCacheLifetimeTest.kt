package com.jccdex.toolkits.dappconnect.provider

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-5 的缓存生命周期回归测试：延迟清空的 epoch 守卫 + 绝对 TTL 过期删除。
 *
 * 为什么需要注入缝：[CachingSecretProvider] 原先把清空协程固定在 `Dispatchers.Default`
 * 并用 `System.currentTimeMillis()` 计时，测试无法驱动"清理任务到点"这一时刻，也无法
 * 在不触发窗口清空的前提下推进时间，所以这两个不变量此前完全没有护栏。
 *
 * 覆盖边界（明确说明，避免误以为覆盖了并发窗口）：
 * - 可以确定性覆盖：窗口到点后清空、epoch 变化时跳过清空、MAX_AGE 过期刷新、
 *   过期删除只作用于自己的 key；
 * - 不能确定性覆盖：清理体**已经开始执行**的同时另一线程 bump epoch 的窄竞态
 *   （pk/sec 路径被各自的 Mutex 串行化，清理协程在另一个调度器上，且 `delay` 之后的
 *   `cancel()` 通常已能拦住尚未恢复的任务）。该窗口由 [CachingSecretProvider.clearIfEpochUnchanged]
 *   的 epoch 判断兜住，并由本文件前三条用例直接锁定该判断的语义。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CachingSecretProviderCacheLifetimeTest {
    private val origin = "https://dapp.example"

    private class CountingProvider : SecretProvider {
        var callCount = 0

        override suspend fun getPrivateKeyForAddress(
            address: String,
            origin: String
        ): String? {
            callCount++
            return "key-$address"
        }

        override suspend fun getSecretForAddress(
            address: String,
            origin: String
        ): String? {
            callCount++
            return "secret-$address"
        }
    }

    // ── 延迟清空：epoch 守卫 ────────────────────────────────────────────────

    @Test
    fun `delayed clear wipes the cache when nothing happened in between`() =
        runTest {
            val spy = CountingProvider()
            val provider =
                CachingSecretProvider(spy, clearDispatcher = StandardTestDispatcher(testScheduler))

            provider.getPrivateKeyForAddress("0x111", origin)
            assertEquals(1, spy.callCount)

            advanceTimeBy(CachingSecretProvider.BRIDGE_MS)
            runCurrent()

            provider.getPrivateKeyForAddress("0x111", origin)
            assertEquals("窗口到点后必须重新委托", 2, spy.callCount)
        }

    @Test
    fun `stale scheduled clear is skipped when the epoch changed`() =
        runTest {
            val spy = CountingProvider()
            val provider =
                CachingSecretProvider(spy, clearDispatcher = StandardTestDispatcher(testScheduler))

            provider.getPrivateKeyForAddress("0x111", origin)
            assertEquals(1, spy.callCount)

            // 模拟"清理任务已排定、但期间发生了新的操作/强制清空"
            val cleared = provider.clearIfEpochUnchanged(provider.currentEpochForTest() - 1)

            assertFalse("epoch 已变化时不得清空", cleared)
            provider.getPrivateKeyForAddress("0x111", origin)
            assertEquals("陈旧清理任务不得抹掉缓存", 1, spy.callCount)
        }

    @Test
    fun `scheduled clear does clear when the epoch is unchanged`() =
        runTest {
            val spy = CountingProvider()
            val provider =
                CachingSecretProvider(spy, clearDispatcher = StandardTestDispatcher(testScheduler))

            provider.getPrivateKeyForAddress("0x111", origin)
            val cleared = provider.clearIfEpochUnchanged(provider.currentEpochForTest())

            assertTrue("epoch 未变时必须清空", cleared)
            provider.getPrivateKeyForAddress("0x111", origin)
            assertEquals(2, spy.callCount)
        }

    @Test
    fun `starting a new op invalidates a scheduled clear`() =
        runTest {
            val spy = CountingProvider()
            val provider =
                CachingSecretProvider(spy, clearDispatcher = StandardTestDispatcher(testScheduler))

            provider.getPrivateKeyForAddress("0x111", origin)
            // 在清理任务到点前开始并完成另一个操作：epoch 自增
            provider.getPrivateKeyForAddress("0x222", origin)

            val epochAfterOps = provider.currentEpochForTest()
            assertFalse(provider.clearIfEpochUnchanged(epochAfterOps - 1))
            assertTrue(provider.clearIfEpochUnchanged(epochAfterOps))
        }

    // ── 绝对 TTL（MAX_AGE_MS）与过期删除 ────────────────────────────────────

    @Test
    fun `entry within max age is reused even after many ops`() =
        runTest {
            var fakeNow = 0L
            val spy = CountingProvider()
            val provider =
                CachingSecretProvider(
                    spy,
                    clearDispatcher = StandardTestDispatcher(testScheduler),
                    now = { fakeNow }
                )

            provider.getPrivateKeyForAddress("0x111", origin)
            fakeNow += CachingSecretProvider.MAX_AGE_MS - 1

            provider.getPrivateKeyForAddress("0x111", origin)

            assertEquals("未到 MAX_AGE 不得重新委托", 1, spy.callCount)
        }

    @Test
    fun `entry older than max age is refreshed`() =
        runTest {
            var fakeNow = 0L
            val spy = CountingProvider()
            val provider =
                CachingSecretProvider(
                    spy,
                    clearDispatcher = StandardTestDispatcher(testScheduler),
                    now = { fakeNow }
                )

            provider.getPrivateKeyForAddress("0x111", origin)
            fakeNow += CachingSecretProvider.MAX_AGE_MS

            provider.getPrivateKeyForAddress("0x111", origin)

            assertEquals("越过 MAX_AGE 必须重新委托", 2, spy.callCount)
        }

    @Test
    fun `expired entry removal is scoped to its own key`() =
        runTest {
            var fakeNow = 0L
            val spy = CountingProvider()
            val provider =
                CachingSecretProvider(
                    spy,
                    clearDispatcher = StandardTestDispatcher(testScheduler),
                    now = { fakeNow }
                )

            provider.getPrivateKeyForAddress("0x111", origin)
            // 让 0x111 过期，同时写入一个新鲜的 0x222
            fakeNow += CachingSecretProvider.MAX_AGE_MS
            provider.getPrivateKeyForAddress("0x222", origin)
            assertEquals(2, spy.callCount)

            // 过期读会删掉 0x111 的条目并重新委托
            provider.getPrivateKeyForAddress("0x111", origin)
            assertEquals(3, spy.callCount)

            // 0x222 仍新鲜：过期删除不得连带清掉它
            provider.getPrivateKeyForAddress("0x222", origin)
            assertEquals("0x222 应仍命中缓存", 3, spy.callCount)
        }

    @Test
    fun `clearCache forces a refetch and invalidates the scheduled clear`() =
        runTest {
            var fakeNow = 0L
            val spy = CountingProvider()
            val provider =
                CachingSecretProvider(
                    spy,
                    clearDispatcher = StandardTestDispatcher(testScheduler),
                    now = { fakeNow }
                )

            provider.getPrivateKeyForAddress("0x111", origin)
            val epochBeforeClear = provider.currentEpochForTest()

            provider.clearCache()

            assertTrue(provider.currentEpochForTest() > epochBeforeClear)
            assertFalse(
                "clearCache 之后，之前排定的清空任务必须作废",
                provider.clearIfEpochUnchanged(epochBeforeClear)
            )

            provider.getPrivateKeyForAddress("0x111", origin)
            assertEquals(2, spy.callCount)
        }
}
