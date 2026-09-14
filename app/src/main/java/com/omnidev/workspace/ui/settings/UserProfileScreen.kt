package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.launch

/**
 * User Profile screen — lets the user set their display name and a short persona bio.
 *
 * **Name** is used by the AI assistant for personalised greetings
 * (e.g. "   Ahmed   ").
 *
 * **Persona** is injected into the AI's system prompt so the model can tailor
 * advice, code style, and explanations to this specific person
 * (e.g. "Senior Android developer, prefers Kotlin, builds indie apps in Arabic").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    settingsRepository: SettingsRepository,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Observe persisted values and seed local draft state once on first load
    val persistedName by settingsRepository.observeUserName().collectAsState(initial = null)
    val persistedPersona by settingsRepository.observeUserPersona().collectAsState(initial = null)

    var name by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("") }
    // Flags to ensure we only seed from storage once — after that, local edits take precedence
    var nameSeeded by remember { mutableStateOf(false) }
    var personaSeeded by remember { mutableStateOf(false) }

    // Seed draft from persisted values only on first non-null arrival
    LaunchedEffect(persistedName) {
        if (!nameSeeded && persistedName != null) {
            name = persistedName!!
            nameSeeded = true
        }
    }
    LaunchedEffect(persistedPersona) {
        if (!personaSeeded && persistedPersona != null) {
            persona = persistedPersona!!
            personaSeeded = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Profile") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // ── Avatar placeholder ──
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // ── Subtitle ──
            Text(
                text = "Tell the assistant how you prefer to work.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            )

            // ── Name field ──
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Your name") },
                placeholder = { Text("Name or nickname") },
                singleLine = true,
                supportingText = {
                    Text("The assistant will use your name in greetings.")
                }
            )

            // ── Persona field ──
            OutlinedTextField(
                value = persona,
                onValueChange = { persona = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                label = { Text("Your Personal Data (Optional)") },
                placeholder = {
                    Text(
                        "e.g., Professional Android Developer, prefers Kotlin." +
                        "\nThis helps the AI understand your style."
                    )
                },
                supportingText = {
                    Text("Used to personalize your conversations.")
                }
            )

            // ── Save button ──
            Button(
                onClick = {
                    scope.launch {
                        settingsRepository.setUserName(name.trim().ifBlank { null })
                        settingsRepository.setUserPersona(persona.trim().ifBlank { null })
                        snackbarHostState.showSnackbar("✅ Saved!")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save profile")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
