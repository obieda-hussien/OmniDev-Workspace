package com.omnidev.workspace.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.launch

@Composable
internal fun WorkspaceLockGate(settings: SettingsRepository, content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT < 30) {
        content()
        return
    }

    val workspaceState = rememberSaveableStateHolder()
    val enabled by remember(settings) { settings.observeWorkspaceLock() }.collectAsStateWithLifecycle(null)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalContext.current.findActivity()
    val window = activity?.window
    var unlocked by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) unlocked = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val secureWindow = enabled != false
    DisposableEffect(window, secureWindow) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        val alreadySecure = ((window?.attributes?.flags ?: 0) and flag) != 0
        if (secureWindow) window?.addFlags(flag)
        onDispose { if (secureWindow && !alreadySecure) window?.clearFlags(flag) }
    }

    when {
        enabled == false || unlocked -> workspaceState.SaveableStateProvider("workspace") { content() }
        enabled == true -> Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
            ) {
                Text("Workspace locked", style = MaterialTheme.typography.headlineSmall)
                WorkspaceAuthenticationButton("Unlock workspace", "Unlock") { unlocked = true }
            }
        }
    }
}

@Composable
internal fun WorkspaceLockSettings() {
    if (Build.VERSION.SDK_INT < 30) return
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val enabled by remember(settings) { settings.observeWorkspaceLock() }.collectAsStateWithLifecycle(null)
    val current = enabled ?: return
    Text(if (current) "Workspace screen lock is on." else "Workspace screen lock is off.")
    Text("Use your device PIN, pattern, password, or a supported fingerprint or face unlock.")
    WorkspaceAuthenticationButton(
        title = if (current) "Disable workspace lock" else "Enable workspace lock",
        label = if (current) "Turn off lock" else "Turn on lock"
    ) {
        scope.launch { settings.setWorkspaceLock(!current) }
    }
}

@RequiresApi(30)
@Composable
private fun WorkspaceAuthenticationButton(title: String, label: String, onAuthenticated: () -> Unit) {
    val activity = LocalContext.current.findActivity() ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var pending by remember(activity) { mutableStateOf<CancellationSignal?>(null) }
    var error by remember(activity) { mutableStateOf<String?>(null) }
    DisposableEffect(activity) {
        onDispose {
            val signal = pending
            pending = null
            signal?.cancel()
        }
    }

    Button(enabled = pending == null, onClick = {
        val signal = CancellationSignal()
        pending = signal
        error = null
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (pending !== signal) return
                pending = null
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) onAuthenticated()
                else error = "Unlock again while OmniDev is open."
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (pending !== signal) return
                pending = null
                error = errString.toString()
            }
        }
        try {
            BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
                .authenticate(signal, activity.mainExecutor, callback)
        } catch (_: IllegalStateException) {
            pending = null
            error = "Device authentication is unavailable."
        } catch (_: SecurityException) {
            pending = null
            error = "Device authentication is unavailable."
        }
    }) { Text(label) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

private fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()
