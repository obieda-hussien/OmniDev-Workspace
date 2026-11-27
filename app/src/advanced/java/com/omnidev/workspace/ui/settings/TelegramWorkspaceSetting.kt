package com.omnidev.workspace.ui.settings

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidev.workspace.data.integration.TelegramWorkspace
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TelegramWorkspaceSetting(settings: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target by remember(settings) { settings.observeTargetContext() }.collectAsStateWithLifecycle(null)
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            error = null
            try {
                val path = if (uri.authority == TelegramWorkspace.DOCUMENT_AUTHORITY) {
                    TelegramWorkspace.folderPath(DocumentsContract.getTreeDocumentId(uri))
                } else null
                if (path == null) {
                    error = "Choose a specific folder on this device or an SD card."
                    return@launch
                }
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                settings.setTargetContextUri(uri.toString(), path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not save access to this folder. Choose another device folder."
            }
        }
    }

    Text("Project folder", style = MaterialTheme.typography.labelLarge)
    Text(
        target?.takeIf { it.isNotBlank() && it != "/" }?.substringAfterLast('/')
            ?: "Choose a folder to enable Telegram Agent or Swarm mode.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedButton(onClick = { picker.launch(null) }) { Text("Choose project folder") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
