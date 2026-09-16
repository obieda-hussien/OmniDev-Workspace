package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.auth.GitHubAccountDeviceFlowManager
import com.omnidev.workspace.data.auth.GitHubAgentAccessStore
import com.omnidev.workspace.data.auth.GitHubDeviceFlowManager
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Adds an explicit GitHub account-control permission surface on top of the existing
 * Integrations & Linked Accounts screen without coupling it to Copilot/Models auth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsLinkedAccountsScreen(
    settingsRepository: SettingsRepository,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val accessStore = remember(context) { GitHubAgentAccessStore(context) }
    var showGitHubAgentSheet by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        IntegrationsScreen(
            settingsRepository = settingsRepository,
            onNavigateBack = onNavigateBack
        )

        ExtendedFloatingActionButton(
            onClick = { showGitHubAgentSheet = true },
            icon = { androidx.compose.material3.Icon(Icons.Filled.AccountTree, contentDescription = null) },
            text = { Text("GitHub Agent Access") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .navigationBarsPadding()
        )
    }

    if (showGitHubAgentSheet) {
        ModalBottomSheet(onDismissRequest = { showGitHubAgentSheet = false }) {
            GitHubAgentAccessPanel(
                store = accessStore,
                onClose = { showGitHubAgentSheet = false }
            )
        }
    }
}

