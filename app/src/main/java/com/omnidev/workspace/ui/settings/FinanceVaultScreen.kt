package com.omnidev.workspace.ui.settings

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.vault.FinanceVaultRecord
import com.omnidev.workspace.data.vault.OmniVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinanceVaultScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vault = remember(context) { OmniVault.finance(context) }
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(emptyList<FinanceVaultRecord>()) }
    var kind by remember { mutableStateOf(FinanceVaultRecord.Kind.BANK_ACCOUNT) }
    var issuer by remember { mutableStateOf("") }
    var lastFour by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<FinanceVaultRecord?>(null) }

    suspend fun refresh(change: () -> Unit = {}) {
        busy = true
        message = ""
        try {
            records = withContext(Dispatchers.IO) {
                change()
                vault.listFinanceRecords()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "Could not access your financial records."
        } finally {
            busy = false
        }
    }

    LaunchedEffect(vault) { refresh() }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = { Text("Bank accounts and cards") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Encrypted on this device. Enter the issuer and only the last four digits.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FinanceVaultRecord.Kind.entries.forEach { option ->
                        FilterChip(selected = kind == option, onClick = { kind = option },
                            label = { Text(option.label) }, enabled = !busy)
                    }
                }
                OutlinedTextField(issuer, { if (it.length <= 60) issuer = it }, label = { Text("Issuer name") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lastFour, { value ->
                    if (value.length <= 4 && value.all { it in '0'..'9' }) lastFour = value
                }, label = { Text("Last four digits") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && FinanceVaultRecord.isValid(issuer, lastFour), onClick = {
                    val recordIssuer = issuer
                    val recordKind = kind
                    val recordSuffix = lastFour
                    issuer = ""
                    lastFour = ""
                    busy = true
                    scope.launch { refresh { vault.storeFinanceRecord(recordIssuer, recordKind, recordSuffix) } }
                }) { Text("Save reference") }
                if (message.isNotBlank()) Text(message)
            }
            items(records, key = { it.id }) { record ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${record.issuer} · ${record.kind.label} · •••• ${record.lastFour}", Modifier.weight(1f))
                    TextButton(onClick = { deleting = record }, enabled = !busy) { Text("Delete") }
                }
            }
        }
    }
    deleting?.let { record ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete financial reference?") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    busy = true
                    scope.launch { refresh { vault.delete(record.id) } }
                }) { Text("Delete") }
            }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}
