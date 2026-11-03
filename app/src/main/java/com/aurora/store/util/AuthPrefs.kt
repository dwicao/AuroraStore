/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.aurora.extensions.TAG

/**
 * Isolated store for authentication material: sign-in flag, account type, e-mail,
 * AAS/AUTH tokens, the saved Play session JSON and whether the session came via
 * microG. Kept in its own file (`auth_prefs.xml`) instead of the default
 * SharedPreferences so backups can exclude exactly that file (see `backup_rules.xml`)
 * without wiping the user's harmless settings.
 *
 * The file is encrypted with a key held in the Android Keystore
 * ([EncryptedSharedPreferences]): a backup that leaks no longer hands the reader
 * a working Google account token, and neither does a plain file read off a rooted
 * device. Anything the Keystore cannot do on this device (broken StrongBox,
 * mangled keystore) falls back to a plain private file rather than logging the
 * user out — availability first, encryption best-effort.
 *
 * Tokens for a personal Google account are the crown jewels here: anyone reading a
 * backup containing them inherits that Google account. Anonymous dispenser accounts
 * are disposable by comparison, but they live here too for uniformity.
 */
object AuthPrefs {

    private const val FILE_NAME = "auth_prefs"

    @Volatile
    private var cached: SharedPreferences? = null

    fun getPrefs(context: Context): SharedPreferences {
        cached?.let { return it }
        val created = createEncrypted(context) ?: context.getSharedPreferences(
            FILE_NAME,
            Context.MODE_PRIVATE
        )
        cached = created
        return created
    }

    private fun createEncrypted(context: Context): SharedPreferences? {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val encrypted = EncryptedSharedPreferences.create(
                FILE_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            migratePlainInto(context, encrypted)
            encrypted
        } catch (exception: Exception) {
            Log.e(TAG, "Encrypted prefs unavailable, using plain private file", exception)
            null
        }
    }

    /**
     * One-time move from the pre-encryption plain file of the same name into the
     * encrypted one. Only the keys this object ever wrote are carried over; the
     * plain file is wiped afterwards so no cleartext copy lingers.
     */
    private fun migratePlainInto(context: Context, encrypted: SharedPreferences) {
        if (encrypted.all.isNotEmpty()) return
        val plain = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val existing = plain.all
        if (existing.isEmpty()) return
        Log.i(TAG, "Migrating ${existing.size} auth prefs into encrypted storage")
        encrypted.edit {
            for ((key, value) in existing) {
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> Log.w(TAG, "Skipping auth pref $key of unknown type")
                }
            }
        }
        plain.edit { clear() }
    }

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
