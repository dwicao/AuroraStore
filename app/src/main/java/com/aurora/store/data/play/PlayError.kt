/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.play

import com.aurora.gplayapi.PlayException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException

/**
 * Small, stable taxonomy for every failure coming out of the reversed-engineered Play
 * API (gplayapi). Callers handle [PlayError], never gplayapi internals, so when Google
 * changes something upstream only the mapping in [PlayErrors.of] needs updating — the
 * UI/worker code below it keeps compiling and behaving.
 */
sealed interface PlayError {

    /** The saved session was rejected; re-login is required. */
    data object AuthExpired : PlayError

    /** The call needs a signed-in (non-anonymous) account. */
    data object NeedsAccount : PlayError

    /** Play says the app is not available for this device/session. */
    data object NotSupported : PlayError

    /** Play says the app was not purchased by this account. */
    data object NotPurchased : PlayError

    /** Reserved: Play rate-limiting us. Currently surfaces as [Unknown]. */
    data object RateLimited : PlayError

    /** Connectivity or timeout — nothing wrong with the request itself. */
    data object Network : PlayError

    /** Reserved: Play's servers erroring (5xx). Currently surfaces as [Unknown]. */
    data object ServerError : PlayError

    /**
     * The response didn't look like what the client expected. This is the canary for
     * "Google changed the API": it should be rare, and every occurrence is logged as
     * a warning by [PlayGateway] so a spike is visible before users report breakage.
     */
    data object ProtocolChanged : PlayError

    /** Anything else; kept opaque with the original cause attached. */
    data class Unknown(val cause: Throwable) : PlayError
}

/**
 * Maps any [Throwable] coming out of a Play API call onto [PlayError]. Pure function
 * with no Android dependencies, so it is covered by JVM unit tests.
 */
object PlayErrors {

    fun of(throwable: Throwable): PlayError {        // Auth/session semantics dominate: check the whole cause chain for them first,
        // since gplayapi tends to wrap the meaningful error.
        var cursor: Throwable? = throwable
        while (cursor != null) {
            when (cursor) {
                is PlayException.Unauthorized -> return PlayError.AuthExpired
                is PlayException.RequiresAccount -> return PlayError.NeedsAccount
                is PlayException.AppNotSupported -> return PlayError.NotSupported
                is PlayException.AppNotPurchased -> return PlayError.NotPurchased
                // Any other Play-side failure has no actionable taxonomy yet (gplayapi
                // exposes numeric codes only on some subtypes); keep it visible as
                // Unknown so the monitor still flags a spike.
                is PlayException -> return PlayError.Unknown(throwable)
            }
            cursor = cursor.cause
        }

        cursor = throwable
        while (cursor != null) {
            when (cursor) {
                is UnknownHostException,
                is SocketTimeoutException,
                is ConnectException,
                is TimeoutCancellationException -> return PlayError.Network

                is SerializationException -> return PlayError.ProtocolChanged
            }
            // Generic I/O this deep in a Play call is the network, not the request.
            if (cursor is IOException) return PlayError.Network
            cursor = cursor.cause
        }

        return PlayError.Unknown(throwable)
    }

    /**
     * True when the failure is the auth family, which [PlayGateway] already routes to
     * Splash. Callers setting their own error state use this to stay silent on auth
     * failures instead of flashing an error screen the user is about to leave.
     */
    fun isAuthFailure(throwable: Throwable): Boolean = when (of(throwable)) {
        is PlayError.AuthExpired, is PlayError.NeedsAccount -> true
        else -> false
    }
}
