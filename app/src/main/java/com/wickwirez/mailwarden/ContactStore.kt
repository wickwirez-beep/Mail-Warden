package com.wickwirez.mailwarden

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class Contact(
    val address: String,
    val name: String = "",
    val seen: Int = 1
) {
    val display: String
        get() = if (name.isBlank()) address else "$name <$address>"

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isBlank()) return false
        return address.lowercase().contains(q) || name.lowercase().contains(q)
    }
}

class ContactStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "mailwarden_contacts",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun all(): List<Contact> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Contact(
                    address = o.getString("address"),
                    name = o.optString("name", ""),
                    seen = o.optInt("seen", 1)
                )
            }.sortedByDescending { it.seen }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun suggest(query: String, limit: Int = 5): List<Contact> =
        all().filter { it.matches(query) }.take(limit)

    fun record(address: String, name: String = "") {
        val clean = address.trim().lowercase()
        if (clean.isBlank() || !clean.contains("@")) return
        if (clean.startsWith("no-reply") || clean.startsWith("noreply")) return

        val existing = all().toMutableList()
        val idx = existing.indexOfFirst { it.address == clean }
        if (idx >= 0) {
            val c = existing[idx]
            existing[idx] = c.copy(
                name = if (c.name.isBlank()) name.trim() else c.name,
                seen = c.seen + 1
            )
        } else {
            existing += Contact(clean, name.trim(), 1)
        }
        save(existing.sortedByDescending { it.seen }.take(300))
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun save(contacts: List<Contact>) {
        val arr = JSONArray()
        contacts.forEach {
            arr.put(JSONObject().apply {
                put("address", it.address)
                put("name", it.name)
                put("seen", it.seen)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "contacts"
    }
}
