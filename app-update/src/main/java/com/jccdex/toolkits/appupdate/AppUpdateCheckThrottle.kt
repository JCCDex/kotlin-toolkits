package com.jccdex.toolkits.appupdate

object AppUpdateCheckThrottle {
    const val DEFAULT_INTERVAL_MS = 24L * 60L * 60L * 1000L

    fun shouldCheck(
        nowMs: Long,
        lastCheckMs: Long,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        force: Boolean
    ): Boolean {
        if (force) return true
        if (lastCheckMs <= 0L) return true
        return nowMs - lastCheckMs >= intervalMs
    }
}