@Composable
private fun GitHubAgentAccessPanel(
    store: GitHubAgentAccessStore,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { store.policy() }

    var enabled by remember { mutableStateOf(initial.enabled) }
    var writeEnabled by remember { mutableStateOf(initial.writeEnabled) }
    var destructiveEnabled by remember { mutableStateOf(initial.destructiveEnabled) }
    var orgAdminEnabled by remember { mutableStateOf(initial.organizationAdminEnabled) }
    var connected by remember { mutableStateOf(initial.connected) }
    var grantedScopes by remember { mutableStateOf(initial.grantedScopes) }

    val bundledClientId = remember {
        GitHubDeviceFlowManager.MODELS_CLIENT_ID
            .takeUnless { it.contains("XXXX", ignoreCase = true) }
            .orEmpty()
    }
    var clientId by remember {
        mutableStateOf(initial.oauthClientId.ifBlank { bundledClientId })
    }

    var authRunning by remember { mutableStateOf(false) }
    var authCode by remember { mutableStateOf<String?>(null) }
    var verificationUri by remember { mutableStateOf(GitHubAccountDeviceFlowManager.VERIFICATION_URL) }
    var status by remember { mutableStateOf<String?>(null) }

    fun persistPolicy() {
        store.setEnabled(enabled)
        store.setWriteEnabled(writeEnabled)
        store.setDestructiveEnabled(destructiveEnabled && writeEnabled)
        store.setOrganizationAdminEnabled(orgAdminEnabled)
        store.setOAuthClientId(clientId)
    }

    val desiredScopes = remember(enabled, writeEnabled, destructiveEnabled, orgAdminEnabled) {
        // Persist first through a temporary sequence so the store computes exactly
        // the scopes represented by the currently visible switches.
        store.setEnabled(enabled)
        store.setWriteEnabled(writeEnabled)
        store.setDestructiveEnabled(destructiveEnabled && writeEnabled)
        store.setOrganizationAdminEnabled(orgAdminEnabled)
        store.scopesForCurrentPolicy()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "GitHub Agent Access",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Separate from Copilot and GitHub Models. Omni can only use this GitHub account-control token after you enable it here. The agent cannot enable, expand, or re-authorize these permissions by itself.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        PermissionSwitchRow(
            title = "Allow agent GitHub access",
            subtitle = "Lets Omni read GitHub through the authorized account token.",
            checked = enabled,
            onCheckedChange = {
                enabled = it
                store.setEnabled(it)
            }
        )

        PermissionSwitchRow(
            title = "Allow write operations",
            subtitle = "Create/update repositories, files, branches, issues, PRs, workflows, gists, projects and other REST resources allowed by GitHub OAuth.",
            checked = writeEnabled,
            enabled = enabled,
            onCheckedChange = {
                writeEnabled = it
                if (!it) destructiveEnabled = false
                store.setWriteEnabled(it)
            }
        )

        PermissionSwitchRow(
            title = "Allow destructive operations",
            subtitle = "Allows HTTP DELETE actions such as deleting repositories, refs, releases, packages, hooks or other GitHub resources. Off by default.",
            checked = destructiveEnabled,
            enabled = enabled && writeEnabled,
            onCheckedChange = {
                destructiveEnabled = it
                store.setDestructiveEnabled(it)
            }
        )

        PermissionSwitchRow(
            title = "Allow organization administration",
            subtitle = "Allows organization-level mutations when your GitHub account and OAuth scopes permit them.",
            checked = orgAdminEnabled,
            enabled = enabled,
            onCheckedChange = {
                orgAdminEnabled = it
                store.setOrganizationAdminEnabled(it)
            }
        )

        HorizontalDivider()

        Text(
            "OAuth Device Flow",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Agent control needs its own Device Flow-enabled GitHub OAuth App Client ID. A Client ID is public metadata, not a client secret. Configure OmniDev's Client ID here once; never paste a client secret into the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = clientId,
            onValueChange = {
                clientId = it.trim()
                store.setOAuthClientId(clientId)
            },
            label = { Text("GitHub OAuth App Client ID") },
            placeholder = { Text("Ov23li… or Iv1.…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            "Requested scopes: $desiredScopes",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (connected) {
            Text(
                "Connected ✓${if (grantedScopes.isNotBlank()) " · Granted: $grantedScopes" else ""}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
            if (grantedScopes.isNotBlank() && grantedScopes.split(' ').toSet() != desiredScopes.split(' ').toSet()) {
                Text(
                    "Permission switches changed. Re-authorize to request the updated GitHub scopes.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (authCode != null) {
            Text("GitHub code: $authCode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(
                onClick = {
                    GitHubAccountDeviceFlowManager.openVerificationPage(context, verificationUri)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open GitHub and authorize")
            }
            Text(
                "Enter the code on GitHub. OmniDev is waiting for your approval.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }

        Button(
            enabled = enabled && !authRunning && clientId.isNotBlank(),
            onClick = {
                persistPolicy()
                authRunning = true
                authCode = null
                status = null
                val scopesToRequest = store.scopesForCurrentPolicy()
                scope.launch {
                    GitHubAccountDeviceFlowManager.startAndPoll(
                        context = context,
                        clientId = clientId,
                        scopes = scopesToRequest
                    ).collect { state ->
                        when (state) {
                            is GitHubAccountDeviceFlowManager.State.AwaitingUserCode -> {
                                authCode = state.userCode
                                verificationUri = state.verificationUri
                                GitHubAccountDeviceFlowManager.openVerificationPage(context, state.verificationUri)
                            }
                            GitHubAccountDeviceFlowManager.State.Polling -> Unit
                            is GitHubAccountDeviceFlowManager.State.Success -> {
                                connected = true
                                grantedScopes = state.grantedScopes
                                authRunning = false
                                authCode = null
                                status = "Authorized. Omni can now use GitHub within the switches you enabled."
                            }
                            is GitHubAccountDeviceFlowManager.State.Error -> {
                                authRunning = false
                                status = "Error: ${state.message}"
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (authRunning) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Spacer(Modifier.height(4.dp))
            }
            Text(if (connected) "Re-authorize GitHub Agent Access" else "Authorize GitHub Agent Access")
        }

        if (connected) {
            OutlinedButton(
                onClick = {
                    store.clearAuthorization()
                    connected = false
                    grantedScopes = ""
                    authCode = null
                    status = "Local GitHub Agent authorization removed."
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect Agent GitHub Account")
            }
        }

        OutlinedButton(
            onClick = {
                persistPolicy()
                onClose()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Done")
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PermissionSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}
