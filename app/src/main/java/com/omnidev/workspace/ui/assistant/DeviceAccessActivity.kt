package com.omnidev.workspace.ui.assistant

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.data.tools.DeviceAccessCatalog
import com.omnidev.workspace.data.tools.DeviceAccessSetupPlan
import com.omnidev.workspace.data.tools.PermissionRequestBridge
import com.omnidev.workspace.data.tools.PermissionRequestPlan
import com.omnidev.workspace.ui.theme.OmniDevTheme
import rikka.shizuku.Shizuku

/** Foreground access requests shared by the assistant and the full agent. */
class DeviceAccessActivity : ComponentActivity() {
    private val model: DeviceAccessViewModel by viewModels()
    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == SHIZUKU_REQUEST) model.state.value.pending
            ?.takeIf { it.kind == DeviceAccessViewModel.RequestKind.SHIZUKU }
            ?.let { model.externalResult(it.id) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        enableEdgeToEdge()
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuResult) }
        setContent {
            OmniDevTheme(dynamicColor = false) { DeviceAccessScreen(model, ::finish) }
        }
    }
    override fun onResume() {
        super.onResume()
        PermissionRequestBridge.attach(this)
        model.refresh()
        // Permission can complete while this Activity is being recreated.
        model.state.value.pending?.takeIf {
            it.kind == DeviceAccessViewModel.RequestKind.SHIZUKU && it.dispatched &&
                com.omnidev.workspace.data.tools.ShizukuCommandTool.hasPermission()
        }?.let { model.externalResult(it.id) }
    }
    override fun onPause() { PermissionRequestBridge.detach(this); super.onPause() }
    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuResult) }
        super.onDestroy()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        model.refresh()
    }
    companion object { private const val SHIZUKU_REQUEST = 0x4F62 }
}

