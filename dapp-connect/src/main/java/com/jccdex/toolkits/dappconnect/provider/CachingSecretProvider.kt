package com.jccdex.toolkits.dappconnect.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val delegate: SecretProvider
) : SecretProvider {

    companion object {
        private const val BRIDGE_MS = 5_000L
        private const val MAX_AGE_MS = 20_000L
        private const val PRIVATE_KEY_PREFIX = "pk:"
        private const val SECRET_PREFIX = "sec:"
    }

    private data class Entry(val value: String, val at: Long)

    private val cache = mutableMapOf<String, Entry>()
    private var activeOps = 0
    private var clearJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val privateKeyMutex = Mutex()
    private val secretMutex = Mutex()

    @Synchronized
    private fun beginOp() {
        activeOps++
        clearJob?.cancel()
        clearJob = null
    }

    @Synchronized
    private fun endOp() {
        activeOps = (activeOps - 1).coerceAtLeast(0)
        if (activeOps == 0) {
            clearJob?.cancel()
            clearJob = scope.launch { delay(BRIDGE_MS); cache.clear() }
        }
    }

    private fun cached(key: String): String? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.at >= MAX_AGE_MS) {
            cache.remove(key)
            return null
        }
        return entry.value
    }

    /** Force-clear the cache (call on lifecycle stop / account switch). */
    @Synchronized
    fun clearCache() {
        cache.clear()
        clearJob?.cancel()
        clearJob = null
    }

    override suspend fun getPrivateKeyForAddress(address: String, origin: String): String? {
        val cacheKey = "$PRIVATE_KEY_PREFIX$origin|$address"
        return privateKeyMutex.withLock {
            cached(cacheKey)?.let { return it }
            beginOp()
            try {
                cached(cacheKey)?.let { return it }
                delegate.getPrivateKeyForAddress(address, origin)?.also {
                    cache[cacheKey] = Entry(it, System.currentTimeMillis())
                }
            } finally {
                endOp()
            }
        }
    }

    override suspend fun getSecretForAddress(address: String, origin: String): String? {
        val cacheKey = "$SECRET_PREFIX$origin|$address"
        return secretMutex.withLock {
            cached(cacheKey)?.let { return it }
            beginOp()
            try {
                cached(cacheKey)?.let { return it }
                delegate.getSecretForAddress(address, origin)?.also {
                    cache[cacheKey] = Entry(it, System.currentTimeMillis())
                }
            } finally {
                endOp()
            }
        }
    }
}
