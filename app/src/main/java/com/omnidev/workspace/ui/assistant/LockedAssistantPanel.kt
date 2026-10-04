package com.omnidev.workspace.ui.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.omnidev.workspace.data.admin.DeviceConsentPolicy
import com.omnidev.workspace.data.admin.DeviceConsentStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun rememberDeviceLocked(): Boolean {
    val context = LocalContext.current
    var locked by remember { mutableStateOf(DeviceConsentStore(context).locked()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                locked = intent.action == Intent.ACTION_SCREEN_OFF || DeviceConsentStore(context).locked()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(context) {
        while (true) { locked = DeviceConsentStore(context).locked(); delay(250) }
    }
    return locked
}

/** No chat history, attachments, console, speech, or grant controls while locked. */
@Composable
fun LockedAssistantPanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val consent = DeviceConsentStore(context)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun request(action: String) {
        if (busy) return
        busy = true
        scope.launch {
            try { message = com.omnidev.workspace.data.tools.DeviceAdminTool.execute(context, mapOf("action" to action)).output }
            finally { busy = false }
        }
    }
    var allowed by remember { mutableStateOf(consent.enabled(DeviceConsentPolicy.Scope.LOCK_OVERLAY)) }
    LaunchedEffect(context) {
        while (true) {
            allowed = consent.enabled(DeviceConsentPolicy.Scope.LOCK_OVERLAY)
            if (!allowed) { onDismiss(); break }
            delay(250)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Omni · Device locked", style = MaterialTheme.typography.titleLarge)
                Text(if (allowed) "Unlock your device to continue. Your conversation stays private while locked."
                    else "Enable Assistant on the lock screen from Device access after unlocking.")
                if (allowed && consent.enabled(DeviceConsentPolicy.Scope.UNLOCK)) {
                    Button(onClick = { request("request_unlock") }, enabled = !busy) { Text("Unlock with Android") }
                    if (consent.enabled(DeviceConsentPolicy.Scope.SAVED_PIN) && consent.pinArmed()) {
                        OutlinedButton(onClick = { request("unlock_with_saved_pin") }, enabled = !busy) { Text("Use authorized local PIN") }
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
