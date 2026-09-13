package com.wickwirez.mailwarden

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class BlockStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "mailwarden_blocklist",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun blocked(): Set<String> =
        prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun isBlocked(address: String): Boolean {
        val a = address.trim().lowercase()
        if (a.isBlank()) return false
        val list = blocked()
        if (list.contains(a)) return true
        val domain = a.substringAfterLast("@", "")
        return domain.isNotBlank() && list.contains("@$domain")
    }

    fun block(address: String) {
        val a = address.trim().lowercase()
        if (a.isBlank()) return
        prefs.edit().putStringSet(KEY, blocked() + a).apply()
    }

    fun blockDomain(address: String) {
        val domain = address.trim().lowercase().substringAfterLast("@", "")
        if (domain.isBlank()) return
        prefs.edit().putStringSet(KEY, blocked() + "@$domain").apply()
    }

    fun unblock(entry: String) {
        prefs.edit().putStringSet(KEY, blocked() - entry.trim().lowercase()).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY = "blocked"
    }
}
