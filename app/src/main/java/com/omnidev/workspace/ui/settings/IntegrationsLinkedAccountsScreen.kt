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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.auth.GitHubAccountDeviceFlowManager
import com.omnidev.workspace.data.auth.GitHubAgentAccessStore
import com.omnidev.workspace.data.auth.GitHubDeviceFlowManager
import com.omnidev.workspace.data.auth.GitHubTokenValidator
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.launch

/**
 * GitHub integration is intentionally split into two capability families:
 *
 * 1. GitHub AI Access — handled by the existing [IntegrationsScreen] for Copilot/Models.
 * 2. GitHub Agent Access — account/repository automation, authorized independently by
 *    OAuth Device Flow or a user-supplied Personal Access Token.
 *
 * AI credentials are never reused for repository/account control.
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
            icon = { Icon(Icons.Filled.AccountTree, contentDescription = null) },
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
    var connectedMethod by remember { mutableStateOf(initial.authMethod) }
    var accountLogin by remember { mutableStateOf(initial.accountLogin) }
    var grantedScopes by remember { mutableStateOf(initial.grantedScopes) }
    var selectedMethod by remember { mutableStateOf(initial.authMethod) }

    val bundledClientId = remember {
        GitHubDeviceFlowManager.MODELS_CLIENT_ID
            .takeUnless { it.contains("XXXX", ignoreCase = true) }
            .orEmpty()
    }
    var clientId by remember {
        mutableStateOf(initial.oauthClientId.ifBlank { bundledClientId })
    }
    var personalAccessToken by remember { mutableStateOf("") }

    var authRunning by remember { mutableStateOf(false) }
    var patValidating by remember { mutableStateOf(false) }
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

    val desiredScopes = remember(writeEnabled, destructiveEnabled, orgAdminEnabled) {
        GitHubAgentAccessStore.buildScopes(
            writeEnabled = writeEnabled,
            destructiveEnabled = destructiveEnabled && writeEnabled,
            organizationAdminEnabled = orgAdminEnabled
        )
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
            "GitHub AI Access (Copilot / Models) stays separate above. This section grants Omni account/repository control only when you explicitly enable it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (connected) {
            Text(
                buildString {
                    append("Connected ✓")
                    accountLogin?.takeIf { it.isNotBlank() }?.let { append(" · @").append(it) }
                    append(" · ").append(connectedMethod.displayName)
                },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        PermissionSwitchRow(
            title = "Allow agent GitHub access",
            subtitle = "Lets Omni use the separately authorized account token for GitHub API operations.",
            checked = enabled,
            onCheckedChange = {
                enabled = it
                store.setEnabled(it)
            }
        )

        PermissionSwitchRow(
            title = "Allow write operations",
            subtitle = "Create/update repositories, files, branches, issues, PRs, workflows, gists, projects, packages, Codespaces and other resources allowed by the connected token.",
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
            subtitle = "Allows DELETE operations. OAuth also requests delete_repo/delete:packages. PAT permissions remain controlled by GitHub. Off by default.",
            checked = destructiveEnabled,
            enabled = enabled && writeEnabled,
            onCheckedChange = {
                destructiveEnabled = it
                store.setDestructiveEnabled(it)
            }
        )

        PermissionSwitchRow(
            title = "Allow advanced account & organization admin",
            subtitle = "Allows organization/account administration only when the connected GitHub token also has those permissions.",
            checked = orgAdminEnabled,
            enabled = enabled,
            onCheckedChange = {
                orgAdminEnabled = it
                store.setOrganizationAdminEnabled(it)
            }
        )

        HorizontalDivider()

        Text(
            "Choose how Agent Access connects",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        AuthMethodRow(
            title = "Connect with GitHub",
            subtitle = "OAuth Device Flow. Best for a polished one-tap linked-account experience; requires OmniDev's Device Flow-enabled GitHub OAuth App Client ID.",
            selected = selectedMethod == GitHubAgentAccessStore.AuthMethod.OAUTH_DEVICE_FLOW,
            onClick = {
                selectedMethod = GitHubAgentAccessStore.AuthMethod.OAUTH_DEVICE_FLOW
                status = null
                authCode = null
            }
        )
        AuthMethodRow(
            title = "Personal Access Token",
            subtitle = "No Client ID required. Supports fine-grained or classic PATs; fine-grained is preferred. Omni verifies the token before replacing any existing connection.",
            selected = selectedMethod == GitHubAgentAccessStore.AuthMethod.PERSONAL_ACCESS_TOKEN,
            onClick = {
                selectedMethod = GitHubAgentAccessStore.AuthMethod.PERSONAL_ACCESS_TOKEN
                status = null
                authCode = null
            }
        )

        when (selectedMethod) {
            GitHubAgentAccessStore.AuthMethod.OAUTH_DEVICE_FLOW -> {
                Text(
                    "OAuth Device Flow",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "A Client ID is public app metadata, not a client secret. Never put a GitHub client secret in OmniDev.",
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
                    "Requested OAuth scopes: $desiredScopes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (connected && connectedMethod == GitHubAgentAccessStore.AuthMethod.OAUTH_DEVICE_FLOW &&
                    grantedScopes.isNotBlank() && grantedScopes.split(' ').toSet() != desiredScopes.split(' ').toSet()
                ) {
                    Text(
                        "Permission switches changed. Re-authorize OAuth to request the updated scopes.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (authCode != null) {
                    Text(
                        "GitHub code: $authCode",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
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

                Button(
                    enabled = enabled && !authRunning && !patValidating && clientId.isNotBlank(),
                    onClick = {
                        persistPolicy()
                        authRunning = true
                        authCode = null
                        status = null
                        val scopesToRequest = desiredScopes
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
                                        connectedMethod = GitHubAgentAccessStore.AuthMethod.OAUTH_DEVICE_FLOW
                                        accountLogin = state.accountLogin
                                        grantedScopes = state.grantedScopes
                                        authRunning = false
                                        authCode = null
                                        status = "Authorized with GitHub OAuth. Omni can use GitHub only within your local switches and granted scopes."
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
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (connectedMethod == GitHubAgentAccessStore.AuthMethod.OAUTH_DEVICE_FLOW && connected) {
                        "Re-authorize with GitHub"
                    } else {
                        "Connect with GitHub"
                    })
                }
            }

            GitHubAgentAccessStore.AuthMethod.PERSONAL_ACCESS_TOKEN -> {
                Text(
                    "Personal Access Token",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Paste a fine-grained or classic PAT. OmniDev sends it only to api.github.com for verification, then stores it encrypted with Android Keystore. Token permissions are chosen on GitHub; OmniDev's switches can further restrict them but never expand them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = personalAccessToken,
                    onValueChange = {
                        personalAccessToken = it.trim()
                        status = null
                    },
                    label = { Text("GitHub Personal Access Token") },
                    placeholder = { Text("github_pat_… or ghp_…") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Fine-grained PAT is recommended. Select only the repositories and GitHub permissions you want OmniDev to be capable of using.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    enabled = enabled && !patValidating && !authRunning && personalAccessToken.isNotBlank(),
                    onClick = {
                        persistPolicy()
                        patValidating = true
                        status = null
                        val candidate = personalAccessToken
                        scope.launch {
                            GitHubTokenValidator.validate(candidate)
                                .onSuccess { verified ->
                                    store.savePersonalAccessToken(
                                        token = candidate,
                                        accountLogin = verified.login,
                                        reportedScopes = verified.scopes
                                    )
                                    connected = true
                                    connectedMethod = GitHubAgentAccessStore.AuthMethod.PERSONAL_ACCESS_TOKEN
                                    accountLogin = verified.login
                                    grantedScopes = verified.scopes
                                    personalAccessToken = ""
                                    status = buildString {
                                        append("Verified and connected PAT for @").append(verified.login).append(".")
                                        if (!verified.tokenExpiration.isNullOrBlank()) {
                                            append(" Token expiration: ").append(verified.tokenExpiration).append(".")
                                        }
                                    }
                                }
                                .onFailure { e ->
                                    status = "Error: ${e.message}"
                                }
                            patValidating = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (patValidating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (connectedMethod == GitHubAgentAccessStore.AuthMethod.PERSONAL_ACCESS_TOKEN && connected) {
                        "Verify & replace PAT"
                    } else {
                        "Verify & connect PAT"
                    })
                }

                if (connected && connectedMethod == GitHubAgentAccessStore.AuthMethod.PERSONAL_ACCESS_TOKEN) {
                    Text(
                        if (grantedScopes.isNotBlank()) {
                            "GitHub reported classic OAuth scopes: $grantedScopes"
                        } else {
                            "Connected token uses GitHub-managed permissions (typical for fine-grained PATs)."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }

        if (connected) {
            OutlinedButton(
                onClick = {
                    store.clearAuthorization()
                    connected = false
                    accountLogin = null
                    grantedScopes = ""
                    authCode = null
                    personalAccessToken = ""
                    status = "Local GitHub Agent authorization removed. GitHub AI credentials were not changed."
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
private fun AuthMethodRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
