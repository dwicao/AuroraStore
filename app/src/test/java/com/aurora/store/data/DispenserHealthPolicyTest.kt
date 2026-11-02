/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DispenserHealthPolicyTest {

    private val now = 1_000_000_000L
    private val urls = setOf("https://one.example/auth", "https://two.example/auth")

    @Test
    fun emptySet_picksNull() {
        assertThat(DispenserHealthPolicy.pick(emptySet(), emptyMap(), now)).isNull()
    }

    @Test
    fun noHealth_picksOneOfTheUrls() {
        assertThat(DispenserHealthPolicy.pick(urls, emptyMap(), now)).isIn(urls)
    }

    @Test
    fun recentlyFailed_isSkippedWhileOtherIsEligible() {
        val health = mapOf(
            "https://one.example/auth" to DispenserHealth(failures = 1, lastFailureAt = now)
        )
        assertThat(DispenserHealthPolicy.pick(urls, health, now))
            .isEqualTo("https://two.example/auth")
    }

    @Test
    fun cooldownExpires_oldFailureIsEligibleAgain() {
        val health = mapOf(
            "https://one.example/auth" to DispenserHealth(
                failures = 1,
                lastFailureAt = now - DispenserHealthPolicy.cooldownMs(1) - 1_000L
            )
        )
        assertThat(DispenserHealthPolicy.pick(urls, health, now)).isIn(urls)
    }

    @Test
    fun fewestFailuresWins() {
        val health = mapOf(
            "https://one.example/auth" to DispenserHealth(
                failures = 3,
                lastFailureAt = now - DispenserHealthPolicy.cooldownMs(3) - 1_000L
            ),
            "https://two.example/auth" to DispenserHealth(failures = 1, lastFailureAt = 0L)
        )
        assertThat(DispenserHealthPolicy.pick(urls, health, now))
            .isEqualTo("https://two.example/auth")
    }

    @Test
    fun allCoolingDown_stillPicksLeastRecentlyFailed() {
        val health = mapOf(
            "https://one.example/auth" to DispenserHealth(failures = 2, lastFailureAt = now),
            "https://two.example/auth" to DispenserHealth(
                failures = 5,
                lastFailureAt = now - 1_000L
            )
        )
        assertThat(DispenserHealthPolicy.pick(urls, health, now))
            .isEqualTo("https://two.example/auth")
    }

    @Test
    fun cooldown_growsExponentiallyAndCaps() {
        assertThat(DispenserHealthPolicy.cooldownMs(0)).isEqualTo(0L)
        assertThat(DispenserHealthPolicy.cooldownMs(1)).isEqualTo(5 * 60 * 1000L)
        assertThat(DispenserHealthPolicy.cooldownMs(2)).isEqualTo(10 * 60 * 1000L)
        assertThat(DispenserHealthPolicy.cooldownMs(3)).isEqualTo(20 * 60 * 1000L)
        assertThat(DispenserHealthPolicy.cooldownMs(100)).isEqualTo(12 * 60 * 60 * 1000L)
    }
}
