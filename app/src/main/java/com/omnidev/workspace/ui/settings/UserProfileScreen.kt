package com.omnidev.workspace.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.ui.motion.OmniIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(settingsRepository: SettingsRepository, onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // A non-null pair distinguishes an empty saved profile from storage that has not loaded yet.
    val profile = remember(settingsRepository) {
        combine(settingsRepository.observeUserName(), settingsRepository.observeUserPersona()) { name, persona ->
            name.orEmpty() to persona.orEmpty()
        }
    }
    val saved by profile.collectAsStateWithLifecycle(initialValue = null)
    var name by rememberSaveable { mutableStateOf("") }
    var persona by rememberSaveable { mutableStateOf("") }
    var seeded by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    LaunchedEffect(saved) {
        if (!seeded && saved != null) {
            name = saved!!.first; persona = saved!!.second; seeded = true
        }
    }
    val dirty = seeded && saved != null && (name.trim() != saved!!.first || persona.trim() != saved!!.second)
    fun leave() { if (saving) return; if (dirty) discard = true else onNavigateBack() }
    BackHandler { leave() }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false }, title = { Text("Discard profile changes?") },
        text = { Text("Your saved profile will stay as it is.") },
        confirmButton = { TextButton(onClick = onNavigateBack) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } }
    )
    Scaffold(
        topBar = { TopAppBar(title = { Text("Your profile") }, navigationIcon = {
            OmniIconButton(onClick = { leave() }, enabled = !saving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = {
                        val nameToSave = name.trim(); val personaToSave = persona.trim()
                        saving = true; error = null
                        scope.launch {
                            try {
                                settingsRepository.setUserProfile(nameToSave, personaToSave)
                                saving = false
                                snackbar.showSnackbar("Profile saved")
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = "Could not save your profile. Please try again." }
                            finally { saving = false }
                        }
                    }, enabled = seeded && dirty && !saving, modifier = Modifier.fillMaxWidth()) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(if (!seeded) "Loading profile…" else if (dirty) "Save changes" else "Profile saved")
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Person, null, Modifier.padding(16.dp).size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "Make Omni yours" }, style = MaterialTheme.typography.titleLarge)
                    Text("Choose how your assistant addresses you and adapts its answers.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!seeded) LinearProgressIndicator(Modifier.fillMaxWidth())
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("What should Omni call you?", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(name, { name = it; error = null }, label = { Text("Name or nickname") },
                        singleLine = true, enabled = seeded && !saving, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Used in greetings. You can leave it empty.") })
                }
            }
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("How do you like to work?", style = MaterialTheme.typography.titleMedium)
                    Text("Share your role, preferred tools, language or explanation style.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(persona, { persona = it; error = null }, label = { Text("Preferences · optional") },
                        placeholder = { Text("Android developer. Prefer Kotlin, Arabic explanations and concise examples.") },
                        minLines = 4, enabled = seeded && !saving, modifier = Modifier.fillMaxWidth())
                }
            }
            Text("These details are included in assistant prompts to personalize answers. Add only what you want to share with the selected model provider.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
