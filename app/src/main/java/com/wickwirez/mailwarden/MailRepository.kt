package com.wickwirez.mailwarden

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
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
    val starred: Boolean = false,
    val unread: Boolean = false,
    val verdict: SpamVerdict = SpamVerdict.UNKNOWN
)

data class EmailBody(
    val text: String,
    val links: List<String>,
    val verdict: SpamVerdict = SpamVerdict.UNKNOWN,
    val attachments: List<Attachment> = emptyList(),
    val timing: String = ""
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

    private val storeCache = java.util.concurrent.ConcurrentHashMap<String, Store>()
    private val bodyCache = java.util.concurrent.ConcurrentHashMap<String, EmailBody>()
    private val storeUsedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var prefetchJob: kotlinx.coroutines.Job? = null
    private val prefetchScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    private fun connectedStore(account: Account, host: String, forceFresh: Boolean = false): Store {
        val key = "${account.email}@$host"
        if (!forceFresh) {
            val cached = storeCache[key]
            val usedAt = storeUsedAt[key] ?: 0L
            val now = System.currentTimeMillis()
            if (cached != null && (now - usedAt < 60_000 || cached.isConnected)) {
                storeUsedAt[key] = now
                return cached
            }
        }
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "40000")
            put("mail.imaps.timeout", "45000")
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(host, account.email, account.appPassword)
        storeCache[key] = store
        storeUsedAt[key] = System.currentTimeMillis()
        return store
    }

    suspend fun fetchInbox(
        account: Account,
        folderName: String = "INBOX",
        limit: Int = 25
    ): FetchResult = withContext(Dispatchers.IO) {
        val host = account.imapHost
        if (host.isBlank()) {
            return@withContext FetchResult.Error("No IMAP host set for this account")
        }

        var inbox: Folder? = null
        try {
            val store = connectedStore(account, host)

            inbox = store.getFolder(folderName)
            if (!inbox.exists()) {
                return@withContext FetchResult.Error("Folder not found: $folderName")
            }
            inbox.open(Folder.READ_ONLY)

            val total = inbox.messageCount
            if (total == 0) return@withContext FetchResult.Success(emptyList())

            val start = maxOf(1, total - limit + 1)
            val messages = inbox.getMessages(start, total)

            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
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
                    starred = try { msg.isSet(javax.mail.Flags.Flag.FLAGGED) } catch (_: Exception) { false },
                    unread = try { !msg.isSet(javax.mail.Flags.Flag.SEEN) } catch (_: Exception) { false },
                    verdict = verdict
                )
            }

            prefetchJob?.cancel()
            val topUids = results.take(10).map { it.uid }
            prefetchJob = prefetchScope.launch {
                for (u in topUids) {
                    if (!isActive) break
                    if (bodyCache.containsKey("${account.email}|$folderName|$u")) continue
                    fetchBody(account, u, folderName)
                }
            }
            FetchResult.Success(results)
        } catch (e: Exception) {
            storeCache.remove("${account.email}@$host")
            FetchResult.Error(e.message ?: "Unknown error")
        } finally {
            try { inbox?.close(false) } catch (_: Exception) {}
        }
    }

    suspend fun fetchBody(
        account: Account,
        uid: Long,
        folderName: String = "INBOX"
    ): BodyResult = withContext(Dispatchers.IO) {
        val host = account.imapHost
        if (host.isBlank()) {
            return@withContext BodyResult.Error("No IMAP host set for this account")
        }

        val cacheKey = "${account.email}|$folderName|$uid"
        bodyCache[cacheKey]?.let {
            return@withContext BodyResult.Success(it.copy(timing = "cached"))
        }

        var inbox: Folder? = null
        try {
            val tStart = System.currentTimeMillis()
            val store = connectedStore(account, host)
            val tConn = System.currentTimeMillis()

            inbox = store.getFolder(folderName)
            inbox.open(Folder.READ_ONLY)
            val tOpen = System.currentTimeMillis()

            val msg = (inbox as UIDFolder).getMessageByUID(uid)
                ?: return@withContext BodyResult.Error("Message not found")
            inbox.fetch(arrayOf(msg), FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.CONTENT_INFO)
                add("From")
                add("Authentication-Results")
                add("Received-SPF")
                add("Reply-To")
                add("Return-Path")
                add("List-Unsubscribe")
            })
            val tFind = System.currentTimeMillis()

            val hrefs = mutableListOf<String>()
            val text = extractText(msg, hrefs)
            val tText = System.currentTimeMillis()
            val links = (hrefs + extractLinks(text)).distinct().take(50)
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
            val displayText = if (cleanText.replace(Regex("\\s+"), "").length < 60 && links.isNotEmpty()) {
                "(This message is mostly images and links. Use the link list above to see where it points.)"
            } else {
                cleanText
            }

            val tHdr = System.currentTimeMillis()
            val atts = mutableListOf<Attachment>()
            extractAttachments(msg, atts)
            val tAtt = System.currentTimeMillis()

            val body = EmailBody(
                text = displayText,
                links = links,
                verdict = verdict,
                attachments = atts,
                timing = "c${tConn - tStart} o${tOpen - tConn} f${tFind - tOpen} t${tText - tFind} h${tHdr - tText} x${tAtt - tHdr}"
            )
            if (bodyCache.size > 50) bodyCache.clear()
            bodyCache[cacheKey] = body
            BodyResult.Success(body)
        } catch (e: Exception) {
            storeCache.remove("${account.email}@$host")
            BodyResult.Error(e.message ?: "Unknown error")
        } finally {
            try { inbox?.close(false) } catch (_: Exception) {}
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

    suspend fun fetchAttachmentBytes(
        account: Account,
        uid: Long,
        partPath: String,
        folderName: String = "INBOX"
    ): ByteArray? = withContext(Dispatchers.IO) {
        val host = account.imapHost
        if (host.isBlank()) return@withContext null
        var inbox: Folder? = null
        try {
            val store = connectedStore(account, host)
            inbox = store.getFolder(folderName)
            inbox.open(Folder.READ_ONLY)
            val msg = (inbox as UIDFolder).getMessageByUID(uid)
                ?: return@withContext null
            var part: Part = msg
            if (partPath.isNotEmpty()) {
                for (idx in partPath.split(".")) {
                    val mp = part.content as? MimeMultipart
                        ?: return@withContext null
                    part = mp.getBodyPart(idx.toInt())
                }
            }
            val bytes = part.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > 15 * 1024 * 1024) null else bytes
        } catch (e: Exception) {
            storeCache.remove("${account.email}@$host")
            null
        } finally {
            try { inbox?.close(false) } catch (_: Exception) {}
        }
    }

    private fun extractAttachments(part: Part, out: MutableList<Attachment>, path: String = "") {
        try {
            val disp = part.disposition
            val name = try {
                javax.mail.internet.MimeUtility.decodeText(part.fileName ?: "")
            } catch (_: Exception) {
                part.fileName
            }
            if (!name.isNullOrBlank() &&
                (disp == null || disp.equals(Part.ATTACHMENT, true) ||
                 disp.equals(Part.INLINE, true))) {
                val rawSize = try { part.size } catch (_: Exception) { -1 }
                val enc = try {
                    (part as? javax.mail.internet.MimePart)?.encoding?.lowercase()
                } catch (_: Exception) { null }
                out += Attachment(
                    filename = name,
                    mimeType = part.contentType?.substringBefore(";")?.trim() ?: "",
                    size = if (enc == "base64" && rawSize > 0) rawSize * 3 / 4 else rawSize,
                    partPath = path
                )
                return
            }
            if (part.isMimeType("multipart/*")) {
                val mp = part.content as? MimeMultipart ?: return
                for (i in 0 until mp.count) {
                    extractAttachments(mp.getBodyPart(i), out, if (path.isEmpty()) "$i" else "$path.$i")
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun readTextPart(p: Part?): String {
        if (p == null) return ""
        return try {
            when (val c = p.content) {
                is String -> c
                is java.io.InputStream -> c.use { String(it.readBytes(), Charsets.UTF_8) }
                else -> c?.toString() ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun findTextPart(part: Part, mime: String): Part? {
        try {
            val disp = part.disposition
            if (disp != null && disp.equals(Part.ATTACHMENT, true)) return null
            if (part.isMimeType(mime)) return part
            if (part.isMimeType("multipart/*")) {
                val mp = part.content as? MimeMultipart ?: return null
                for (i in 0 until mp.count) {
                    val found = findTextPart(mp.getBodyPart(i), mime)
                    if (found != null) return found
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun extractText(part: Part, hrefs: MutableList<String> = mutableListOf()): String {
        val plain = readTextPart(findTextPart(part, "text/plain")).trim()
        if (plain.length >= 120) return plain
        val raw = readTextPart(findTextPart(part, "text/html"))
        val html = if (raw.isNotBlank()) cleanHtml(raw, hrefs) else ""
        return if (html.length > plain.length) html else plain
    }

    private fun decodeEntity(code: String, radix: Int): String =
        try {
            String(Character.toChars(code.toInt(radix)))
        } catch (_: Exception) {
            " "
        }

    private fun cleanHtml(raw: String, hrefs: MutableList<String>): String {
        Regex("(?i)href\\s*=\\s*[\"']?(https?://[^\"'\\s>]+)")
            .findAll(raw)
            .forEach { hrefs += it.groupValues[1].replace("&amp;", "&") }
        return raw
            .replace(Regex("(?s)<!--.*?-->"), " ")
            .replace(Regex("(?is)<head[^>]*>.*?</head>"), " ")
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|tr|table|h[1-6]|li)>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { decodeEntity(it.groupValues[1], 16) }
            .replace(Regex("&#(\\d+);")) { decodeEntity(it.groupValues[1], 10) }
            .replace("&nbsp;", " ")
            .replace("&zwnj;", "")
            .replace("&rsquo;", "'")
            .replace("&lsquo;", "'")
            .replace("&rdquo;", "\"")
            .replace("&ldquo;", "\"")
            .replace("&mdash;", "-")
            .replace("&ndash;", "-")
            .replace("&copy;", "(c)")
            .replace("&reg;", "(R)")
            .replace("&trade;", "(TM)")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace(Regex("[ \\t\\u00A0\\u200B\\u200C\\u034F]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun extractLinks(text: String): List<String> =
        Regex("https?://[^\\s<>\"')\\]]+")
            .findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':') }
            .distinct()
            .take(50)
            .toList()
}
