/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data

import android.content.Context
import com.aurora.store.util.Preferences
import com.aurora.store.util.Preferences.PREFERENCE_APP_LOCK_ENABLED
import com.aurora.store.util.Preferences.PREFERENCE_APP_LOCK_TIMEOUT
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the app-lock state at process scope. The unlocked flag lives only in memory, so a
 * fresh process (cold start or process death) always starts locked. Re-locking after the
 * app is backgrounded is governed by a short grace [timeout] to avoid re-prompting during
 * transient stops such as the system install dialog or the biometric sheet itself.
 */
@Singleton
class AppLockManager @Inject constructor() {

    private var unlocked = false
    private var backgroundedAt = 0L

    /**
     * Whether the app should currently present the lock screen, i.e. the feature is enabled
     * and the session is not (still) unlocked within the grace [timeout].
     *
     * This reads the state and the preference; the rule itself lives in [AppLockPolicy] so
     * it can be tested without a [Context] or a real grace period.
     */
    fun shouldLock(context: Context): Boolean = AppLockPolicy.shouldLock(
        enabled = Preferences.getBoolean(context, PREFERENCE_APP_LOCK_ENABLED, false),
        unlocked = unlocked,
        backgroundedAtMillis = backgroundedAt,
        timeoutMillis = timeoutMillis(context),
        nowMillis = System.currentTimeMillis()
    )

    /** Marks the current session as authenticated. */
    fun markUnlocked() {
        unlocked = true
        backgroundedAt = 0L
    }

    /** Records when the app went to the background so the grace timeout can be measured. */
    fun onBackgrounded() {
        if (unlocked) backgroundedAt = System.currentTimeMillis()
    }

    private fun timeoutMillis(context: Context): Long {
        val seconds = Preferences.getInteger(
            context,
            PREFERENCE_APP_LOCK_TIMEOUT,
            AppLockPolicy.DEFAULT_TIMEOUT_SECONDS
        )
        return AppLockPolicy.timeoutMillis(seconds)
    }
}
