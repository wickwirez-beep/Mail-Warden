package com.wickwirez.mailwarden

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMultipart
import javax.mail.Part

data class EmailSummary(
    val uid: Long,
    val subject: String,
    val sender: String,
    val senderAddress: String = "",
    val date: String,
    val timestamp: Long = 0L,
    val verdict: SpamVerdict = SpamVerdict.UNKNOWN
)

data class EmailBody(
    val text: String,
    val links: List<String>,
    val verdict: SpamVerdict = SpamVerdict.UNKNOWN
)

sealed class BodyResult {
    data class Success(val body: EmailBody) : BodyResult()
    data class Error(val message: String) : BodyResult()
}

sealed class FetchResult {
    data class Success(val emails: List<EmailSummary>) : FetchResult()
    data class Error(val message: String) : FetchResult()
}

object MailRepository {

    suspend fun fetchInbox(
        account: Account,
        limit: Int = 25
    ): FetchResult = withContext(Dispatchers.IO) {
        val host = account.imapHost
        if (host.isBlank()) {
            return@withContext FetchResult.Error("No IMAP host set for this account")
        }

        var store: Store? = null
        var inbox: Folder? = null
        try {
            val props = Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", host)
                put("mail.imaps.port", "993")
                put("mail.imaps.ssl.enable", "true")
                put("mail.imaps.connectiontimeout", "15000")
                put("mail.imaps.timeout", "45000")
            }

            val session = Session.getInstance(props)
            store = session.getStore("imaps")
            store.connect(host, account.email, account.appPassword)

            inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            val total = inbox.messageCount
            if (total == 0) return@withContext FetchResult.Success(emptyList())

            val start = maxOf(1, total - limit + 1)
            val messages = inbox.getMessages(start, total)

            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(UIDFolder.FetchProfileItem.UID)
                add("Authentication-Results")
                add("Received-SPF")
                add("Reply-To")
                add("Return-Path")
                add("List-Unsubscribe")
            }
            inbox.fetch(messages, fp)

            val uidFolder = inbox as UIDFolder
            val results = messages.reversed().map { msg ->
                val from = (msg.from?.firstOrNull() as? InternetAddress)
                val subj = msg.subject ?: "(no subject)"
                val headers = readHeaders(msg)
                val verdict = SpamScorer.score(
                    headers = headers,
                    subject = subj,
                    body = "",
                    links = emptyList(),
                    providerStripsAuth = account.provider == Provider.YAHOO ||
                                         account.provider == Provider.AOL
                )
                EmailSummary(
                    uid = uidFolder.getUID(msg),
                    subject = subj,
                    sender = from?.personal ?: from?.address ?: "(unknown sender)",
                    senderAddress = from?.address ?: "",
                    date = msg.receivedDate?.toString() ?: "",
                    timestamp = msg.receivedDate?.time ?: 0L,
                    verdict = verdict
                )
            }

            FetchResult.Success(results)
        } catch (e: Exception) {
            FetchResult.Error(e.message ?: "Unknown error")
        } finally {
            try { inbox?.close(false) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    suspend fun fetchBody(
        account: Account,
        uid: Long
    ): BodyResult = withContext(Dispatchers.IO) {
        val host = account.imapHost
        if (host.isBlank()) {
            return@withContext BodyResult.Error("No IMAP host set for this account")
        }

        var store: Store? = null
        var inbox: Folder? = null
        try {
            val props = Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", host)
                put("mail.imaps.port", "993")
                put("mail.imaps.ssl.enable", "true")
                put("mail.imaps.connectiontimeout", "15000")
                put("mail.imaps.timeout", "45000")
            }

            val session = Session.getInstance(props)
            store = session.getStore("imaps")
            store.connect(host, account.email, account.appPassword)

            inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            val msg = (inbox as UIDFolder).getMessageByUID(uid)
                ?: return@withContext BodyResult.Error("Message not found")

            val text = extractText(msg)
            val links = extractLinks(text)
            val cleanText = text
                .replace(Regex("\\[?https?://[^\\s<>\"')\\]]+\\]?"), "")
                .replace(Regex("[ \\t]{2,}"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
            val verdict = SpamScorer.score(
                headers = readHeaders(msg),
                subject = msg.subject ?: "",
                body = text,
                links = links,
                providerStripsAuth = account.provider == Provider.YAHOO ||
                                     account.provider == Provider.AOL
            )
            BodyResult.Success(EmailBody(text = cleanText, links = links, verdict = verdict))
        } catch (e: Exception) {
            BodyResult.Error(e.message ?: "Unknown error")
        } finally {
            try { inbox?.close(false) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    private fun readHeaders(msg: javax.mail.Message): MessageHeaders {
        fun first(name: String): String =
            try { msg.getHeader(name)?.firstOrNull() ?: "" } catch (_: Exception) { "" }

        val fromRaw = first("From")
        val fromAddr = (msg.from?.firstOrNull() as? InternetAddress)?.address ?: ""

        return MessageHeaders(
            fromDisplay = fromRaw,
            fromAddress = fromAddr,
            replyTo = first("Reply-To"),
            returnPath = first("Return-Path"),
            authResults = first("Authentication-Results"),
            receivedSpf = first("Received-SPF"),
            listUnsubscribe = first("List-Unsubscribe")
        )
    }

    private fun extractText(part: Part): String {
        try {
            if (part.isMimeType("text/plain")) {
                return part.content?.toString() ?: ""
            }
            if (part.isMimeType("text/html")) {
                val html = part.content?.toString() ?: ""
                return html
                    .replace(Regex("(?s)<(script|style).*?</\\1>"), " ")
                    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
                    .replace(
                        Regex("(?is)<a[^>]*>(.*?)</a>"),
                        "$1"
                    )
                    .replace(Regex("<[^>]+>"), " ")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace(Regex("[ \\t]{2,}"), " ")
                    .replace(Regex("\\n{3,}"), "\n\n")
                    .trim()
            }
            if (part.isMimeType("multipart/*")) {
                val mp = part.content as? MimeMultipart ?: return ""
                val parts = (0 until mp.count).map { mp.getBodyPart(it) }
                parts.firstOrNull { it.isMimeType("text/plain") }?.let {
                    return extractText(it)
                }
                return parts.joinToString("\n") { extractText(it) }.trim()
            }
        } catch (_: Exception) {
        }
        return ""
    }

    private fun extractLinks(text: String): List<String> =
        Regex("https?://[^\\s<>\"')\\]]+")
            .findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':') }
            .distinct()
            .take(50)
            .toList()
}
