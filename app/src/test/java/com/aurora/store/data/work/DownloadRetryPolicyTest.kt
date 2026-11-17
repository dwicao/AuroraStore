/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.work

import com.google.common.truth.Truth.assertThat
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Test

class DownloadRetryPolicyTest {

    // --- isRetryableType -------------------------------------------------------------

    @Test
    fun nullCause_isNotRetryable() {
        assertThat(DownloadRetryPolicy.isRetryableType(null)).isFalse()
    }

    @Test
    fun socketErrors_areRetryable() {
        assertThat(DownloadRetryPolicy.isRetryableType(SocketException())).isTrue()
        assertThat(DownloadRetryPolicy.isRetryableType(SocketTimeoutException())).isTrue()
        assertThat(DownloadRetryPolicy.isRetryableType(UnknownHostException())).isTrue()
    }

    @Test
    fun wrappedNetworkError_isFoundThroughTheCauseChain() {
        val wrapped = IllegalStateException("download failed", SocketException("reset"))
        assertThat(DownloadRetryPolicy.isRetryableType(wrapped)).isTrue()
    }

    @Test
    fun nestedNetworkError_isFoundTwoLevelsDown() {
        val wrapped = IllegalStateException(
            "outer",
            IllegalStateException("inner", UnknownHostException("no dns"))
        )
        assertThat(DownloadRetryPolicy.isRetryableType(wrapped)).isTrue()
    }

    @Test
    fun unrelatedFailure_isNotRetryable() {
        assertThat(DownloadRetryPolicy.isRetryableType(IllegalStateException("nope"))).isFalse()
    }

    @Test
    fun deepButUnrelatedChain_isNotRetryable() {
        var throwable = Exception("root")
        repeat(40) { throwable = Exception("layer $it", throwable) }
        assertThat(DownloadRetryPolicy.isRetryableType(throwable)).isFalse()
    }

    @Test
    fun selfReferencingCause_terminates() {
        // A cycle must not hang the walk: the depth guard bails out and reports "not retryable".
        assertThat(DownloadRetryPolicy.isRetryableType(SelfCaused())).isFalse()
    }

    // --- shouldRetry -------------------------------------------------------------------

    @Test
    fun retryableFailure_isRetried() {
        assertThat(retry(attemptCount = 0, retryable = true)).isTrue()
    }

    @Test
    fun nonRetryableFailure_isNotRetried() {
        assertThat(retry(attemptCount = 0, retryable = false)).isFalse()
    }

    @Test
    fun systemStop_isRetriedEvenWhenNotRetryableByType() {
        assertThat(
            DownloadRetryPolicy.shouldRetry(
                cancelledByUser = false,
                isSystemStop = true,
                retryable = false,
                attemptCount = 0
            )
        ).isTrue()
    }

    @Test
    fun userCancellation_alwaysWins() {
        assertThat(
            DownloadRetryPolicy.shouldRetry(
                cancelledByUser = true,
                isSystemStop = true,
                retryable = true,
                attemptCount = 0
            )
        ).isFalse()
    }

    @Test
    fun attemptLimit_isExclusive() {
        assertThat(retry(attemptCount = DownloadRetryPolicy.MAX_RETRIES - 1)).isTrue()
        assertThat(retry(attemptCount = DownloadRetryPolicy.MAX_RETRIES)).isFalse()
        assertThat(retry(attemptCount = DownloadRetryPolicy.MAX_RETRIES + 1)).isFalse()
    }

    private fun retry(attemptCount: Int, retryable: Boolean = true): Boolean =
        DownloadRetryPolicy.shouldRetry(
            cancelledByUser = false,
            isSystemStop = false,
            retryable = retryable,
            attemptCount = attemptCount
        )

    /** A throwable whose cause is itself — the pathological case the depth guard exists for. */
    private class SelfCaused : RuntimeException("loop") {
        override val cause: Throwable get() = this
    }
}
