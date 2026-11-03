/*
 * SPDX-FileCopyrightText: 2021 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.providers

import android.content.Context
import com.aurora.Constants
import com.aurora.gplayapi.session.TokenType
import com.aurora.store.data.model.AccountType
import com.aurora.store.util.AuthPrefs
import com.aurora.store.util.Preferences
import com.aurora.store.util.Preferences.PREFERENCE_AUTH_DATA

/**
 * Legacy single-account mirror of the Room account table (see [AuthProvider]: the DB is
 * the source of truth, these prefs are what the login gate and the token-mint paths
 * read). All secrets live in [AuthPrefs]' isolated file — excluded from cloud and
 * device-transfer backups — never in the default SharedPreferences.
 *
 * Reads fall back to the pre-isolation default prefs once, so existing installs
 * migrate transparently; every write goes to the new file and clears the legacy key.
 */
object AccountProvider {

    private fun getString(context: Context, key: String): String {
        val isolated = AuthPrefs.getString(context, key)
        if (isolated.isNotEmpty()) return isolated
        return Preferences.getString(context, key)
    }

    private fun putString(context: Context, key: String, value: String) {
        AuthPrefs.putString(context, key, value)
        Preferences.remove(context, key)
    }

    private fun getBoolean(context: Context, key: String): Boolean =
        AuthPrefs.getBoolean(context, key) || Preferences.getBoolean(context, key)

    private fun putBoolean(context: Context, key: String, value: Boolean) {
        AuthPrefs.putBoolean(context, key, value)
        Preferences.remove(context, key)
    }

    private fun remove(context: Context, key: String) {
        AuthPrefs.remove(context, key)
        Preferences.remove(context, key)
    }

    fun getAccountType(context: Context): AccountType {
        val rawType = getString(context, Constants.ACCOUNT_TYPE)
        return when (rawType) {
            "GOOGLE" -> AccountType.GOOGLE
            else -> AccountType.ANONYMOUS
        }
    }

    fun isLoggedIn(context: Context): Boolean =
        getBoolean(context, Constants.ACCOUNT_SIGNED_IN)

    fun getLoginEmail(context: Context): String? {
        val email = getString(context, Constants.ACCOUNT_EMAIL_PLAIN)
        return email.ifBlank { null }
    }

    fun getLoginToken(context: Context): Pair<String, TokenType>? {
        val email = getString(context, Constants.ACCOUNT_EMAIL_PLAIN)
        val aasToken = getString(context, Constants.ACCOUNT_AAS_PLAIN)
        val authToken = getString(context, Constants.ACCOUNT_AUTH_PLAIN)

        if (email.isBlank() && (aasToken.isBlank() || authToken.isBlank())) return null

        val tokenType = if (aasToken.isBlank()) TokenType.AUTH else TokenType.AAS
        return Pair(aasToken.ifBlank { authToken }, tokenType)
    }

    fun login(
        context: Context,
        email: String,
        token: String,
        tokenType: TokenType,
        accountType: AccountType
    ) {
        putBoolean(context, Constants.ACCOUNT_SIGNED_IN, true)
        putString(context, Constants.ACCOUNT_EMAIL_PLAIN, email)
        putString(context, Constants.ACCOUNT_TYPE, accountType.name)

        if (tokenType == TokenType.AAS) {
            putString(context, Constants.ACCOUNT_AAS_PLAIN, token)
            // The other token must not survive: getLoginToken() prefers a non-blank AAS,
            // so a stale AAS would shadow a fresh AUTH token (and vice versa) after an
            // account-type switch, minting sessions for the wrong credential.
            remove(context, Constants.ACCOUNT_AUTH_PLAIN)
        } else {
            putString(context, Constants.ACCOUNT_AUTH_PLAIN, token)
            remove(context, Constants.ACCOUNT_AAS_PLAIN)
        }
    }

    fun logout(context: Context) {
        remove(context, PREFERENCE_AUTH_DATA)
        remove(context, Constants.ACCOUNT_SIGNED_IN)
        remove(context, Constants.ACCOUNT_TYPE)
        remove(context, Constants.ACCOUNT_EMAIL_PLAIN)
        remove(context, Constants.ACCOUNT_AAS_PLAIN)
        remove(context, Constants.ACCOUNT_AUTH_PLAIN)
        remove(context, Preferences.PREFERENCE_AUTH_VIA_MICROG)
    }
}
