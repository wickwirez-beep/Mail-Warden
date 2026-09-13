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
        put("mail.imaps.connectiontimeout", "40000")
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

    private val trashNames = listOf(
        "Trash", "[Gmail]/Trash", "Deleted Items", "Deleted Messages", "Bin"
    )

    suspend fun delete(account: Account, uid: Long): ActionResult =
        withContext(Dispatchers.IO) {
            runWithStore(account) { store, inbox ->
                val msg = (inbox as UIDFolder).getMessageByUID(uid)
                    ?: return@runWithStore ActionResult.Error("Message not found")

                val trash = trashNames
                    .asSequence()
                    .mapNotNull { name ->
                        try {
                            val f = store.getFolder(name)
                            if (f.exists()) f else null
                        } catch (_: Exception) { null }
                    }
                    .firstOrNull()

                if (trash == null) {
                    return@runWithStore ActionResult.Error(
                        "No Trash folder found on this account"
                    )
                }

                inbox.copyMessages(arrayOf(msg), trash)
                msg.setFlag(Flags.Flag.DELETED, true)
                ActionResult.Success
            }
        }

    private fun runWithStore(
        account: Account,
        block: (Store, Folder) -> ActionResult
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
            block(store, inbox)
        } catch (e: Exception) {
            ActionResult.Error(e.message ?: "Unknown error")
        } finally {
            try { inbox?.close(true) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
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
