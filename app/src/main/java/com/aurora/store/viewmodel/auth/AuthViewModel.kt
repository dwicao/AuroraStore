/*
 * SPDX-FileCopyrightText: 2021 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.viewmodel.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.Constants
import com.aurora.extensions.TAG
import com.aurora.gplayapi.session.AuthenticatedSession
import com.aurora.gplayapi.session.TokenType
import com.aurora.store.AuroraApp
import com.aurora.store.R
import com.aurora.store.data.event.AuthEvent
import com.aurora.store.data.model.AccountType
import com.aurora.store.data.model.AuthState
import com.aurora.store.data.providers.AccountProvider
import com.aurora.store.data.providers.AuthProvider
import com.aurora.store.data.providers.GoogleAccountTokenProvider
import com.aurora.store.util.AC2DMTask
import com.aurora.store.util.AuthPrefs
import com.aurora.store.util.PackageUtil
import com.aurora.store.util.Preferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@HiltViewModel
class AuthViewModel @Inject constructor(
    val authProvider: AuthProvider,
    @ApplicationContext private val context: Context,
    private val aC2DMTask: AC2DMTask,
    private val tokenProvider: GoogleAccountTokenProvider
) : ViewModel() {

    private val _authState: MutableStateFlow<AuthState> = MutableStateFlow(AuthState.Init)
    val authState = _authState.asStateFlow()

    /** Emits null once a Google account (non-default) is added, otherwise the error to show. */
    private val _accountAdded = MutableSharedFlow<String?>()
    val accountAdded = _accountAdded.asSharedFlow()

    private var sessionChecked = false

    /**
     * The in-flight auth attempt. Kept so [retry] can cancel a hung validation instead of
     * leaving the splash spinner forever — the root cause of "stuck loading" after the app
     * hasn't been opened for a while (expired session + slow/dead network, or a dead
     * AccountManager callback that never returns).
     */
    private var authJob: Job? = null

    companion object {
        /** Upper bound for any single session validation/mint. OkHttp already times out at 25s. */
        private const val SESSION_TIMEOUT_MS = 35_000L
        /** Longer bound for the microG path, which may show user consent UI. */
        private const val MICROG_TIMEOUT_MS = 120_000L
    }

    /** Only the splash screen should call this: a failed check rebuilds the default session. */
    fun checkSession() {
        if (sessionChecked) return
        sessionChecked = true
        viewModelScope.launch(Dispatchers.IO) {
            // Repair any DB/prefs account drift (backup restore, crash mid-write) before
            // the login gate reads the prefs; cheap and fully offline.
            runCatching { authProvider.reconcileAccountState() }
                .onFailure { Log.e(TAG, "Account reconciliation failed", it) }
            updateAuthState()
        }
        viewModelScope.launch {
            authProvider.sessionRejections.drop(1).collect { updateAuthState() }
        }
    }

    fun buildMicroGAuthData(
        email: String,
        oldToken: String?,
        activity: Activity?,
        onError: () -> Unit
    ) {
        authJob?.cancel()
        // IO: fetchAuthToken switches to Main itself only for the consent UI.
        authJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeout(MICROG_TIMEOUT_MS) {
                    val token = tokenProvider.fetchAuthToken(email, oldToken, activity)
                    buildGoogleAuthDataInternal(email, token, TokenType.AUTH)
                }
            } catch (exception: TimeoutCancellationException) {
                Log.e(TAG, "Timed out getting authToken for Google login", exception)
                _authState.value =
                    AuthState.Failed(context.getString(R.string.session_timeout))
                onError()
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to get authToken for Google login", exception)
                _authState.value =
                    AuthState.Failed(context.getString(R.string.failed_to_generate_session))
                onError()
            }
        }
    }

    fun buildGoogleAuthData(email: String, token: String, tokenType: TokenType) {
        authJob?.cancel()
        _authState.value = AuthState.Fetching
        authJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeout(SESSION_TIMEOUT_MS) {
                    verifyAndSaveAuth(
                        authProvider.buildGoogleAuthData(email, token, tokenType).getOrThrow(),
                        AccountType.GOOGLE
                    )
                }
            } catch (exception: TimeoutCancellationException) {
                Log.e(TAG, "Timed out generating Google session", exception)
                _authState.value =
                    AuthState.Failed(context.getString(R.string.session_timeout))
            } catch (exception: Exception) {
                _authState.value =
                    AuthState.Failed(context.getString(R.string.failed_to_generate_session))
                Log.e(TAG, "Failed to generate Session", exception)
            }
        }
    }

    private suspend fun buildGoogleAuthDataInternal(
        email: String,
        token: String,
        tokenType: TokenType
    ) {
        _authState.value = AuthState.Fetching
        try {
            verifyAndSaveAuth(
                authProvider.buildGoogleAuthData(email, token, tokenType).getOrThrow(),
                AccountType.GOOGLE
            )
        } catch (exception: Exception) {
            _authState.value =
                AuthState.Failed(context.getString(R.string.failed_to_generate_session))
            Log.e(TAG, "Failed to generate Session", exception)
            throw exception
        }
    }

    /**
     * Adds a Google account WITHOUT changing the active default (used by the account screen's
     * "add account" flow). Does not touch [authState], so the current session/UI is unaffected.
     * Re-syncs the prefs to the real default afterwards, because the AC2DM step
     * ([buildAuthData]) writes the new account's e-mail/token into the legacy prefs mid-flow.
     */
    fun addGoogleAuthData(email: String, token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val authData = authProvider
                    .buildGoogleAuthData(email, token, TokenType.AAS)
                    .getOrThrow()
                require(authData.authToken.isNotEmpty() && authData.deviceConfigToken.isNotEmpty())
                authProvider.persistAccount(
                    authData = authData,
                    accountType = AccountType.GOOGLE,
                    authViaMicroG = false,
                    makeDefault = false
                )
            }
            authProvider.syncDefaultToPrefs()
            _accountAdded.emit(
                result.exceptionOrNull()?.let {
                    googleLoginError(it, R.string.failed_to_generate_session)
                }
            )
        }
    }

    fun buildAnonymousAuthData() {
        authJob?.cancel()
        _authState.value = AuthState.Fetching
        authJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeout(SESSION_TIMEOUT_MS) {
                    verifyAndSaveAuth(
                        authProvider.buildAnonymousAuthData().getOrThrow(),
                        AccountType.ANONYMOUS
                    )
                }
            } catch (exception: TimeoutCancellationException) {
                Log.e(TAG, "Timed out generating anonymous session", exception)
                _authState.value =
                    AuthState.Failed(context.getString(R.string.session_timeout))
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to generate Session", exception)
                val message = when {
                    exception is UnknownHostException -> context.getString(R.string.check_connectivity)
                    isDispenserMisconfigured(exception) -> context.getString(
                        R.string.dispensers_not_configured
                    )
                    else -> exception.message.toString()
                }

                _authState.value = AuthState.Failed(message)
            }
        }
    }

    fun buildAuthData(context: Context, email: String, oauthToken: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = aC2DMTask.getAC2DMResponse(email, oauthToken)
                if (response.isNotEmpty()) {
                    val aasToken = response["Token"]
                    if (aasToken != null) {
                        val accountEmail = response["Email"]?.takeIf { it.isNotBlank() } ?: email
                        AuthPrefs.putString(context, Constants.ACCOUNT_EMAIL_PLAIN, accountEmail)
                        AuthPrefs.putString(context, Constants.ACCOUNT_AAS_PLAIN, aasToken)
                        AuroraApp.events.send(
                            AuthEvent.GoogleLogin(true, accountEmail, aasToken)
                        )
                    } else {
                        AuthPrefs.putString(context, Constants.ACCOUNT_EMAIL_PLAIN, "")
                        AuthPrefs.putString(context, Constants.ACCOUNT_AAS_PLAIN, "")
                        AuroraApp.events.send(AuthEvent.GoogleLogin(false, "", ""))
                    }
                } else {
                    AuroraApp.events.send(AuthEvent.GoogleLogin(false, "", ""))
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to build AuthData", exception)
                AuroraApp.events.send(
                    AuthEvent.GoogleLogin(
                        success = false,
                        email = "",
                        token = "",
                        error = googleLoginError(exception, R.string.toast_aas_token_failed)
                    )
                )
            }
        }
    }

    private fun googleLoginError(exception: Throwable, @StringRes fallback: Int): String =
        context.getString(
            when (exception) {
                is SocketTimeoutException -> R.string.google_login_timeout
                is UnknownHostException, is ConnectException -> R.string.check_connectivity
                else -> fallback
            }
        )

    /**
     * The empty-dispenser-set signal from [AuthProvider.buildAnonymousAuthData]: it means
     * the user has no dispenser configured at all (as opposed to all of them failing),
     * so the error must point at Settings instead of a generic "internal error".
     * Also covers the cleartext-dispenser refusal, which is a configuration problem too.
     */
    private fun isDispenserMisconfigured(exception: Throwable): Boolean =
        (exception is IllegalStateException || exception is IllegalArgumentException) &&
            exception.message?.contains("dispenser", ignoreCase = true) == true

    fun retry() {
        // Cancel any hung attempt so a stuck Fetching/Verifying/Pending state can recover.
        // Without this, updateAuthState()'s Fetching guard makes retry a no-op forever.
        authJob?.cancel()
        updateAuthState(force = true)
    }

    private fun updateAuthState(force: Boolean = false) {
        // Guard against overlapping checks, but allow a forced retry to break out of a
        // hung Fetching/Verifying/Pending state (retry() cancels authJob first).
        if (!force && (_authState.value == AuthState.Fetching ||
                _authState.value == AuthState.Verifying ||
                _authState.value is AuthState.PendingAccountManager ||
                _authState.value == AuthState.Available)
        ) {
            return
        }
        if (authProvider.isSessionRepeatedlyRejected) {
            Log.w(TAG, "Session rejected again right after re-validation, not retrying")
            _authState.value = AuthState.Failed(context.getString(R.string.session_rejected))
        } else if (AccountProvider.isLoggedIn(context)) {
            _authState.value = AuthState.Available
            buildSavedAuthData()
        } else {
            _authState.value = AuthState.Unavailable
        }
    }

    private fun buildSavedAuthData() {
        authJob?.cancel()
        authJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeout(SESSION_TIMEOUT_MS) {
                    if (authProvider.isSavedAuthDataValid()) {
                        _authState.value = AuthState.Valid
                    } else {
                        // Generate and validate new auth (inlined so the timeout covers
                        // the whole validate + re-mint, not just the validate half).
                        when (AccountProvider.getAccountType(context)) {
                            AccountType.ANONYMOUS -> {
                                _authState.value = AuthState.Fetching
                                val session =
                                    authProvider.buildAnonymousAuthData().getOrThrow()
                                verifyAndSaveAuth(session, AccountType.ANONYMOUS)
                            }

                            AccountType.GOOGLE -> {
                                val email = AccountProvider.getLoginEmail(context)
                                val tokenPair = AccountProvider.getLoginToken(context)

                                if (email == null || tokenPair == null) {
                                    throw IllegalStateException("Missing saved Google credentials")
                                }

                                when (tokenPair.second) {
                                    TokenType.AAS -> {
                                        _authState.value = AuthState.Fetching
                                        val session = authProvider.buildGoogleAuthData(
                                            email,
                                            tokenPair.first,
                                            TokenType.AAS
                                        ).getOrThrow()
                                        verifyAndSaveAuth(session, AccountType.GOOGLE)
                                    }

                                    TokenType.AUTH -> {
                                        _authState.value =
                                            AuthState.PendingAccountManager(email, tokenPair.first)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (exception: TimeoutCancellationException) {
                Log.e(TAG, "Session check timed out", exception)
                _authState.value = AuthState.Failed(context.getString(R.string.session_timeout))
            } catch (exception: Exception) {
                val error = when {
                    exception is UnknownHostException -> context.getString(R.string.title_no_network)
                    exception is IOException -> context.getString(R.string.server_unreachable)
                    isDispenserMisconfigured(exception) -> context.getString(
                        R.string.dispensers_not_configured
                    )
                    else -> context.getString(R.string.bad_request)
                }
                _authState.value = AuthState.Failed(error)
            }
        }
    }

    private suspend fun verifyAndSaveAuth(
        authData: AuthenticatedSession,
        accountType: AccountType
    ) {
        _authState.value = AuthState.Verifying
        if (authData.authToken.isNotEmpty() && authData.deviceConfigToken.isNotEmpty()) {
            authProvider.saveAuthData(authData)
            val tokenType =
                if (authData.aasToken.isNullOrBlank()) TokenType.AUTH else TokenType.AAS
            AccountProvider.login(
                context,
                authData.email,
                authData.aasToken?.ifBlank { authData.authToken } ?: authData.authToken,
                tokenType,
                accountType
            )
            // Record whether this Google session relies on microG's AccountManager so we
            // can warn the user if microG is later uninstalled.
            AuthPrefs.putBoolean(
                context,
                Preferences.PREFERENCE_AUTH_VIA_MICROG,
                accountType == AccountType.GOOGLE &&
                    tokenType == TokenType.AUTH &&
                    PackageUtil.hasSupportedMicroGVariant(context)
            )
            authProvider.persistAccount(
                authData = authData,
                accountType = accountType,
                authViaMicroG = AuthPrefs.getBoolean(
                    context,
                    Preferences.PREFERENCE_AUTH_VIA_MICROG,
                    false
                ),
                makeDefault = true
            )
            _authState.value = AuthState.SignedIn
        } else {
            authProvider.removeAuthData(context)
            authProvider.logout()
            _authState.value =
                AuthState.Failed(context.getString(R.string.failed_to_generate_session))
        }
    }
}
