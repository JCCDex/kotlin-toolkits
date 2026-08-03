package com.jccdex.toolkits.vault

/**
 * Thrown when [VaultRepository.unlock] / [VaultRepository.verifyPassword] are blocked
 * after too many failed attempts (M-01).
 */
class VaultAuthLockedException(
    val remainingMs: Long
) : IllegalStateException(
        "Too many failed attempts. Try again in ${(remainingMs + 999) / 1000}s"
    )

/**
 * Persistent failed-attempt / time-lock state for vault password auth (M-01).
 *
 * After [MAX_FAILURES] consecutive failures, auth is locked for an escalating duration
 * (1 min → 5 min → 15 min). Success clears the counter and escalation level.
 */
internal class VaultAuthLockout(
    private val prefs: android.content.SharedPreferences,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun remainingMs(): Long {
        val until = prefs.getLong(KEY_LOCK_UNTIL, 0L)
        if (until <= 0L) return 0L
        return (until - clock()).coerceAtLeast(0L)
    }

    fun isLocked(): Boolean = remainingMs() > 0L

    fun ensureNotLocked() {
        val remaining = remainingMs()
        if (remaining > 0L) {
            throw VaultAuthLockedException(remaining)
        }
    }

    /** @return true if this failure triggered (or extended) a lockout window. */
    fun recordFailure(): Boolean {
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        if (failures < MAX_FAILURES) {
            prefs.edit().putInt(KEY_FAILURES, failures).apply()
            return false
        }
        val level = prefs.getInt(KEY_LEVEL, 0)
        val durationMs =
            when (level) {
                0 -> LOCK_MS_LEVEL_0
                1 -> LOCK_MS_LEVEL_1
                else -> LOCK_MS_LEVEL_2
            }
        prefs
            .edit()
            .putInt(KEY_FAILURES, 0)
            .putInt(KEY_LEVEL, (level + 1).coerceAtMost(2))
            .putLong(KEY_LOCK_UNTIL, clock() + durationMs)
            .apply()
        return true
    }

    fun recordSuccess() {
        prefs.edit().clear().apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "vault_auth_lockout"
        const val MAX_FAILURES = 5
        const val LOCK_MS_LEVEL_0 = 60_000L
        const val LOCK_MS_LEVEL_1 = 300_000L
        const val LOCK_MS_LEVEL_2 = 900_000L

        private const val KEY_FAILURES = "failures"
        private const val KEY_LEVEL = "level"
        private const val KEY_LOCK_UNTIL = "lock_until"

        fun create(
            context: android.content.Context,
            clock: () -> Long = { System.currentTimeMillis() }
        ): VaultAuthLockout {
            val prefs =
                context.applicationContext.getSharedPreferences(
                    PREFS_NAME,
                    android.content.Context.MODE_PRIVATE
                )
            return VaultAuthLockout(prefs = prefs, clock = clock)
        }
    }
}
