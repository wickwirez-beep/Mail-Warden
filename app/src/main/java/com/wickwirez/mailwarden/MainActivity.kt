package com.wickwirez.mailwarden

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
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
    val store = remember { CredentialStore(context) }
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf(store.getEmail() ?: "") }
    var password by remember { mutableStateOf(store.getPassword() ?: "") }
    var emails by remember { mutableStateOf<List<EmailSummary>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

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

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Gmail address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("App password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                val cleanEmail = email.trim()
                val cleanPassword = password.replace(" ", "")
                store.save(cleanEmail, cleanPassword)
                loading = true
                status = "Connecting..."
                emails = emptyList()
                scope.launch {
                    when (val result = MailRepository.fetchInbox(cleanEmail, cleanPassword)) {
                        is FetchResult.Success -> {
                            emails = result.emails
                            status = if (result.emails.isEmpty()) {
                                "Connected. Inbox is empty."
                            } else {
                                "Loaded ${result.emails.size} messages"
                            }
                        }
                        is FetchResult.Error -> {
                            status = "Error: ${result.message}"
                        }
                    }
                    loading = false
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Connect to Inbox")
        }

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        if (status.isNotEmpty()) {
            Text(text = status, style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(emails) { mail ->
                Column(modifier = Modifier.fillMaxWidth()) {
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
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
