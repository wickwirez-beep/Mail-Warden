package com.wickwirez.mailwarden

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class LinkStatus { UNKNOWN, CLEAN, UNSAFE }

data class CheckedLink(
    val original: String,
    val finalUrl: String,
    val finalHost: String,
    val redirected: Boolean,
    val status: LinkStatus = LinkStatus.UNKNOWN,
    val threatType: String = ""
)

object LinkChecker {

    private const val SB_ENDPOINT =
        "https://safebrowsing.googleapis.com/v4/threatMatches:find"

    suspend fun check(links: List<String>): List<CheckedLink> =
        withContext(Dispatchers.IO) {
            val unique = links.distinct().take(25)
            val resolved = unique.map { resolve(it) }
            val verdicts = safeBrowsingLookup(resolved.map { it.finalUrl })
            resolved.map { link ->
                val hit = verdicts[link.finalUrl]
                when {
                    hit != null -> link.copy(status = LinkStatus.UNSAFE, threatType = hit)
                    verdicts.isEmpty() -> link
                    else -> link.copy(status = LinkStatus.CLEAN)
                }
            }
        }

    private fun resolve(url: String): CheckedLink {
        var current = url
        var hops = 0
        try {
            while (hops < 5) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "HEAD"
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MailWarden")
                }
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                conn.disconnect()

                if (code in 300..399 && !location.isNullOrBlank()) {
                    current = if (location.startsWith("http")) {
                        location
                    } else {
                        URL(URL(current), location).toString()
                    }
                    hops++
                } else {
                    break
                }
            }
        } catch (_: Exception) {
        }

        return CheckedLink(
            original = url,
            finalUrl = current,
            finalHost = hostOf(current),
            redirected = current != url
        )
    }

    private fun hostOf(url: String): String = try {
        URL(url).host?.lowercase()?.removePrefix("www.") ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun safeBrowsingLookup(urls: List<String>): Map<String, String> {
        val key = BuildConfig.SAFE_BROWSING_KEY
        if (key.isBlank() || urls.isEmpty()) return emptyMap()

        return try {
            val entries = JSONArray()
            urls.distinct().forEach { entries.put(JSONObject().put("url", it)) }

            val payload = JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientId", "mailwarden")
                    put("clientVersion", "1.0")
                })
                put("threatInfo", JSONObject().apply {
                    put("threatTypes", JSONArray(listOf(
                        "MALWARE", "SOCIAL_ENGINEERING",
                        "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION"
                    )))
                    put("platformTypes", JSONArray(listOf("ANY_PLATFORM")))
                    put("threatEntryTypes", JSONArray(listOf("URL")))
                    put("threatEntries", entries)
                })
            }

            val conn = (URL("$SB_ENDPOINT?key=$key").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return emptyMap()
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val matches = JSONObject(body).optJSONArray("matches") ?: return mapOf("" to "")
            val out = mutableMapOf<String, String>()
            for (i in 0 until matches.length()) {
                val m = matches.getJSONObject(i)
                val u = m.optJSONObject("threat")?.optString("url") ?: continue
                out[u] = m.optString("threatType", "UNSAFE")
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
