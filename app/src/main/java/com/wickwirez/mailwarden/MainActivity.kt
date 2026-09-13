package com.wickwirez.mailwarden

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MailWardenTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MailWardenApp()
                }
            }
        }
    }
}

@Composable
fun MailWardenApp() {
    val context = LocalContext.current
    val store = remember { AccountStore(context) }
    val scope = rememberCoroutineScope()

    var accounts by remember { mutableStateOf(store.getAccounts()) }
    var activeId by remember { mutableStateOf(store.getActiveId()) }
    var emails by remember { mutableStateOf<List<EmailSummary>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showAddForm by remember { mutableStateOf(accounts.isEmpty()) }
    var openMail by remember { mutableStateOf<EmailSummary?>(null) }
    var openBody by remember { mutableStateOf<EmailBody?>(null) }
    var bodyLoading by remember { mutableStateOf(false) }
    var bodyError by remember { mutableStateOf("") }
    var checkedLinks by remember { mutableStateOf<List<CheckedLink>>(emptyList()) }
    var linksChecking by remember { mutableStateOf(false) }
    var showLinks by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    var composeTo by remember { mutableStateOf("") }
    var composeSubject by remember { mutableStateOf("") }
    var composeBody by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendStatus by remember { mutableStateOf("") }

    var newProvider by remember { mutableStateOf(Provider.GMAIL) }
    var newEmail by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newHost by remember { mutableStateOf("") }
    var providerMenuOpen by remember { mutableStateOf(false) }

    fun loadInbox(account: Account) {
        loading = true
        status = "Connecting to ${account.email}..."
        emails = emptyList()
        scope.launch {
            when (val result = MailRepository.fetchInbox(account)) {
                is FetchResult.Success -> {
                    emails = result.emails
                    status = if (result.emails.isEmpty()) {
                        "Connected. Inbox is empty."
                    } else {
                        "Loaded ${result.emails.size} messages"
                    }
                }
                is FetchResult.Error -> status = "Error: ${result.message}"
            }
            loading = false
        }
    }

    fun doSend() {
        val acct = store.getActive()
        if (acct == null) {
            sendStatus = "No active account"
            return
        }
        sending = true
        sendStatus = "Sending..."
        scope.launch {
            when (val r = MailSender.send(
                acct,
                Draft(composeTo, composeSubject, composeBody)
            )) {
                is SendResult.Success -> {
                    sendStatus = "Sent"
                    composeTo = ""
                    composeSubject = ""
                    composeBody = ""
                    composing = false
                }
                is SendResult.Error -> sendStatus = "Error: ${r.message}"
            }
            sending = false
        }
    }

    if (composing) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { composing = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }

            Text(
                text = "New Message",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "From: ${store.getActive()?.email ?: "(no account)"}",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = composeTo,
                onValueChange = { composeTo = it },
                label = { Text("To") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = composeSubject,
                onValueChange = { composeSubject = it },
                label = { Text("Subject") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = composeBody,
                onValueChange = { composeBody = it },
                label = { Text("Message") },
                minLines = 8,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { doSend() },
                enabled = !sending && composeTo.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Send")
            }

            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            if (sendStatus.isNotEmpty()) {
                Text(text = sendStatus, style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    val current = openMail
    if (current != null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    openMail = null
                    openBody = null
                    bodyError = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back to Inbox")
            }

            Text(
                text = current.subject,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "From: ${current.sender}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = current.date,
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = {
                    composeTo = current.senderAddress
                    composeSubject = if (current.subject.startsWith("Re:", true)) {
                        current.subject
                    } else {
                        "Re: ${current.subject}"
                    }
                    composeBody = ""
                    sendStatus = ""
                    composing = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reply")
            }

            HorizontalDivider()

            if (bodyLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            if (bodyError.isNotEmpty()) {
                Text(text = "Error: $bodyError")
            }

            openBody?.let { body ->
                val v = body.verdict
                Text(
                    text = "${v.level.label}  (score ${v.score})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = threatColor(v.level)
                )
                if (v.signals.isEmpty()) {
                    Text(
                        text = "No warning signs found. Sender authentication passed and nothing suspicious in the content.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    v.signals.forEach { sig ->
                        Text(
                            text = "• ${sig.name} (+${sig.points})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "   ${sig.detail}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                HorizontalDivider()

                if (body.links.isNotEmpty()) {
                    val unsafe = checkedLinks.count { it.status == LinkStatus.UNSAFE }
                    val summary = when {
                        linksChecking -> "Checking ${body.links.size} links..."
                        unsafe > 0 -> "$unsafe of ${checkedLinks.size} links flagged UNSAFE"
                        checkedLinks.isNotEmpty() -> "${checkedLinks.size} links checked, none flagged"
                        else -> "${body.links.size} links"
                    }

                    OutlinedButton(
                        onClick = { showLinks = !showLinks },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (showLinks) "Hide links" else summary)
                    }

                    if (unsafe > 0 && !showLinks) {
                        Text(
                            text = "Tap above to review flagged links.",
                            style = MaterialTheme.typography.bodySmall,
                            color = threatColor(ThreatLevel.DANGEROUS)
                        )
                    }

                    if (showLinks) {
                        val shown = if (checkedLinks.isNotEmpty()) checkedLinks
                                    else body.links.map {
                                        CheckedLink(it, it, "", false)
                                    }
                        shown.forEach { link ->
                            val color = when (link.status) {
                                LinkStatus.UNSAFE -> threatColor(ThreatLevel.DANGEROUS)
                                LinkStatus.CLEAN -> threatColor(ThreatLevel.SAFE)
                                LinkStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                text = link.finalHost.ifBlank { link.original },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = color,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (link.status == LinkStatus.UNSAFE) {
                                Text(
                                    text = "   Flagged: ${link.threatType}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = color
                                )
                            }
                            if (link.redirected) {
                                Text(
                                    text = "   Redirects from ${link.original.take(60)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }

                Text(
                    text = body.text.ifBlank { "(no readable content)" },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Mail Warden",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        if (accounts.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(accounts) { acct ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = acct.id == activeId,
                            onClick = {
                                activeId = acct.id
                                store.setActiveId(acct.id)
                                loadInbox(acct)
                            },
                            label = {
                                Text("${acct.provider.displayName}: ${acct.email}")
                            }
                        )
                        TextButton(onClick = {
                            store.removeAccount(acct.id)
                            accounts = store.getAccounts()
                            activeId = store.getActiveId()
                            emails = emptyList()
                            status = "Removed ${acct.email}"
                            if (accounts.isEmpty()) showAddForm = true
                        }) {
                            Text("Remove")
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { showAddForm = !showAddForm },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showAddForm) "Cancel" else "Add another account")
            }
        }

        if (showAddForm) {
            OutlinedButton(
                onClick = { providerMenuOpen = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Provider: ${newProvider.displayName}")
            }
            DropdownMenu(
                expanded = providerMenuOpen,
                onDismissRequest = { providerMenuOpen = false }
            ) {
                Provider.entries.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.displayName) },
                        onClick = {
                            newProvider = p
                            providerMenuOpen = false
                        }
                    )
                }
            }

            OutlinedTextField(
                value = newEmail,
                onValueChange = { newEmail = it },
                label = { Text("Email address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (newProvider.needsManualHost) {
                OutlinedTextField(
                    value = newHost,
                    onValueChange = { newHost = it },
                    label = { Text("IMAP host (e.g. imap.example.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("App password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val acct = store.addAccount(
                        provider = newProvider,
                        email = newEmail,
                        appPassword = newPassword,
                        customImapHost = newHost
                    )
                    accounts = store.getAccounts()
                    activeId = store.getActiveId()
                    newEmail = ""
                    newPassword = ""
                    newHost = ""
                    showAddForm = false
                    loadInbox(acct)
                },
                enabled = !loading && newEmail.isNotBlank() && newPassword.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add and Connect")
            }
        }

        if (!showAddForm && accounts.isNotEmpty()) {
            Button(
                onClick = {
                    composeTo = ""
                    composeSubject = ""
                    composeBody = ""
                    sendStatus = ""
                    composing = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Compose")
            }

            Button(
                onClick = { store.getActive()?.let { loadInbox(it) } },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh Inbox")
            }
        }

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        if (status.isNotEmpty()) {
            Text(text = status, style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(emails) { mail ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            openMail = mail
                            openBody = null
                            bodyError = ""
                            checkedLinks = emptyList()
                            showLinks = false
                            bodyLoading = true
                            scope.launch {
                                val acct = store.getActive()
                                if (acct == null) {
                                    bodyError = "No active account"
                                } else {
                                    when (val r = MailRepository.fetchBody(acct, mail.uid)) {
                                        is BodyResult.Success -> {
                                            openBody = r.body
                                            if (r.body.links.isNotEmpty()) {
                                                linksChecking = true
                                                checkedLinks = LinkChecker.check(r.body.links)
                                                linksChecking = false
                                            }
                                        }
                                        is BodyResult.Error -> bodyError = r.message
                                    }
                                }
                                bodyLoading = false
                            }
                        }
                ) {
                    Text(
                        text = mail.sender,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = mail.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = mail.date,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (mail.verdict.level != ThreatLevel.SAFE) {
                        AssistChip(
                            onClick = { },
                            label = { Text(mail.verdict.level.label) },
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = threatColor(mail.verdict.level)
                            )
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

fun threatColor(level: ThreatLevel): Color = when (level) {
    ThreatLevel.SAFE -> Color(0xFF4CAF50)
    ThreatLevel.SUSPICIOUS -> Color(0xFFFFA726)
    ThreatLevel.DANGEROUS -> Color(0xFFC1121F)
}
