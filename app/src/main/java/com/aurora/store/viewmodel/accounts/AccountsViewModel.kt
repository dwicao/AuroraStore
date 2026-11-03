/*
 * SPDX-FileCopyrightText: 2025 The Calyx Institute
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.viewmodel.accounts

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.gplayapi.session.TokenType
import com.aurora.store.data.AccountRepository
import com.aurora.store.data.model.AccountType
import com.aurora.store.data.providers.AuthProvider
import com.aurora.store.data.providers.GoogleAccountTokenProvider
import com.aurora.store.data.room.account.Account
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@HiltViewModel
class AccountsViewModel @Inject constructor(
    val authProvider: AuthProvider,
    private val accountRepository: AccountRepository,
    private val tokenProvider: GoogleAccountTokenProvider
) : ViewModel() {

    val accounts = accountRepository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Emits true when the default switched (caller should restart), false on failure. */
    private val _switched = MutableSharedFlow<Boolean>()
    val switched = _switched.asSharedFlow()

    /** Post-removal navigation signal for the screen. */
    enum class RemoveResult { RESTART, LOGGED_OUT }

    private val _removeResult = MutableSharedFlow<RemoveResult>()
    val removeResult = _removeResult.asSharedFlow()

    /** Emits the outcome of an account add (true = added) so the screen can dismiss progress. */
    private val _addResult = MutableSharedFlow<Boolean>()
    val addResult = _addResult.asSharedFlow()

    /** Number of accounts that failed to refresh in the last refresh-all (0 = all succeeded). */
    private val _refreshResult = MutableSharedFlow<Int>()
    val refreshResult = _refreshResult.asSharedFlow()

    companion object {
        /**
         * Upper bound for one interactive account operation (refresh/add/switch). Matches
         * the microG consent window used on the login path: long enough for the user to
         * approve a consent screen, short enough that a hung backend can't wedge the
         * Accounts screen's progress state forever.
         */
        private const val ACCOUNT_OP_TIMEOUT_MS = 120_000L
    }

    /** Refreshes the session/token for every account; the default's prefs mirror is re-synced. */
    fun refreshAll(activity: Activity?) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = accountRepository.getAll()
            val failures = all.count {
                runCatching {
                    // Bounded per account so one hung backend doesn't wedge the batch.
                    withTimeout(ACCOUNT_OP_TIMEOUT_MS) { authProvider.refresh(it.id, activity) }
                }.isFailure
            }
            authProvider.syncDefaultToPrefs()
            _refreshResult.emit(failures)
        }
    }

    fun setDefault(account: Account) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                withTimeout(ACCOUNT_OP_TIMEOUT_MS) { authProvider.setDefaultAccount(account.id) }
            }.isSuccess
            _switched.emit(ok)
        }
    }

    fun remove(account: Account) {
        viewModelScope.launch(Dispatchers.IO) {
            val wasDefault = account.isDefault
            accountRepository.removeAccount(account.id)
            // Keep the legacy login gate / refresh prefs in sync with the new DB default (or clear
            // them when nothing is left) — otherwise removing the active account doesn't log out.
            authProvider.syncDefaultToPrefs()
            when {
                accountRepository.getDefault() == null -> _removeResult.emit(
                    RemoveResult.LOGGED_OUT
                )
                wasDefault -> _removeResult.emit(RemoveResult.RESTART)
                else -> Unit
            }
        }
    }

    /** Adds an anonymous account (non-default) so it can be used without making it active. */
    fun addAnonymousAccount() {
        viewModelScope.launch(Dispatchers.IO) {
            val added = runCatching {
                withTimeout(ACCOUNT_OP_TIMEOUT_MS) {
                    val authData = authProvider.buildAnonymousAuthData().getOrThrow()
                    authProvider.persistAccount(
                        authData = authData,
                        accountType = AccountType.ANONYMOUS,
                        authViaMicroG = false,
                        makeDefault = false
                    )
                }
            }.isSuccess
            _addResult.emit(added)
        }
    }

    /** Google accounts present on-device (via microG) that aren't already added. */
    suspend fun discoverableEmails(): List<String> {
        val existing = accountRepository.getAll().map { it.email.lowercase() }.toSet()
        return tokenProvider.systemGoogleAccountEmails().filter { it.lowercase() !in existing }
    }

    /** Adds a device (microG) Google account by minting an AUTH token and building AuthData. */
    fun addSystemAccount(email: String, activity: Activity?) {
        viewModelScope.launch(Dispatchers.IO) {
            val added = runCatching {
                withTimeout(ACCOUNT_OP_TIMEOUT_MS) {
                    val token = tokenProvider.fetchAuthToken(email = email, activity = activity)
                    val authData = authProvider
                        .buildGoogleAuthData(email, token, TokenType.AUTH)
                        .getOrThrow()
                    authProvider.persistAccount(
                        authData = authData,
                        accountType = AccountType.GOOGLE,
                        authViaMicroG = true,
                        makeDefault = false
                    )
                }
            }.isSuccess
            _addResult.emit(added)
        }
    }
}
