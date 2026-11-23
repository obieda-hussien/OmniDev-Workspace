package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.vault.OmniVault
import com.omnidev.workspace.data.vault.VaultAccessPolicy

/** Trusted local settings surface: never expose secrets to agent tools or logs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniVaultSettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val vault = remember(context) { OmniVault(context) }
    var site by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var references by remember { mutableStateOf(vault.listReferences()) }
    var message by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<OmniVault.Reference?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("OmniVault") },
            navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Encrypted credentials", style = MaterialTheme.typography.titleMedium)
                Text("Stored on this device only. Agents cannot read passwords. Automatic login is disabled.")
            }
            item {
                OutlinedTextField(site, { site = it.take(240) },
                    label = { Text("HTTPS site") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(label, { label = it.take(96) },
                    label = { Text("Account label") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(password, { password = it.take(2048) },
                    label = { Text("Password or secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                Button(
                    enabled = VaultAccessPolicy.canonicalOrigin(site) != null &&
                        label.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        val borrowed = password.toCharArray()
                        try {
                            vault.storeFromUserInput(label, site, borrowed)
                            site = ""
                            label = ""
                            password = ""
                            references = vault.listReferences()
                            message = "Credential encrypted on device."
                        } catch (_: Exception) {
                            password = ""
                            message = "Could not securely save this credential."
                        } finally {
                            borrowed.fill('\u0000')
                        }
                    }
                ) { Text("Save securely") }
            }
            if (message.isNotBlank()) item { Text(message) }
            item { Text("Saved accounts", style = MaterialTheme.typography.titleMedium) }
            items(references, key = { it.id }) { reference ->
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(reference.label)
                        Text(reference.origin, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { pendingDelete = reference }) { Text("Delete") }
                }
            }
        }
    }

    pendingDelete?.let { reference ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete saved credential?") },
            text = { Text("This permanently removes the encrypted entry from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val deleted = vault.delete(reference.id)
                        references = vault.listReferences()
                        message = if (deleted) "Credential deleted." else "Credential not found."
                    } catch (_: Exception) {
                        message = "Could not delete credential."
                    }
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}
