/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.work

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * The retry decision table used by [DownloadWorker].
 *
 * It lives outside the worker on purpose: the worker's own failure types are `inner`
 * classes (they need a [android.content.Context] for their messages), which made the whole
 * retry rule untestable and left "should this failure be retried?" verifiable only on a
 * device. Everything here is plain JVM logic, so it is covered by
 * `DownloadRetryPolicyTest`.
 *
 * The worker keeps its `when` over its own exception types and hands the *decision* — and
 * the standard exception tree-walk — to this object.
 */
object DownloadRetryPolicy {

    /**
     * Upper bound on automatic WorkManager retries for transient (network) failures before
     * the download is marked as failed and left for the user to retry.
     */
    const val MAX_RETRIES: Int = 5

    /**
     * Guard against a self-referential cause chain (`a.cause = b`, `b.cause = a`), which
     * would otherwise spin forever while walking it.
     */
    private const val MAX_CAUSE_DEPTH: Int = 16

    /**
     * Whether [throwable] — or anything in its cause chain — is a connectivity-shaped
     * failure that is worth another attempt once the network is back.
     *
     * Only standard exceptions are recognised here; the worker's app-specific failures
     * (expired URLs, a sick CDN edge, no network route) are classified by the caller and
     * passed to [shouldRetry] as `retryable`.
     */
    fun isRetryableType(throwable: Throwable?): Boolean {
        var current = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            when (current) {
                is SocketException,
                is SocketTimeoutException,
                is UnknownHostException,
                // A session validation/refresh that hit its timeout is transient by nature.
                is TimeoutCancellationException -> return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * The single retry gate. A user cancellation is final and wins over everything else: a
     * cancelled download must never burn a WorkManager retry. Otherwise a failure is
     * retried while attempts remain, as long as it is retryable (or the system stopped the
     * work for its own reasons, e.g. connectivity/quota/device state).
     */
    fun shouldRetry(
        cancelledByUser: Boolean,
        isSystemStop: Boolean,
        retryable: Boolean,
        attemptCount: Int,
        maxRetries: Int = MAX_RETRIES
    ): Boolean {
        if (cancelledByUser) return false
        if (attemptCount >= maxRetries) return false
        return retryable || isSystemStop
    }
}
