/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.play

import android.util.Log
import com.aurora.extensions.TAG
import com.aurora.store.AuroraApp
import com.aurora.store.data.event.AuthEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * In-memory counter of [PlayError]s per call site. Its only job is early warning:
 * [PlayError.ProtocolChanged] and [PlayError.Unknown] should be rare, so when they
 * start spiking it almost certainly means Google changed something upstream, and that
 * shows up in logcat long before a full breakage report comes in.
 */
object PlayErrorMonitor {

    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    fun record(label: String, error: PlayError) {
        if (error !is PlayError.ProtocolChanged && error !is PlayError.Unknown) return
        val count = counters
            .computeIfAbsent("$label:${error::class.simpleName}") { AtomicInteger() }
            .incrementAndGet()
        // Log every 10th so a flood stays visible without spamming per failure.
        if (count % 10 == 1) {
            Log.w(
                TAG,
                "[$label] $count unexpected Play API failures ($error) — " +
                    "possible upstream API change, please report"
            )
        }
    }
}

/**
 * Single choke point for Play API calls. Maps every failure onto [PlayError], emits
 * [AuthEvent.SessionExpired] for the auth family, feeds [PlayErrorMonitor], and hands
 * the original exception back inside [Result] so callers keep their error-message UX.
 *
 * New Play API call sites should go through here instead of catching
 * `PlayException.Unauthorized` ad hoc; the existing per-ViewModel handlers keep working
 * untouched.
 */
@Singleton
class PlayGateway @Inject constructor() {

    /**
     * Runs [block], returning its value. A session rejection also emits
     * [AuthEvent.SessionExpired] (with [expiredPackage]) so the app re-validates via
     * Splash exactly like the hand-rolled handlers do. Coroutine cancellation is never
     * swallowed.
     */
    suspend fun <T> call(
        label: String,
        expiredPackage: String? = null,
        block: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            val error = PlayErrors.of(throwable)
            PlayErrorMonitor.record(label, error)
            when (error) {
                is PlayError.AuthExpired, is PlayError.NeedsAccount -> {
                    Log.w(TAG, "[$label] session rejected ($error), redirecting to Splash")
                    AuroraApp.events.send(AuthEvent.SessionExpired(expiredPackage))
                }

                is PlayError.ProtocolChanged, is PlayError.Unknown -> {
                    Log.e(TAG, "[$label] unexpected Play API failure ($error)", throwable)
                }

                else -> {
                    Log.i(TAG, "[$label] Play call failed ($error)")
                }
            }
            Result.failure(throwable)
        }
    }

    /** [call] that yields `null` on any failure. For best-effort reads. */
    suspend fun <T> callOrNull(
        label: String,
        expiredPackage: String? = null,
        block: suspend () -> T
    ): T? = call(label, expiredPackage, block).getOrNull()

    /** [call] that yields [default] on any failure. For reads with a safe fallback. */
    suspend fun <T> callOrDefault(
        label: String,
        default: T,
        expiredPackage: String? = null,
        block: suspend () -> T
    ): T = call(label, expiredPackage, block).getOrDefault(default)
}
