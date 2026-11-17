/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.room.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The "ignore this update" rule gates the update list, the notification and auto-install, so
 * a regression here resurfaces updates the user explicitly muted — on one path only, which
 * is the kind of bug nobody notices until a phone installs something at night.
 */
class IgnoredUpdateTest {

    private val packageName = "com.example.app"

    private fun update(versionCode: Long) = Update(
        packageName = packageName,
        versionCode = versionCode,
        versionName = "1.0.$versionCode",
        displayName = "Example",
        iconURL = "",
        changelog = "",
        id = 0,
        developerName = "Example",
        size = 0L,
        updatedOn = "",
        hasValidCert = true,
        offerType = 0,
        fileList = emptyList(),
        sharedLibs = emptyList()
    )

    @Test
    fun ignoreAll_hidesEveryVersion() {
        val rule = IgnoredUpdate(packageName, ignoredVersionCode = null)
        assertThat(rule.hides(update(10L))).isTrue()
        assertThat(rule.hides(update(11L))).isTrue()
    }

    @Test
    fun versionRule_hidesOnlyThatVersion() {
        val rule = IgnoredUpdate(packageName, ignoredVersionCode = 10L)
        assertThat(rule.hides(update(10L))).isTrue()
        assertThat(rule.hides(update(11L))).isFalse()
    }

    @Test
    fun noRule_neverHides() {
        assertThat(update(10L).isIgnoredBy(emptyMap())).isFalse()
    }

    @Test
    fun ruleForAnotherPackage_isNotApplied() {
        val rules = mapOf("com.other.app" to IgnoredUpdate("com.other.app", null))
        assertThat(update(10L).isIgnoredBy(rules)).isFalse()
    }

    @Test
    fun ignoreAll_isAppliedThroughTheMap() {
        val rules = mapOf(packageName to IgnoredUpdate(packageName, null))
        assertThat(update(10L).isIgnoredBy(rules)).isTrue()
    }

    @Test
    fun versionRule_leavesNewerVersionVisibleThroughTheMap() {
        val rules = mapOf(packageName to IgnoredUpdate(packageName, 10L))
        assertThat(update(10L).isIgnoredBy(rules)).isTrue()
        assertThat(update(12L).isIgnoredBy(rules)).isFalse()
    }
}
