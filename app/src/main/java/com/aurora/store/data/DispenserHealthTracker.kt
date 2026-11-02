/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data

import android.content.Context
import android.util.Log
import com.aurora.extensions.TAG
import com.aurora.store.util.Preferences
import com.aurora.store.util.Preferences.PREFERENCE_DISPENSER_HEALTH
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/** Consecutive POST failures, and when the dispenser last failed/succeeded. */
data class DispenserHealth(
    val failures: Int = 0,
    val lastFailureAt: Long = 0L,
    val lastSuccessAt: Long = 0L
)

/**
 * Pure picking policy: no Android dependencies, covered by JVM unit tests.
 *
 * Dispensers that failed recently are skipped with exponential backoff, the least-failed
 * ones are preferred, and ties break randomly so load spreads. If *every* dispenser is
 * cooling down the least-recently-failed one is still returned — a possibly-stale
 * dispenser is better than refusing to try at all.
 */
object DispenserHealthPolicy {

    private const val BASE_COOLDOWN_MS = 5 * 60 * 1000L
    private const val MAX_COOLDOWN_MS = 12 * 60 * 60 * 1000L

    fun cooldownMs(failures: Int): Long {
        if (failures <= 0) return 0L
        // 5m, 10m, 20m, … capped at 12h. Shift is guarded: Int shl is masked to 5 bits,
        // so clamp the exponent first instead of relying on the shift alone.
        val shift = minOf(failures - 1, 20)
        return minOf(BASE_COOLDOWN_MS shl shift, MAX_COOLDOWN_MS)
    }

    fun pick(
        urls: Collection<String>,
        health: Map<String, DispenserHealth>,
        now: Long
    ): String? {
        if (urls.isEmpty()) return null
        val eligible = urls.filter { url ->
            val state = health[url] ?: return@filter true
            state.failures <= 0 || now - state.lastFailureAt >= cooldownMs(state.failures)
        }
        if (eligible.isEmpty()) {
            // All cooling down: the one that failed longest ago most likely recovered.
            return urls.minByOrNull { health[it]?.lastFailureAt ?: 0L }
        }
        val minFailures = eligible.minOf { health[it]?.failures ?: 0 }
        val best = eligible.filter { (health[it]?.failures ?: 0) == minFailures }
        return best.random()
    }
}

/**
 * Persists per-dispenser POST outcomes and picks the healthiest URL for the next
 * anonymous mint. Health only ever reflects the dispenser HTTP round-trip — a failure
 * later, while minting the Play session from a good response, never blames the
 * dispenser.
 */
@Singleton
class DispenserHealthTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun pickDispenser(urls: Set<String>): String? =
        DispenserHealthPolicy.pick(urls, snapshot(), System.currentTimeMillis())

    fun recordSuccess(url: String) {
        if (update(url) { copy(failures = 0, lastSuccessAt = System.currentTimeMillis()) }) {
            Log.i(TAG, "Dispenser healthy again: $url")
        }
    }

    fun recordFailure(url: String) {
        val now = System.currentTimeMillis()
        update(url) { copy(failures = failures + 1, lastFailureAt = now) }
        Log.w(TAG, "Dispenser failed, backing off: $url")
    }

    fun clear(url: String) {
        val current = snapshot().toMutableMap()
        if (current.remove(url) != null) persist(current)
    }

    fun snapshot(): Map<String, DispenserHealth> {
        val raw = Preferences.getString(context, PREFERENCE_DISPENSER_HEALTH, "")
        if (raw.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            buildMap {
                for (key in json.keys()) {
                    val entry = json.optJSONObject(key) ?: continue
                    put(
                        key,
                        DispenserHealth(
                            failures = entry.optInt("f"),
                            lastFailureAt = entry.optLong("lf"),
                            lastSuccessAt = entry.optLong("ls")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Applies [transform] to [url]'s entry; returns true when it changed anything. */
    private fun update(url: String, transform: DispenserHealth.() -> DispenserHealth): Boolean {
        val current = snapshot().toMutableMap()
        val before = current[url] ?: DispenserHealth()
        val after = before.transform()
        if (after == before) return false
        current[url] = after
        persist(current)
        return true
    }

    private fun persist(health: Map<String, DispenserHealth>) {
        val json = JSONObject()
        for ((url, state) in health) {
            json.put(
                url,
                JSONObject()
                    .put("f", state.failures)
                    .put("lf", state.lastFailureAt)
                    .put("ls", state.lastSuccessAt)
            )
        }
        Preferences.putString(context, PREFERENCE_DISPENSER_HEALTH, json.toString())
    }
}
