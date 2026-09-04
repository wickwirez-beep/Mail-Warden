package com.wickwirez.mailwarden

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Folder
import javax.mail.Session
import javax.mail.Store
import javax.mail.internet.InternetAddress

data class EmailSummary(
    val subject: String,
    val sender: String,
    val date: String
)

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
                put("mail.imaps.timeout", "15000")
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

            val results = messages.reversed().map { msg ->
                val from = (msg.from?.firstOrNull() as? InternetAddress)
                EmailSummary(
                    subject = msg.subject ?: "(no subject)",
                    sender = from?.personal ?: from?.address ?: "(unknown sender)",
                    date = msg.receivedDate?.toString() ?: ""
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
}
