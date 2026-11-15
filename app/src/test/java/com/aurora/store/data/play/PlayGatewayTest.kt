/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.play

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Framework-independent coverage for [PlayGateway]: success pass-through, cancellation
 * discipline and the failure path. The auth-redirect behaviour needs a live Play session to
 * construct gplayapi's Unauthorized exception and is covered by the nightly live probes instead.
 */
class PlayGatewayTest {

    private val gateway = PlayGateway()

    @Test
    fun `successful call returns the value`() = runBlocking {
        val result = gateway.call("test") { "hello" }

        assertThat(result.getOrNull()).isEqualTo("hello")
    }

    @Test
    fun `cancellation is rethrown and never mapped`() = runBlocking {
        var threw = false
        try {
            gateway.call<String>("test") { throw CancellationException("job cancelled") }
        } catch (_: CancellationException) {
            threw = true
        }
        assertThat(threw).isTrue()
    }

    @Test
    fun `network failure yields failure result`() = runBlocking {
        val cause = UnknownHostException("dns")
        val result = gateway.call<String>("test") { throw cause }

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isSameInstanceAs(cause)
    }

    @Test
    fun `io failure yields failure result instead of throwing`() = runBlocking {
        val result = gateway.call<String>("test") { throw IOException("reset") }

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `callOrNull maps failure to null`() = runBlocking {
        val result = gateway.callOrNull<String>("test") { throw UnknownHostException("dns") }

        assertThat(result).isNull()
    }

    @Test
    fun `callOrDefault maps failure to the default`() = runBlocking {
        val result = gateway.callOrDefault("test", default = 42) { throw IOException("reset") }

        assertThat(result).isEqualTo(42)
    }

    @Test
    fun `callOrDefault keeps the success value`() = runBlocking {
        val result = gateway.callOrDefault("test", default = 42) { 7 }

        assertThat(result).isEqualTo(7)
    }
}
