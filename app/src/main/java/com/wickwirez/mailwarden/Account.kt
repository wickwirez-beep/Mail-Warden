package com.wickwirez.mailwarden

import org.json.JSONArray
import org.json.JSONObject

enum class Provider(
    val displayName: String,
    val imapHost: String,
    val smtpHost: String,
    val needsManualHost: Boolean = false
) {
    GMAIL("Gmail", "imap.gmail.com", "smtp.gmail.com"),
    YAHOO("Yahoo", "imap.mail.yahoo.com", "smtp.mail.yahoo.com"),
    ICLOUD("iCloud", "imap.mail.me.com", "smtp.mail.me.com"),
    AOL("AOL", "imap.aol.com", "smtp.aol.com"),
    OTHER("Other (IMAP)", "", "", needsManualHost = true);

    companion object {
        fun fromName(name: String): Provider =
            entries.firstOrNull { it.name == name } ?: OTHER
    }
}

data class Account(
    val id: String,
    val provider: Provider,
    val email: String,
    val appPassword: String,
    val customImapHost: String = ""
) {
    val imapHost: String
        get() = if (provider.needsManualHost) customImapHost else provider.imapHost

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("provider", provider.name)
        put("email", email)
        put("appPassword", appPassword)
        put("customImapHost", customImapHost)
    }

    companion object {
        fun fromJson(o: JSONObject): Account = Account(
            id = o.getString("id"),
            provider = Provider.fromName(o.getString("provider")),
            email = o.getString("email"),
            appPassword = o.getString("appPassword"),
            customImapHost = o.optString("customImapHost", "")
        )

        fun listToJson(accounts: List<Account>): String {
            val arr = JSONArray()
            accounts.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String?): List<Account> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
