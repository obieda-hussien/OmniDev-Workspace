package com.omnidev.workspace.ui.assistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

object AssistantSettings {
    fun intent(context: Context): Intent = Intent(context, AssistantSetupActivity::class.java)

    fun roleRequest(context: Context): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            context.getSystemService(RoleManager::class.java)?.takeIf {
                it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !it.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            }?.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        }.getOrNull()
    } else null

    fun isSelected(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }.getOrDefault(false)
    } else runCatching {
        listOf("assistant", "voice_interaction_service").any { key ->
            android.content.ComponentName.unflattenFromString(Settings.Secure.getString(context.contentResolver, key).orEmpty())?.packageName == context.packageName
        }
    }.getOrDefault(false)

    // Use public intents, not hard-coded OEM Settings component names.
    fun settingsIntents(): List<Intent> = listOf(
        Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
        Settings.ACTION_VOICE_INPUT_SETTINGS,
        Settings.ACTION_SETTINGS
    ).map { Intent(it) }

}

@Composable
fun AssistantSettingsCard(showAccessLinks: Boolean = true) {
    val context = LocalContext.current
    var setupError by remember { mutableStateOf<String?>(null) }
    val setup = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        setupError = result.data?.getStringExtra(AssistantSetupActivity.ERROR)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Text("Omni on your screen", style = MaterialTheme.typography.titleMedium)
            }
            Text("Hold Home or use your device's assistant gesture. Ask a question, attach the screen, or select just one area.", style = MaterialTheme.typography.bodyMedium)
            Text("Choose Omni as the default digital assistant and allow screen content and screenshots in Android's assistant settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showAccessLinks) {
                TextButton(onClick = { context.startActivity(Intent(context, DeviceAccessActivity::class.java)) }) { Text("Device access and permissions") }
            }
            if (showAccessLinks && com.omnidev.workspace.core.policy.TierPolicyHolder.current.allowAccessibility) {
                OutlinedButton(onClick = { context.startActivity(Intent(context, VoiceWakeActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("Voice activation · phrase, training & listening") }
                Text("Wake/lock-screen permissions alone do not start listening. Choose a phrase, record your voice examples, then press Start listening.", style = MaterialTheme.typography.bodySmall)
            }
            setupError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.fillMaxWidth(), onClick = {
                    setupError = null
                    runCatching { setup.launch(AssistantSettings.intent(context)) }
                        .onFailure { setupError = "Could not open setup. Open Android Settings → Apps → Default apps → Digital assistant app, and select OmniDev." }
                }) { Text("Set up assistant") }
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = { context.startActivity(Intent(context, AssistantActivity::class.java)) }) { Text("Try it") }
            }
        }
    }
}
