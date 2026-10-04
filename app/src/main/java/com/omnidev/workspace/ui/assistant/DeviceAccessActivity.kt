package com.omnidev.workspace.ui.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.data.tools.DeviceAccessCatalog
import com.omnidev.workspace.data.tools.PermissionManagerTool
import com.omnidev.workspace.data.tools.PermissionRequestBridge
import com.omnidev.workspace.data.tools.PermissionRequestPlan
import com.omnidev.workspace.data.tools.AppOpAccessPlan
import com.omnidev.workspace.ui.theme.OmniDevTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A real foreground Activity for access requests originating in a VoiceInteractionSession. */
class DeviceAccessActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OmniDevTheme(dynamicColor = false) {
                DeviceAccessScreen(refresh, onRefresh = { refresh++ }, onClose = ::finish)
            }
        }
    }
    override fun onResume() { super.onResume(); PermissionRequestBridge.attach(this); refresh++ }
    override fun onPause() { PermissionRequestBridge.detach(this); super.onPause() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh++
    }
}

@Composable
private fun DeviceAccessScreen(refresh: Int, onRefresh: () -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val policy = TierPolicyHolder.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var runtime by remember { mutableStateOf<List<String>>(emptyList()) }
    var statuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showRuntime by remember { mutableStateOf(false) }
    var showBackground by remember { mutableStateOf(false) }
    var showProtected by remember { mutableStateOf(false) }
    var protectedDeclarations by remember { mutableStateOf<List<String>>(emptyList()) }
    val keys = remember { DeviceAccessCatalog.entries.map { it.key } + AppOpAccessPlan.entries.map { it.key } + listOf("shizuku", "rish", "root", "system", "device_owner", "profile_owner", "termux") }
    LaunchedEffect(refresh) {
        val snapshot = withContext(Dispatchers.IO) {
            val names = PermissionManagerTool.runtimePermissions(context)
            val declarations = PermissionManagerTool.declaredPermissions(context).sorted()
            Triple(names, declarations.filterNot { it in names }, (keys + declarations).associateWith { PermissionManagerTool.checkPermission(context, it) })
        }
        runtime = snapshot.first
        protectedDeclarations = snapshot.second
        statuses = snapshot.third
    }
    fun request(key: String, backend: String = "auto") {
        if (busy) return
        busy = true
        scope.launch {
            try { message = PermissionManagerTool.requestPermission(context, key, backend).output }
            finally { busy = false; onRefresh() }
        }
    }
    fun appSettings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            .onFailure { message = "Open Android Settings → Apps → OmniDev → Permissions." }
    }
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Device access", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(onClick = onClose) { Text("Done") }
                }
                Text("${policy.tier} · Android ${android.os.Build.VERSION.SDK_INT}", style = MaterialTheme.typography.labelLarge)
                Text("Choose the access Omni needs. The assistant and full agent use these same grants. Android and connected apps decide which capabilities your device can provide.", style = MaterialTheme.typography.bodyMedium)
            }
            message?.let { result -> item {
                Card(Modifier.fillMaxWidth()) { Text(result, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
            } }
            item {
                AccessSection("Runtime permissions", "${runtime.count { statuses[it] == "GRANTED" }}/${runtime.size} supported grants available") {
                    Button(onClick = { request("bootstrap_max") }, enabled = !busy && runtime.isNotEmpty()) { Text("Request runtime access") }
                    Row {
                        TextButton(onClick = { showRuntime = !showRuntime }) { Text(if (showRuntime) "Hide details" else "Choose individually") }
                        TextButton(onClick = ::appSettings) { Text("App permissions") }
                    }
                }
            }
            if (showRuntime) for (name in runtime.filterNot { it in PermissionRequestPlan.staged }) item(key = name) {
                AccessRow(name.substringAfterLast('.').replace('_', ' '), name, statuses[name].orEmpty(), !busy) { request(name) }
            }
            val staged = runtime.filter { it in PermissionRequestPlan.staged }
            if (staged.isNotEmpty()) item {
                TextButton(onClick = { showBackground = !showBackground }) { Text("Background access · ${staged.size} separate steps") }
            }
            if (showBackground) for (name in staged) item(key = name) {
                AccessRow(name.substringAfterLast('.').replace('_', ' '), "Grant foreground access first, then choose background access separately.", statuses[name].orEmpty(), !busy) { request(name) }
            }
            item { DeviceConsentCard() }
            if (policy.allowAccessibility) item {
                OutlinedButton(onClick = { context.startActivity(Intent(context, VoiceWakeActivity::class.java)) }) { Text("Voice activation · train and test my phrase") }
            }
            item { Text("Special access", style = MaterialTheme.typography.titleLarge) }
            for (entry in DeviceAccessCatalog.entries) {
                val status = statuses[entry.key].orEmpty()
                if (status !in setOf("TIER_BLOCKED", "NOT_SUPPORTED", "NOT_DECLARED")) item(key = entry.key) {
                    AccessRow(entry.title, entry.detail, status, !busy) { request(entry.key) }
                }
            }
            item {
                AccessSection("Privileged execution", "Shizuku: ${statuses["shizuku"]} · rish: ${statuses["rish"]}\nRoot: ${statuses["root"]} · System UID: ${statuses["system"]}") {
                    if (policy.allowShizuku) OutlinedButton(onClick = { request("shizuku") }, enabled = !busy) { Text("Authorize Shizuku") }
                    if (policy.allowRoot) OutlinedButton(onClick = { request("root") }, enabled = !busy) { Text("Verify root access") }
                    Text("Shizuku through ADB runs as shell; Shizuku started with root may run as root. Root needs a rooted device. System access needs the ROM's genuine entitlements. Diagnostics never trigger a root prompt.", style = MaterialTheme.typography.bodySmall)
                    if (policy.allowShizuku || policy.allowSystemIntegration) Button(onClick = { request("privileged_bootstrap") }, enabled = !busy) { Text("Grant settings and diagnostics") }
                    if (policy.allowRoot) TextButton(onClick = { request("privileged_bootstrap", "root") }, enabled = !busy) { Text("Grant settings and diagnostics via root") }
                    Text("Requests secure settings, logs, dumps, battery statistics, configuration, AppOps statistics and cross-user development grants, where supported. Every grant is verified. Signature-only permissions need their real platform entitlement.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (policy.allowShizuku || policy.allowSystemIntegration || policy.allowRoot) {
                item { Text("Advanced special access", style = MaterialTheme.typography.titleLarge) }
                for (entry in AppOpAccessPlan.entries.filter { android.os.Build.VERSION.SDK_INT >= it.minSdk && it.permission in protectedDeclarations }) item(key = entry.key) {
                    AccessSection(entry.specialKey.replace('_', ' '), statuses[entry.key].orEmpty()) {
                        Text("Changes only OmniDev's own special-access mode. Reset restores Android's default; it does not guarantee access is denied.", style = MaterialTheme.typography.bodySmall)
                        if (policy.allowShizuku || policy.allowSystemIntegration) Row {
                            TextButton(onClick = { request(entry.key) }, enabled = !busy) { Text("Allow via shell/system") }
                            TextButton(onClick = { request("reset_${entry.key}") }, enabled = !busy) { Text("Restore default") }
                        }
                        if (policy.allowRoot) Row {
                            TextButton(onClick = { request(entry.key, "root") }, enabled = !busy) { Text("Allow via root") }
                            TextButton(onClick = { request("reset_${entry.key}", "root") }, enabled = !busy) { Text("Restore via root") }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { showProtected = !showProtected }) { Text("Other permission declarations · ${protectedDeclarations.size}") }
            }
            if (showProtected) for (name in protectedDeclarations) item(key = "protected:$name") {
                val route = PermissionManagerTool.permissionRoute(context, name)
                AccessRow(name.substringAfterLast('.').replace('_', ' '), "$name\nAccess path: $route", statuses[name].orEmpty(), !busy) { request(name) }
            }
            item {
                AccessSection("Connected apps", "Termux command access: ${statuses["termux"]}") {
                    if (policy.allowAccessibility) OutlinedButton(onClick = { request("termux") }, enabled = !busy) { Text("Authorize Termux commands") }
                    Text("Install Termux and enable allow-external-apps in its properties. OmniLink extensions need discovery, a trusted peer and capability grants in OmniDev settings. File pickers grant only the selected documents. Notifications do not grant access to another app's private database.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                AccessSection("Managed device", "Device Owner: ${statuses["device_owner"]} · Profile Owner: ${statuses["profile_owner"]}") {
                    Text("Owner authority is provisioned by Android on a managed device or work profile. Activating Device Admin alone does not provide it.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh actual access") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AccessSection(title: String, detail: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun AccessRow(title: String, detail: String, status: String, enabled: Boolean, onRequest: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(status.replace('_', ' '), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(top = 12.dp))
                TextButton(onClick = onRequest, enabled = enabled) { Text(if (status == "GRANTED") "Check" else "Set up") }
            }
        }
    }
}
