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
import androidx.compose.material3.IconButton
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
import com.omnidev.workspace.data.vault.VaultHandle
import com.omnidev.workspace.data.vault.VaultOrigin

/** Trusted, user-operated UI only. No credential contents travel through Agent tools. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniVaultSettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val vault = remember(context) { OmniVault(context) }
    var origin by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var handles by remember { mutableStateOf(vault.handles()) }
    var message by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<VaultHandle?>(null) }

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
                Text("Only HTTPS sites. Credentials stay on this device. Agents cannot read them and automatic login is not enabled yet.")
            }
            item {
                OutlinedTextField(origin, { origin = it.take(240) }, label = { Text("Site origin (https://example.com)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(label, { label = it.take(120) }, label = { Text("Account label") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(password, { password = it.take(8192) }, label = { Text("Secret or password") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
            item {
                Button(
                    enabled = VaultOrigin.normalize(origin) != null && label.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        try {
                            vault.put(origin, label, password.toCharArray())
                            origin = ""; label = ""; password = ""
                            handles = vault.handles()
                            message = "Credential encrypted on device."
                        } catch (_: Exception) {
                            password = ""
                            message = "Could not store credential securely."
                        }
                    }
                ) { Text("Save in OmniVault") }
            }
            if (message.isNotBlank()) item { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Text("Saved references", style = MaterialTheme.typography.titleMedium) }
            items(handles, key = { it.id }) { handle ->
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(handle.label, style = MaterialTheme.typography.bodyLarge)
                        Text(handle.origin, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { pendingDelete = handle }) { Text("Delete") }
                }
            }
        }
    }

    pendingDelete?.let { handle ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete saved credential?") },
            text = { Text("This permanently deletes the local entry and revokes its approvals.") },
            confirmButton = {
                TextButton(onClick = {
                    vault.delete(handle.id)
                    handles = vault.handles()
                    pendingDelete = null
                    message = "Credential deleted."
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}
