package com.wickwirez.mailwarden

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "mailwarden_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(email: String, appPassword: String) {
        prefs.edit()
            .putString(KEY_EMAIL, email)
            .putString(KEY_PASSWORD, appPassword)
            .apply()
    }

    fun getEmail(): String? = prefs.getString(KEY_EMAIL, null)

    fun getPassword(): String? = prefs.getString(KEY_PASSWORD, null)

    fun hasCredentials(): Boolean = getEmail() != null && getPassword() != null

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD = "app_password"
    }
}
