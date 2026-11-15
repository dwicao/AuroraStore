/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers [CommonUtil.parseProxyUrl]: the proxy setting is a single URL field, and the fork's
 * networking stack derives its [com.aurora.store.data.model.ProxyInfo] from it — a broken parser
 * silently drops the user's proxy on every app start.
 */
class ProxyUrlParserTest {

    @Test
    fun `http proxy with credentials`() {
        val info = CommonUtil.parseProxyUrl("http://user:pass@192.168.1.10:8080")

        assertThat(info).isNotNull()
        assertThat(info!!.protocol).isEqualTo("HTTP")
        assertThat(info.host).isEqualTo("192.168.1.10")
        assertThat(info.port).isEqualTo(8080)
        assertThat(info.proxyUser).isEqualTo("user")
        assertThat(info.proxyPassword).isEqualTo("pass")
    }

    @Test
    fun `https proxy with credentials`() {
        val info = CommonUtil.parseProxyUrl("https://user:pass@proxy.example.com:443")

        assertThat(info).isNotNull()
        assertThat(info!!.protocol).isEqualTo("HTTPS")
        assertThat(info.host).isEqualTo("proxy.example.com")
        assertThat(info.port).isEqualTo(443)
    }

    @Test
    fun `proxy without credentials`() {
        val info = CommonUtil.parseProxyUrl("http://192.168.1.10:8080")

        assertThat(info).isNotNull()
        assertThat(info!!.proxyUser).isEmpty()
        assertThat(info.proxyPassword).isEmpty()
    }

    @Test
    fun `socks5 proxy with credentials`() {
        val info = CommonUtil.parseProxyUrl("socks5://user:pass@10.0.0.2:1080")

        assertThat(info).isNotNull()
        assertThat(info!!.protocol).isEqualTo("SOCKS5")
        assertThat(info.host).isEqualTo("10.0.0.2")
        assertThat(info.port).isEqualTo(1080)
        assertThat(info.proxyUser).isEqualTo("user")
        assertThat(info.proxyPassword).isEqualTo("pass")
    }

    @Test
    fun `rejects missing port`() {
        assertThat(CommonUtil.parseProxyUrl("http://192.168.1.10")).isNull()
    }

    @Test
    fun `rejects missing host`() {
        assertThat(CommonUtil.parseProxyUrl("http://:8080")).isNull()
    }

    @Test
    fun `rejects unknown scheme`() {
        assertThat(CommonUtil.parseProxyUrl("ftp://192.168.1.10:21")).isNull()
    }

    @Test
    fun `rejects blank input`() {
        assertThat(CommonUtil.parseProxyUrl("")).isNull()
        assertThat(CommonUtil.parseProxyUrl("   ")).isNull()
    }

    @Test
    fun `rejects credentials with spaces`() {
        assertThat(CommonUtil.parseProxyUrl("http://us er:pass@192.168.1.10:8080")).isNull()
    }

    @Test
    fun `rejects out of range port`() {
        assertThat(CommonUtil.parseProxyUrl("http://192.168.1.10:99999")).isNull()
    }
}
