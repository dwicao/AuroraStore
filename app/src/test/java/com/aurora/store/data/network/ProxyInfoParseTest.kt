/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.network

import com.aurora.store.data.model.ProxyInfo
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Coverage for [OkHttpClientModule.parseProxyInfo]: blank/`{}` payloads and malformed JSON from a
 * stale restore must mean "no proxy", never a crashed networking stack at startup.
 */
class ProxyInfoParseTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `blank payload disables the proxy`() {
        assertThat(OkHttpClientModule.parseProxyInfo("", json)).isNull()
        assertThat(OkHttpClientModule.parseProxyInfo("   ", json)).isNull()
    }

    @Test
    fun `empty object disables the proxy`() {
        assertThat(OkHttpClientModule.parseProxyInfo("{}", json)).isNull()
    }

    @Test
    fun `malformed json disables the proxy instead of throwing`() {
        assertThat(OkHttpClientModule.parseProxyInfo("{not json", json)).isNull()
        assertThat(OkHttpClientModule.parseProxyInfo("""{"host": 42}""", json)).isNull()
        assertThat(OkHttpClientModule.parseProxyInfo("[]", json)).isNull()
    }

    @Test
    fun `valid payload round-trips`() {
        val raw = json.encodeToString(
            ProxyInfo.serializer(),
            ProxyInfo(
                protocol = "HTTP",
                host = "192.168.1.10",
                port = 8080,
                proxyUser = "user",
                proxyPassword = "pass"
            )
        )

        val info = OkHttpClientModule.parseProxyInfo(raw, json)

        assertThat(info).isNotNull()
        assertThat(info!!.protocol).isEqualTo("HTTP")
        assertThat(info.host).isEqualTo("192.168.1.10")
        assertThat(info.port).isEqualTo(8080)
        assertThat(info.proxyUser).isEqualTo("user")
        assertThat(info.proxyPassword).isEqualTo("pass")
    }

    @Test
    fun `payload with unknown keys still parses`() {
        val raw = """
            {"protocol":"SOCKS5","host":"10.0.0.2","port":1080,
             "proxyUser":null,"proxyPassword":null,"future_field":"x"}
        """.trimIndent()

        val info = OkHttpClientModule.parseProxyInfo(raw, json)

        assertThat(info).isNotNull()
        assertThat(info!!.protocol).isEqualTo("SOCKS5")
        assertThat(info.port).isEqualTo(1080)
    }
}
