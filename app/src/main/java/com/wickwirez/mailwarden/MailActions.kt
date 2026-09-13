package com.wickwirez.mailwarden

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder

sealed class ActionResult {
    object Success : ActionResult()
    data class Error(val message: String) : ActionResult()
}

object MailActions {

    private fun props(host: String) = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", host)
        put("mail.imaps.port", "993")
        put("mail.imaps.ssl.enable", "true")
        put("mail.imaps.connectiontimeout", "15000")
        put("mail.imaps.timeout", "30000")
    }

    suspend fun setFlag(
        account: Account,
        uid: Long,
        flag: Flags.Flag,
        value: Boolean
    ): ActionResult = withContext(Dispatchers.IO) {
        run(account) { inbox ->
            val msg = (inbox as UIDFolder).getMessageByUID(uid)
                ?: return@run ActionResult.Error("Message not found")
            msg.setFlag(flag, value)
            ActionResult.Success
        }
    }

    suspend fun delete(account: Account, uid: Long): ActionResult =
        withContext(Dispatchers.IO) {
            run(account) { inbox ->
                val msg = (inbox as UIDFolder).getMessageByUID(uid)
                    ?: return@run ActionResult.Error("Message not found")
                msg.setFlag(Flags.Flag.DELETED, true)
                inbox.expunge()
                ActionResult.Success
            }
        }

    private fun run(
        account: Account,
        block: (Folder) -> ActionResult
    ): ActionResult {
        val host = account.imapHost
        if (host.isBlank()) return ActionResult.Error("No IMAP host set")

        var store: Store? = null
        var inbox: Folder? = null
        return try {
            val session = Session.getInstance(props(host))
            store = session.getStore("imaps")
            store.connect(host, account.email, account.appPassword)
            inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)
            block(inbox)
        } catch (e: Exception) {
            ActionResult.Error(e.message ?: "Unknown error")
        } finally {
            try { inbox?.close(true) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }
}
