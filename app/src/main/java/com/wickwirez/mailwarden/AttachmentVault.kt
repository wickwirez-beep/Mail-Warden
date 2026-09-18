package com.wickwirez.mailwarden

import android.content.Context
import java.io.File

object AttachmentVault {

    private const val DIR = "attachments"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    private fun dir(context: Context): File {
        val d = File(context.cacheDir, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun purgeOld(context: Context) {
        try {
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            dir(context).listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun purgeAll(context: Context) {
        try {
            dir(context).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    fun save(context: Context, filename: String, bytes: ByteArray): File? =
        try {
            val safe = filename
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .takeLast(80)
                .ifBlank { "attachment" }
            val f = File(dir(context), "${System.currentTimeMillis()}_$safe")
            f.writeBytes(bytes)
            f
        } catch (_: Exception) {
            null
        }
}

enum class OpenPolicy {
    VIEW_IN_APP,
    RELEASE_WITH_WARNING,
    BLOCKED
}

data class AttachmentRisk(
    val policy: OpenPolicy,
    val headline: String,
    val detail: String
)

object AttachmentRiskAssessor {

    private val executableExts = listOf(
        ".exe", ".scr", ".bat", ".cmd", ".com", ".pif", ".vbs", ".js",
        ".jar", ".apk", ".msi", ".ps1", ".hta", ".lnk", ".sh", ".dll"
    )

    private val imageExts = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")

    fun assess(
        att: Attachment,
        senderKnown: Boolean,
        messageLevel: ThreatLevel
    ): AttachmentRisk {
        val name = att.filename.lowercase()

        if (executableExts.any { name.endsWith(it) }) {
            return AttachmentRisk(
                OpenPolicy.BLOCKED,
                "Blocked: executable file",
                "This file type can run code on your device. Mail Warden will not open it."
            )
        }

        if (att.status == ScanStatus.MALICIOUS) {
            return AttachmentRisk(
                OpenPolicy.BLOCKED,
                "Blocked: flagged as malicious",
                "Flagged by ${att.detections} of ${att.totalEngines} security engines."
            )
        }

        if (imageExts.any { name.endsWith(it) }) {
            val note = when {
                att.status == ScanStatus.CLEAN ->
                    "Checked against security engines and viewed inside Mail Warden."
                senderKnown ->
                    "From a sender you've corresponded with. Viewed inside Mail Warden, where it cannot run."
                else ->
                    "Viewed inside Mail Warden, where it cannot run."
            }
            return AttachmentRisk(OpenPolicy.VIEW_IN_APP, "Safe to view here", note)
        }

        val warn = buildString {
            append("This opens in another app, outside Mail Warden's protection. ")
            if (att.status == ScanStatus.UNKNOWN) {
                append("No security engine has seen this file before. ")
            }
            if (!senderKnown) {
                append("You have not corresponded with this sender before. ")
            }
            if (messageLevel != ThreatLevel.SAFE) {
                append("The message itself was scored ${messageLevel.label.lowercase()}. ")
            }
        }

        return AttachmentRisk(
            OpenPolicy.RELEASE_WITH_WARNING,
            "Opens outside Mail Warden",
            warn.trim()
        )
    }
}
