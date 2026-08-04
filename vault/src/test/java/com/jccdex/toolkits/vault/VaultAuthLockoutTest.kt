package com.jccdex.toolkits.vault

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VaultAuthLockoutTest {
    private lateinit var lockout: VaultAuthLockout
    private var now = 1_000_000L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(VaultAuthLockout.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        now = 1_000_000L
        lockout = VaultAuthLockout.create(context) { now }
    }

    @Test
    fun locksAfterMaxFailures() {
        repeat(VaultAuthLockout.MAX_FAILURES - 1) {
            assertThat(lockout.recordFailure()).isFalse()
            assertThat(lockout.isLocked()).isFalse()
        }
        assertThat(lockout.recordFailure()).isTrue()
        assertThat(lockout.isLocked()).isTrue()
        assertThat(lockout.remainingMs()).isEqualTo(VaultAuthLockout.LOCK_MS_LEVEL_0)
        assertFailsWith<VaultAuthLockedException> { lockout.ensureNotLocked() }
    }

    @Test
    fun successClearsFailuresAndLevel() {
        repeat(VaultAuthLockout.MAX_FAILURES) { lockout.recordFailure() }
        assertThat(lockout.isLocked()).isTrue()
        now += VaultAuthLockout.LOCK_MS_LEVEL_0 + 1
        lockout.recordSuccess()
        assertThat(lockout.isLocked()).isFalse()
        assertThat(lockout.remainingMs()).isEqualTo(0L)
        // Next lockout should start again at level 0 duration.
        repeat(VaultAuthLockout.MAX_FAILURES) { lockout.recordFailure() }
        assertThat(lockout.remainingMs()).isEqualTo(VaultAuthLockout.LOCK_MS_LEVEL_0)
    }

    @Test
    fun lockDurationEscalates() {
        repeat(VaultAuthLockout.MAX_FAILURES) { lockout.recordFailure() }
        assertThat(lockout.remainingMs()).isEqualTo(VaultAuthLockout.LOCK_MS_LEVEL_0)
        now += VaultAuthLockout.LOCK_MS_LEVEL_0 + 1
        repeat(VaultAuthLockout.MAX_FAILURES) { lockout.recordFailure() }
        assertThat(lockout.remainingMs()).isEqualTo(VaultAuthLockout.LOCK_MS_LEVEL_1)
        now += VaultAuthLockout.LOCK_MS_LEVEL_1 + 1
        repeat(VaultAuthLockout.MAX_FAILURES) { lockout.recordFailure() }
        assertThat(lockout.remainingMs()).isEqualTo(VaultAuthLockout.LOCK_MS_LEVEL_2)
    }
}
