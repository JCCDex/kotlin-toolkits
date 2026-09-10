package com.jccdex.toolkits.dappconnect.provider

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Wraps a [SecretProvider] with batch-scoped result caching.
 *
 * The first call to getPrivateKeyForAddress / getSecretForAddress for a given
 * address delegates to the underlying provider (e.g. prompting for password).
 * Subsequent calls for the same address within the bridge window reuse the
 * cached result without prompting. This collapses multi-step DApp flows
 * (e.g. ipfs_getPublicKey followed by ipfs_personalSign, or did_issueCredential)
 * into a single password prompt.
 *
 * Cache is cleared when:
 * - [BRIDGE_MS] pass after the last in-flight operation ends (bridge window)
 * - Absolute TTL of [MAX_AGE_MS] from the initial cache entry
 * - Client calls [clearCache] (lifecycle stop / account switch)
 */
class CachingSecretProvider(
    private val delegate: SecretProvider,
    /**
     * 延迟清空协程所用的调度器。生产用 [Dispatchers.Default]；
     * 测试可注入 TestDispatcher，用虚拟时间精确驱动"清理任务到点"这一时刻。
     */
    clearDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * 时间源。生产用 [System.currentTimeMillis]；测试可注入假时钟，
     * 在不推进虚拟时间（即不触发窗口清空）的前提下精确验证 [MAX_AGE_MS] 过期语义。
     */
    private val now: () -> Long = System::currentTimeMillis
) : SecretProvider {
    /**
     * v0.3.x 的单参构造重载：给主构造加参数会改变它的 JVM 签名，
     * 已经针对旧签名编译过的调用方（预编译 AAR/字节码）需要这个重载才能链接。
     */
    constructor(delegate: SecretProvider) : this(delegate, Dispatchers.Default, System::currentTimeMillis)

    companion object {
        internal const val BRIDGE_MS = 5_000L
        internal const val MAX_AGE_MS = 20_000L
        private const val PRIVATE_KEY_PREFIX = "pk:"
        private const val SECRET_PREFIX = "sec:"
    }

    private data class Entry(val value: String, val at: Long)

    // M-5: shared cache is read/written from the pk/secant Mutex paths and from the
    // delayed-clear coroutine on Dispatchers.Default, while begin/endOp use the
    // intrinsic lock — a plain mutableMapOf has no happens-before across those
    // threads. ConcurrentHashMap makes every cache access thread-safe.
    private val cache = ConcurrentHashMap<String, Entry>()
    private var activeOps = 0
    private var clearJob: Job? = null

    // M-5: invalidates a pending delayed clear when a new op starts or the cache
    // is force-cleared, so a stale clearer cannot wipe entries written afterwards.
    @Volatile
    private var cacheEpoch = 0L
    private val scope = CoroutineScope(SupervisorJob() + clearDispatcher)
    private val privateKeyMutex = Mutex()
    private val secretMutex = Mutex()

    @Synchronized
    private fun beginOp() {
        activeOps++
        cacheEpoch++
        clearJob?.cancel()
        clearJob = null
    }

    @Synchronized
    private fun endOp() {
        activeOps = (activeOps - 1).coerceAtLeast(0)
        if (activeOps == 0) {
            clearJob?.cancel()
            val scheduledEpoch = cacheEpoch
            clearJob =
                scope.launch {
                    delay(BRIDGE_MS)
                    clearIfEpochUnchanged(scheduledEpoch)
                }
        }
    }

    /**
     * 延迟清空的落点：只有在排定之后没有新操作 / 强制清空（epoch 未变）时才真正清空，
     * 避免**已经开始执行**的陈旧清理任务抹掉之后写入的条目（M-5）。
     *
     * 注意：`delay` 之后的取消通常已能拦住尚未恢复的任务，因此这个判断真正起作用的是
     * "清理体已进入执行、同时另一线程 bump 了 epoch"这一并发窗口 —— 抽成独立函数后
     * 该判断本身可以被直接单测（见 CachingSecretProviderCacheLifetimeTest）。
     *
     * @return 是否真的清空了缓存
     */
    internal fun clearIfEpochUnchanged(scheduledEpoch: Long): Boolean {
        if (scheduledEpoch != cacheEpoch) return false
        cache.clear()
        return true
    }

    /** 测试用：当前 epoch（每次 beginOp / clearCache 都会自增）。 */
    internal fun currentEpochForTest(): Long = cacheEpoch

    private fun cached(key: String): String? {
        val entry = cache[key] ?: return null
        if (now() - entry.at >= MAX_AGE_MS) {
            // Remove only the expired entry — never a newer entry written concurrently.
            cache.remove(key, entry)
            return null
        }
        return entry.value
    }

    /** Force-clear the cache (call on lifecycle stop / account switch). */
    @Synchronized
    fun clearCache() {
        cacheEpoch++
        cache.clear()
        clearJob?.cancel()
        clearJob = null
    }

    override suspend fun getPrivateKeyForAddress(
        address: String,
        origin: String
    ): String? {
        val cacheKey = "$PRIVATE_KEY_PREFIX$origin|$address"
        return privateKeyMutex.withLock {
            cached(cacheKey)?.let { return it }
            beginOp()
            try {
                cached(cacheKey)?.let { return it }
                delegate.getPrivateKeyForAddress(address, origin)?.also {
                    cache[cacheKey] = Entry(it, now())
                }
            } finally {
                endOp()
            }
        }
    }

    override suspend fun getSecretForAddress(
        address: String,
        origin: String
    ): String? {
        val cacheKey = "$SECRET_PREFIX$origin|$address"
        return secretMutex.withLock {
            cached(cacheKey)?.let { return it }
            beginOp()
            try {
                cached(cacheKey)?.let { return it }
                delegate.getSecretForAddress(address, origin)?.also {
                    cache[cacheKey] = Entry(it, now())
                }
            } finally {
                endOp()
            }
        }
    }
}