@Composable
private fun DeviceAccessScreen(model: DeviceAccessViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val policy = TierPolicyHolder.current
    val state by model.state.collectAsState()
    var backend by rememberSaveable { mutableStateOf(if (policy.allowShizuku || policy.allowSystemIntegration) "auto" else "android") }
    var launchedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf("") }
    var sensitiveExpanded by rememberSaveable { mutableStateOf(false) }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    val runtimeRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        launchedId?.let { model.externalResult(it) }; launchedId = null
    }
    val settingsRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        launchedId?.let { model.externalResult(it) }; launchedId = null
    }
    val pending = state.pending
    LaunchedEffect(pending?.id) {
        val request = pending?.let { model.markDispatched(it.id) } ?: return@LaunchedEffect
        launchedId = request.id
        runCatching {
            when (request.kind) {
                DeviceAccessViewModel.RequestKind.SHIZUKU -> Shizuku.requestPermission(0x4F62)
                DeviceAccessViewModel.RequestKind.RUNTIME -> runtimeRequest.launch(request.step.permissions.toTypedArray())
                DeviceAccessViewModel.RequestKind.SETTINGS -> {
                    val intent = if (PermissionRequestPlan.usesAppDetails(request.step.key, Build.VERSION.SDK_INT)) {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    } else DeviceAccessCatalog.intent(context, request.step.key)
                    requireNotNull(intent) { "This device has no approval screen for ${request.step.title}." }
                    settingsRequest.launch(intent)
                }
            }
        }.onFailure { model.externalResult(request.id, "Could not open ${request.step.title}: ${it.message.orEmpty().take(180)}"); launchedId = null }
    }
    fun appSettings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
    }
    val declarations = state.access.filter { it.declaration }
    val special = state.access.filter { !it.declaration && it.actionable }
    val ready = declarations.count { it.granted }
    val needsApproval = declarations.count { !it.granted && it.actionable }
    val limited = declarations.size - ready - needsApproval
    val groups = listOf(
        "special" to ("Special access" to special),
        "runtime" to ("App permissions" to declarations.filter { it.route == DeviceAccessSetupPlan.Route.RUNTIME }),
        "development" to ("Settings & diagnostics" to declarations.filter { it.route == DeviceAccessSetupPlan.Route.DEVELOPMENT }),
        "other" to ("Other declarations" to declarations.filter { it.route !in setOf(DeviceAccessSetupPlan.Route.RUNTIME, DeviceAccessSetupPlan.Route.DEVELOPMENT) })
    )
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Device access", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Done") }
                }
                Text("${policy.tier} · Android ${Build.VERSION.RELEASE}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Make Omni ready", style = MaterialTheme.typography.titleLarge)
                        Text("One setup for all permissions in this app. Automatic grants first, then any Android approvals still needed.", style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AccessCount(ready, "Ready", Modifier.weight(1f))
                            AccessCount(needsApproval, "To set up", Modifier.weight(1f))
                            AccessCount(limited, "Restricted", Modifier.weight(1f))
                        }
                        Text(if (state.loaded) "${declarations.size} declarations detected in this installed app" else "Checking this installed app…", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (policy.allowShizuku || policy.allowSystemIntegration) FilterChip(selected = backend == "auto", onClick = { backend = "auto" }, enabled = !state.busy, label = { Text(if (policy.allowShizuku) "Shizuku" else "System") })
                            if (policy.allowRoot) FilterChip(selected = backend == "root", onClick = { backend = "root" }, enabled = !state.busy, label = { Text("Root") })
                            FilterChip(selected = backend == "android", onClick = { backend = "android" }, enabled = !state.busy, label = { Text("Android") })
                        }
                        Button(onClick = { model.start(backend) }, enabled = state.loaded && !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.busy) "Setting up access…" else "Enable all available access")
                        }
                        if (state.busy) {
                            if (state.total > 0 && pending == null) LinearProgressIndicator(progress = { state.completed.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(state.progress, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = model::cancel) { Text("Stop setup") }
                        }
                        Text("Shizuku must be running. Root needs superuser approval. Android may still require a choice for some access.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            pending?.let { request -> item {
                OutlinedCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(request.step.title, style = MaterialTheme.typography.titleMedium)
                        Text("Finish this approval and return. Setup continues automatically; denied access can be retried later.", style = MaterialTheme.typography.bodyMedium)
                        if (PermissionRequestPlan.usesAppDetails(request.step.key, Build.VERSION.SDK_INT)) Text("Permissions → Location → Allow all the time", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::skip) { Text("Skip this step") }
                    }
                }
            } }
            state.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item {
                Text("Review access", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search permissions") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            for ((key, group) in groups) {
                val (title, all) = group
                val filtered = all.filter { search.isBlank() || it.title.contains(search, true) || it.detail.contains(search, true) }
                if (filtered.isNotEmpty()) {
                    item(key = "group:$key") {
                        AccessGroup(title, "${all.count { it.granted }}/${all.size} ready", expanded == key || search.isNotBlank()) { expanded = if (expanded == key) "" else key }
                    }
                    if (expanded == key || search.isNotBlank()) items(filtered, key = { "$key:${it.key}" }) { access ->
                        AccessRow(access, !state.busy) { model.requestSingle(access.key, backend) }
                    }
                }
            }
            if (policy.allowAccessibility) {
                item {
                    AccessGroup("Lock screen & sensitive access", "Identity confirmation", sensitiveExpanded) { sensitiveExpanded = !sensitiveExpanded }
                }
                if (sensitiveExpanded) item {
                    DeviceConsentCard()
                    OutlinedButton(onClick = { context.startActivity(Intent(context, VoiceWakeActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("Set up voice activation") }
                }
            }
            item {
                AccessGroup("Connection & advanced details", "Backends and setup results", diagnosticsExpanded) { diagnosticsExpanded = !diagnosticsExpanded }
            }
            if (diagnosticsExpanded) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((name, status) in state.backends) Text("${name.replace('_', ' ')} · ${accessStatus(status)}", style = MaterialTheme.typography.bodySmall)
                    Text("Restricted declarations need a platform signature, a role, managed-device provisioning or a supported Android version. Connected apps keep their own authorization.", style = MaterialTheme.typography.bodySmall)
                    for (failure in state.failures) Text(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = model::refresh, enabled = !state.busy) { Text("Refresh access") }
                    TextButton(onClick = ::appSettings, enabled = !state.busy) { Text("App settings") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun AccessCount(count: Int, title: String, modifier: Modifier) {
    Column(modifier) {
        Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(title, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AccessGroup(title: String, detail: String, expanded: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (expanded) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AccessRow(access: DeviceAccessSetupPlan.Access, enabled: Boolean, onRequest: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(access.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (access.actionable && !access.granted) TextButton(onClick = onRequest, enabled = enabled) { Text("Set up") }
            else Text(if (access.route == DeviceAccessSetupPlan.Route.ENTITLEMENT && !access.granted) "Restricted" else accessStatus(access.status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(access.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!access.granted && access.actionable) Text(accessStatus(access.status), style = MaterialTheme.typography.labelSmall)
        if (!access.granted && access.route == DeviceAccessSetupPlan.Route.ENTITLEMENT) {
            Text("Requires platform, signature or role access", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

private fun accessStatus(status: String): String = when (status) {
    "GRANTED" -> "Ready"
    "DENIED" -> "Needs approval"
    "TIER_BLOCKED" -> "Unavailable in this edition"
    "NOT_SUPPORTED" -> "Unsupported on this device"
    "NOT_DECLARED" -> "Unavailable in this app"
    "ENABLED_NOT_CONNECTED" -> "Waiting for service connection"
    "NOT_PROBED" -> "Not checked"
    "UNAVAILABLE" -> "Unavailable"
    else -> status.replace('_', ' ')
}
