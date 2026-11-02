/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.play

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import org.junit.Test

/**
 * Covers the framework-independent branches of [PlayErrors.of]. The gplayapi-subtype
 * branches (AuthExpired, NeedsAccount, …) need a live Play session to construct and are
 * instead covered by the nightly live-API smoke workflow.
 */
class PlayErrorsTest {

    @Test
    fun unknownHost_isNetwork() {
        assertThat(PlayErrors.of(UnknownHostException("dns"))).isEqualTo(PlayError.Network)
    }

    @Test
    fun socketTimeout_isNetwork() {
        assertThat(PlayErrors.of(SocketTimeoutException("slow"))).isEqualTo(PlayError.Network)
    }

    @Test
    fun coroutineTimeout_isNetwork() {
        // TimeoutCancellationException's constructor is internal; produce a real one.
        val cause = try {
            runBlocking {
                withTimeout(1L) { delay(1000L) }
                error("should have timed out")
            }
        } catch (expected: TimeoutCancellationException) {
            expected
        }
        assertThat(PlayErrors.of(cause)).isEqualTo(PlayError.Network)
    }

    @Test
    fun plainIOException_isNetwork() {
        assertThat(PlayErrors.of(IOException("reset"))).isEqualTo(PlayError.Network)
    }

    @Test
    fun wrappedNetworkError_isNetwork() {
        assertThat(PlayErrors.of(RuntimeException("wrapper", UnknownHostException())))
            .isEqualTo(PlayError.Network)
    }

    @Test
    fun serializationProblem_isProtocolChanged() {
        assertThat(PlayErrors.of(SerializationException("bad json")))
            .isEqualTo(PlayError.ProtocolChanged)
    }

    @Test
    fun wrappedSerializationProblem_isProtocolChanged() {
        assertThat(
            PlayErrors.of(IllegalStateException("mint", SerializationException("bad")))
        ).isEqualTo(PlayError.ProtocolChanged)
    }

    @Test
    fun anythingElse_isUnknownWithCause() {
        val cause = IllegalArgumentException("weird")
        val error = PlayErrors.of(cause)
        assertThat(error).isInstanceOf(PlayError.Unknown::class.java)
        assertThat((error as PlayError.Unknown).cause).isSameInstanceAs(cause)
    }
}
