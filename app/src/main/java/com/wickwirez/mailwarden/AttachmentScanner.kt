package com.wickwirez.mailwarden

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class ScanStatus { UNKNOWN, CLEAN, MALICIOUS, UNSCANNED }

data class Attachment(
    val filename: String,
    val mimeType: String,
    val size: Int,
    val sha256: String = "",
    val status: ScanStatus = ScanStatus.UNKNOWN,
    val detections: Int = 0,
    val totalEngines: Int = 0
) {
    val sizeLabel: String
        get() = when {
            size <= 0 -> ""
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }

    val riskyExtension: Boolean
        get() = listOf(
            ".exe", ".scr", ".bat", ".cmd", ".com", ".pif", ".vbs", ".js",
            ".jar", ".apk", ".msi", ".ps1", ".hta", ".lnk", ".iso", ".img"
        ).any { filename.lowercase().endsWith(it) }
}

fun sha256Of(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

object AttachmentScanner {

    private const val VT_ENDPOINT = "https://www.virustotal.com/api/v3/files/"

    suspend fun scan(attachments: List<Attachment>): List<Attachment> =
        withContext(Dispatchers.IO) {
            val key = BuildConfig.VIRUSTOTAL_KEY
            if (key.isBlank()) {
                return@withContext attachments.map {
                    it.copy(status = ScanStatus.UNSCANNED)
                }
            }
            attachments.map { att ->
                if (att.sha256.isBlank()) {
                    att.copy(status = ScanStatus.UNSCANNED)
                } else {
                    lookup(att, key)
                }
            }
        }

    private fun lookup(att: Attachment, key: String): Attachment {
        return try {
            val conn = (URL(VT_ENDPOINT + att.sha256).openConnection()
                as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("x-apikey", key)
                setRequestProperty("accept", "application/json")
            }

            val code = conn.responseCode

            if (code == 404) {
                conn.disconnect()
                return att.copy(status = ScanStatus.UNKNOWN)
            }
            if (code !in 200..299) {
                conn.disconnect()
                return att.copy(status = ScanStatus.UNSCANNED)
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val stats = JSONObject(body)
                .optJSONObject("data")
                ?.optJSONObject("attributes")
                ?.optJSONObject("last_analysis_stats")
                ?: return att.copy(status = ScanStatus.UNSCANNED)

            val malicious = stats.optInt("malicious", 0)
            val suspicious = stats.optInt("suspicious", 0)
            val harmless = stats.optInt("harmless", 0)
            val undetected = stats.optInt("undetected", 0)
            val total = malicious + suspicious + harmless + undetected

            att.copy(
                status = if (malicious + suspicious > 0) ScanStatus.MALICIOUS
                         else ScanStatus.CLEAN,
                detections = malicious + suspicious,
                totalEngines = total
            )
        } catch (_: Exception) {
            att.copy(status = ScanStatus.UNSCANNED)
        }
    }
}
