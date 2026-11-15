/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.util

import android.content.Context
import android.util.Log
import com.aurora.extensions.TAG

/**
 * Encrypted, backup-excluded store for the proxy configuration (URL and the
 * derived [com.aurora.store.data.model.ProxyInfo] JSON, which embeds the proxy
 * username and password).
 *
 * Historically the proxy URL (including its embedded credentials) was written to
 * the default SharedPreferences, which is plain XML and covered by cloud backups
 * and device transfers — leaking the proxy password to anyone who restored the
 * backup. Proxy credentials now live in [AuthPrefs]' isolated file
 * (`auth_prefs.xml`), which is both encrypted (AES256-GCM values, Keystore-held
 * master key, availability-first plain fallback) and excluded from backups.
 *
 * A one-time migration moves any legacy plain-prefs entries into the isolated
 * file on first read/write and then wipes them.
 */
object ProxyPrefs {

    private const val KEY_PROXY_URL = "PREFERENCE_PROXY_URL"
    private const val KEY_PROXY_INFO = "PREFERENCE_PROXY_INFO"

    fun url(context: Context): String = get(context, KEY_PROXY_URL)

    fun infoJson(context: Context): String = get(context, KEY_PROXY_INFO)

    fun save(context: Context, url: String, infoJson: String) {
        migrate(context)
        AuthPrefs.putString(context, KEY_PROXY_URL, url)
        AuthPrefs.putString(context, KEY_PROXY_INFO, infoJson)
        wipeLegacy(context)
    }

    fun clear(context: Context) {
        migrate(context)
        AuthPrefs.remove(context, KEY_PROXY_URL)
        AuthPrefs.remove(context, KEY_PROXY_INFO)
        wipeLegacy(context)
    }

    private fun get(context: Context, key: String): String {
        migrate(context)
        return AuthPrefs.getString(context, key)
    }

    /** Moves legacy plain-prefs entries (if any) into the encrypted store. */
    private fun migrate(context: Context) {
        val legacyUrl = Preferences.getString(context, KEY_PROXY_URL)
        val legacyInfo = Preferences.getString(context, KEY_PROXY_INFO)
        if (legacyUrl.isEmpty() && legacyInfo.isEmpty()) return

        Log.i(TAG, "Migrating proxy configuration into encrypted storage")
        if (legacyUrl.isNotEmpty()) AuthPrefs.putString(context, KEY_PROXY_URL, legacyUrl)
        if (legacyInfo.isNotEmpty()) AuthPrefs.putString(context, KEY_PROXY_INFO, legacyInfo)
        wipeLegacy(context)
    }

    private fun wipeLegacy(context: Context) {
        Preferences.remove(context, KEY_PROXY_URL)
        Preferences.remove(context, KEY_PROXY_INFO)
    }
}
