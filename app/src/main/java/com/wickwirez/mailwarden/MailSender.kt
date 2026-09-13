package com.wickwirez.mailwarden

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

sealed class SendResult {
    object Success : SendResult()
    data class Error(val message: String) : SendResult()
}

data class Draft(
    val to: String,
    val subject: String,
    val body: String,
    val inReplyToUid: Long? = null
)

object MailSender {

    suspend fun send(
        account: Account,
        draft: Draft
    ): SendResult = withContext(Dispatchers.IO) {
        val host = account.provider.smtpHost
        if (host.isBlank()) {
            return@withContext SendResult.Error("No SMTP host set for this provider")
        }

        val recipients = draft.to
            .split(",", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (recipients.isEmpty()) {
            return@withContext SendResult.Error("No recipient address")
        }

        try {
            val props = Properties().apply {
                put("mail.smtp.host", host)
                put("mail.smtp.port", "587")
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
                put("mail.smtp.connectiontimeout", "20000")
                put("mail.smtp.timeout", "20000")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(account.email, account.appPassword)
            })

            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(account.email))
                recipients.forEach {
                    addRecipient(Message.RecipientType.TO, InternetAddress(it))
                }
                subject = draft.subject.ifBlank { "(no subject)" }
                setText(draft.body, "UTF-8")
                sentDate = java.util.Date()
            }

            Transport.send(msg)
            SendResult.Success
        } catch (e: Exception) {
            SendResult.Error(e.message ?: "Unknown error")
        }
    }
}
