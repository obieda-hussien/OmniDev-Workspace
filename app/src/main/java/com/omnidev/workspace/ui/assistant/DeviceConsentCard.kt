package com.omnidev.workspace.ui.assistant

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.data.admin.*

@Composable
fun DeviceConsentCard() {
    val context = LocalContext.current
    if (!TierPolicyHolder.current.allowAccessibility) return
    val store = remember { DeviceConsentStore(context) }
    val vault = remember { DevicePinVault(context) }
    var version by remember { mutableIntStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmScope by remember { mutableStateOf<DeviceConsentPolicy.Scope?>(null) }
    val authenticate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK && !store.locked()) {
            runCatching { action?.invoke() }.onFailure { message = "Could not save device consent or local PIN. Try again." }
        } else { pin = ""; message = "Authorization cancelled. Nothing enabled." }
        version++
    }
    fun authorize(action: () -> Unit) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val intent = keyguard?.takeIf { it.isDeviceSecure }?.createConfirmDeviceCredentialIntent(
            "Authorize Omni device access", "Confirm your identity to enable this access. Enter your code only in Android's UI.")
        if (intent == null) { message = "Set an Android screen lock first, then authorize device access."; return }
        pending = action
        runCatching { authenticate.launch(intent) }.onFailure { pending = null; pin = ""; message = "Android authentication unavailable." }
    }
    // The entire access center is protected from screen capture and overlay tapjacking.
    DisposableEffect(context) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) activity?.window?.setHideOverlayWindows(true)
        onDispose { pending = null; pin = "" }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Lock screen and sensitive access", style = MaterialTheme.typography.titleMedium)
            Text("Choose each permission yourself. Enabling requires Android identity confirmation, including in Admin. You can revoke access immediately.", style = MaterialTheme.typography.bodySmall)
            val scopes = DeviceConsentPolicy.Scope.entries.filter {
                it != DeviceConsentPolicy.Scope.SAVED_PIN || TierPolicyHolder.current.tier == "ADMIN"
            }
            for (scope in scopes) {
                val enabled = remember(version, scope) { store.enabled(scope) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(scope.title, style = MaterialTheme.typography.titleSmall)
                        Text(scope.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = enabled, enabled = pending == null, onCheckedChange = { allow ->
                        if (allow) confirmScope = scope
                        else { store.setFromUser(scope, false); pin = ""; version++ }
                    })
                }
            }
            Text("Permissions alone do not provide your device code. For automatic PIN input, save your PIN below and choose Remember PIN authorization. Private local code entry remains available when offline voice is unavailable.", style = MaterialTheme.typography.bodySmall)
            if (store.enabled(DeviceConsentPolicy.Scope.SAVED_PIN)) {
                Text(if (vault.exists()) "Local PIN saved · ${store.pinAuthorizationStatus()}" else "No local PIN saved", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(value = pin, onValueChange = { value ->
                    if (value.length <= 16 && value.all { it in '0'..'9' }) pin = value
                }, label = { Text("Device PIN (local only)") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                Button(enabled = pin.length in 4..16 && pending == null, onClick = { authorize {
                    if (store.enabled(DeviceConsentPolicy.Scope.SAVED_PIN)) {
                        val chars = pin.toCharArray(); pin = ""
                        try {
                            check(store.revokePinAuthorization()) { "Could not revoke the previous PIN authorization." }
                            vault.save(chars)
                            message = "PIN saved locally. Choose an authorization option below."
                        }
                        finally { chars.fill('\u0000') }
                    }
                } }) { Text("Save local PIN") }
                OutlinedButton(enabled = vault.exists() && store.enabled(DeviceConsentPolicy.Scope.UNLOCK) && pending == null,
                    onClick = { authorize {
                        if (store.enabled(DeviceConsentPolicy.Scope.SAVED_PIN) && store.enabled(DeviceConsentPolicy.Scope.UNLOCK)) {
                            message = if (store.armPin()) "One attempt authorized for 15 minutes. Ask Omni to unlock."
                                else "Could not authorize PIN use. Check the PIN and unlock permissions."
                        }
                    } }) { Text("Authorize one PIN attempt") }
                OutlinedButton(enabled = vault.exists() && store.enabled(DeviceConsentPolicy.Scope.UNLOCK) && pending == null,
                    onClick = { authorize {
                        message = if (store.rememberPinFromUser()) "PIN authorization remembered until you revoke it, delete the PIN or clear/uninstall the app. Each request uses one attempt."
                            else "Could not remember PIN authorization. Check the PIN and unlock permissions."
                    } }) { Text(if (store.pinPaused()) "Resume saved PIN attempts" else "Remember PIN authorization") }
                Text("Remembered authorization survives app updates and restarts. Failed or interrupted PIN input pauses attempts until you resume them here.", style = MaterialTheme.typography.bodySmall)
                if (store.pinArmed()) TextButton(enabled = pending == null, onClick = {
                    message = if (store.revokePinAuthorization()) "PIN authorization revoked; the local PIN is still saved."
                        else "Could not revoke PIN authorization. Try again."
                    version++
                }) { Text("Revoke PIN authorization") }
                TextButton(onClick = { vault.delete(); store.setFromUser(DeviceConsentPolicy.Scope.SAVED_PIN, false); pin = ""; version++ }) { Text("Delete PIN and revoke") }
            }
            Text("Protected windows follow Android's restrictions. Passwords, PIN entry, biometrics and existing chat content stay private on the lock screen.", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { store.revokeAll(); pin = ""; version++; message = "All device consent revoked; local PIN deleted." }) { Text("Revoke all access above") }
        }
    }
    confirmScope?.let { scope ->
        AlertDialog(onDismissRequest = { confirmScope = null }, title = { Text(scope.title) },
            text = { Text(scope.detail) }, confirmButton = {
                TextButton(onClick = {
                    confirmScope = null
                    authorize { message = if (store.setFromUser(scope, true)) "Access enabled." else "Could not save access." }
                }) { Text("Confirm with Android") }
            }, dismissButton = { TextButton(onClick = { confirmScope = null }) { Text("Cancel") } })
    }
}
