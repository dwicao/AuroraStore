/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Isolated store for authentication material: sign-in flag, account type, e-mail,
 * AAS/AUTH tokens, the saved Play session JSON and whether the session came via
 * microG. Kept in its own file (`auth_prefs.xml`) instead of the default
 * SharedPreferences so cloud/device-transfer backups can exclude exactly this file
 * (see `backup_rules.xml`) without wiping the user's harmless settings on restore.
 *
 * Tokens for a personal Google account are the crown jewels here: anyone reading a
 * backup containing them inherits that Google account. Anonymous dispenser accounts
 * are disposable by comparison, but they live here too for uniformity.
 */
object AuthPrefs {

    private const val FILE_NAME = "auth_prefs"

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getString(context: Context, key: String, default: String = ""): String =
        getPrefs(context).getString(key, default).orEmpty()

    fun putString(context: Context, key: String, value: String) {
        getPrefs(context).edit { putString(key, value) }
    }

    fun getBoolean(context: Context, key: String, default: Boolean = false): Boolean =
        getPrefs(context).getBoolean(key, default)

    fun putBoolean(context: Context, key: String, value: Boolean) {
        getPrefs(context).edit { putBoolean(key, value) }
    }

    fun remove(context: Context, key: String) {
        getPrefs(context).edit { remove(key) }
    }
}
