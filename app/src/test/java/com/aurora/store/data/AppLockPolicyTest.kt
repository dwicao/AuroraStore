/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLockPolicyTest {

    private val timeout = AppLockPolicy.timeoutMillis(AppLockPolicy.DEFAULT_TIMEOUT_SECONDS)
    private val backgroundedAt = 1_000_000L

    private fun shouldLock(
        enabled: Boolean = true,
        unlocked: Boolean = true,
        backgroundedAtMillis: Long = backgroundedAt,
        nowMillis: Long = backgroundedAt
    ): Boolean = AppLockPolicy.shouldLock(
        enabled = enabled,
        unlocked = unlocked,
        backgroundedAtMillis = backgroundedAtMillis,
        timeoutMillis = timeout,
        nowMillis = nowMillis
    )

    @Test
    fun disabled_neverLocks() {
        assertThat(shouldLock(enabled = false, unlocked = false)).isFalse()
    }

    @Test
    fun neverAuthenticated_locksImmediately() {
        assertThat(shouldLock(unlocked = false)).isTrue()
    }

    @Test
    fun foregroundSession_doesNotLock() {
        assertThat(shouldLock(backgroundedAtMillis = 0L)).isFalse()
    }

    @Test
    fun withinGracePeriod_doesNotLock() {
        assertThat(shouldLock(nowMillis = backgroundedAt + timeout - 1)).isFalse()
    }

    @Test
    fun exactlyAtGracePeriod_locks() {
        assertThat(shouldLock(nowMillis = backgroundedAt + timeout)).isTrue()
    }

    @Test
    fun pastGracePeriod_locks() {
        assertThat(shouldLock(nowMillis = backgroundedAt + timeout + 60_000L)).isTrue()
    }

    @Test
    fun negativeTimeout_isTreatedAsZero() {
        assertThat(AppLockPolicy.timeoutMillis(-5)).isEqualTo(0L)
    }

    @Test
    fun defaultTimeout_isThirtySeconds() {
        assertThat(AppLockPolicy.timeoutMillis(AppLockPolicy.DEFAULT_TIMEOUT_SECONDS))
            .isEqualTo(30_000L)
    }
}
