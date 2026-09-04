package com.wickwirez.mailwarden

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

class AccountStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "mailwarden_accounts",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getAccounts(): List<Account> =
        Account.listFromJson(prefs.getString(KEY_ACCOUNTS, null))

    fun addAccount(
        provider: Provider,
        email: String,
        appPassword: String,
        customImapHost: String = ""
    ): Account {
        val account = Account(
            id = UUID.randomUUID().toString(),
            provider = provider,
            email = email.trim(),
            appPassword = appPassword.replace(" ", ""),
            customImapHost = customImapHost.trim()
        )
        val updated = getAccounts().filterNot { it.email.equals(account.email, true) } + account
        save(updated)
        if (getActiveId() == null) setActiveId(account.id)
        return account
    }

    fun removeAccount(id: String) {
        val remaining = getAccounts().filterNot { it.id == id }
        save(remaining)
        if (getActiveId() == id) setActiveId(remaining.firstOrNull()?.id)
    }

    fun getActive(): Account? {
        val accounts = getAccounts()
        val id = getActiveId()
        return accounts.firstOrNull { it.id == id } ?: accounts.firstOrNull()
    }

    fun getActiveId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActiveId(id: String?) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun save(accounts: List<Account>) {
        prefs.edit().putString(KEY_ACCOUNTS, Account.listToJson(accounts)).apply()
    }

    companion object {
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE = "active_id"
    }
}
