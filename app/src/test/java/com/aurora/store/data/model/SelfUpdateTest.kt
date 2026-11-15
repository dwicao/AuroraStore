/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Covers the fork's self-update feed parsing (`updates.json`): the producer encodes numeric
 * fields as JSON strings, and a restore of a stale/partial feed must degrade to 0 instead of
 * crashing the update check.
 */
class SelfUpdateTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesForkFeedEntry() {
        val raw = """
            {
              "version_name": "4.8.17",
              "version_code": "82",
              "download_url": "https://github.com/dwicao/AuroraStore/releases/download/v4.8.17/app-vanilla-release.apk",
              "icon_url": "",
              "sha1": "",
              "sha256": "a2265596f11564946e3905b3322513c0b186d9da2fc21af2360295a6ee777671",
              "changelog": "**Full Changelog**",
              "size": "10061578",
              "last_commit": "",
              "updated_on": "",
              "timestamp": "0"
            }
        """.trimIndent()

        val update = json.decodeFromString<SelfUpdate>(raw)

        assertThat(update.versionName).isEqualTo("4.8.17")
        assertThat(update.versionCode).isEqualTo(82L)
        assertThat(update.size).isEqualTo(10_061_578L)
        assertThat(update.timestamp).isEqualTo(0L)
        assertThat(update.sha256).isEqualTo(
            "a2265596f11564946e3905b3322513c0b186d9da2fc21af2360295a6ee777671"
        )
        assertThat(update.downloadUrl).endsWith("v4.8.17/app-vanilla-release.apk")
    }

    @Test
    fun `missing numeric fields default to zero`() {
        val update = json.decodeFromString<SelfUpdate>("""{}""")

        assertThat(update.versionName).isEmpty()
        assertThat(update.versionCode).isEqualTo(0L)
        assertThat(update.size).isEqualTo(0L)
        assertThat(update.timestamp).isEqualTo(0L)
    }

    @Test
    fun `blank and non-numeric strings fall back to zero`() {
        val raw = """
            {
              "version_name": "4.8.17",
              "version_code": "",
              "size": "huge",
              "timestamp": "not-a-number"
            }
        """.trimIndent()

        val update = json.decodeFromString<SelfUpdate>(raw)

        assertThat(update.versionCode).isEqualTo(0L)
        assertThat(update.size).isEqualTo(0L)
        assertThat(update.timestamp).isEqualTo(0L)
    }

    @Test
    fun `numeric strings with huge values are preserved`() {
        val raw = """
            {
              "version_name": "4.9.0",
              "version_code": "2147483647",
              "size": "9223372036854775000",
              "timestamp": "1760000000000"
            }
        """.trimIndent()

        val update = json.decodeFromString<SelfUpdate>(raw)

        assertThat(update.versionCode).isEqualTo(2_147_483_647L)
        assertThat(update.size).isEqualTo(9_223_372_036_854_775_000L)
        assertThat(update.timestamp).isEqualTo(1_760_000_000_000L)
    }

    @Test
    fun `unknown keys are ignored so an extended feed keeps parsing`() {
        val raw = """
            {
              "version_name": "4.8.17",
              "version_code": "82",
              "some_future_field": { "nested": [1, 2, 3] }
            }
        """.trimIndent()

        val update = json.decodeFromString<SelfUpdate>(raw)

        assertThat(update.versionCode).isEqualTo(82L)
    }
}
