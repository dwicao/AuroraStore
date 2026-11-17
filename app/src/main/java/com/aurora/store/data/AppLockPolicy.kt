/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data

/**
 * The app-lock decision, split out of [AppLockManager] so it can be exercised without a
 * [android.content.Context] and without waiting out a real grace period.
 *
 * [AppLockManager] owns the mutable session state and the preference reads; this object
 * owns the rule itself, including the boundary below — "the grace period has elapsed" —
 * which is the part that silently breaks if someone flips `>` and `>=`.
 */
object AppLockPolicy {

    /** Grace period before a backgrounded app re-locks, in seconds. */
    const val DEFAULT_TIMEOUT_SECONDS: Int = 30

    /** Converts the preference (seconds) into the unit the rule compares against. */
    fun timeoutMillis(seconds: Int): Long = seconds.coerceAtLeast(0) * 1000L

    /**
     * Whether the lock screen must be shown right now.
     *
     * @param enabled the feature toggle; when off the app never locks.
     * @param unlocked whether this process has been authenticated at least once.
     * @param backgroundedAtMillis when the app left the foreground, or `0` while it never
     *   did — an app that was only paused (dialog, biometric sheet) must not re-lock.
     * @param timeoutMillis grace period; re-lock once at least this much time passed.
     * @param nowMillis current wall clock, injected so the rule stays testable.
     */
    fun shouldLock(
        enabled: Boolean,
        unlocked: Boolean,
        backgroundedAtMillis: Long,
        timeoutMillis: Long,
        nowMillis: Long
    ): Boolean {
        if (!enabled) return false
        if (!unlocked) return true
        if (backgroundedAtMillis == 0L) return false
        return nowMillis - backgroundedAtMillis >= timeoutMillis
    }
}
