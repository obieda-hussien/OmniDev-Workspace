package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.launch

/** Persona chip with a label and the prompt text it auto-fills. */
private data class PersonaChip(val label: String, val prompt: String)

private val CHAT_PERSONAS = listOf(
    PersonaChip("Default", "You are a helpful coding assistant. Answer questions directly without using tools."),
    PersonaChip("Android Expert", "You are a senior Android engineer. Provide idiomatic Kotlin/Compose answers with best practices and API references."),
    PersonaChip("Strict Refactorer", "You are a code quality expert. Focus on reducing complexity, improving readability, and enforcing SOLID principles without changing behavior."),
    PersonaChip("Creative Brainstormer", "You are a creative software architect. Think outside the box and propose multiple creative approaches to every problem."),
    PersonaChip("Minimal Responder", "Answer in the fewest words possible. Code only — no prose unless explicitly asked.")
)

private val AGENT_PERSONAS = listOf(
    PersonaChip("Default", "You are an autonomous AI agent. Use the provided tools to analyze and edit the workspace."),
    PersonaChip("Careful Engineer", "You are a meticulous engineer. Verify every file before editing. Leave detailed TODO comments for anything uncertain."),
    PersonaChip("Speed Executor", "Complete the task as fast as possible with minimum tool calls. Skip verification steps unless correctness is critical."),
    PersonaChip("Security Auditor", "Analyze the codebase for security vulnerabilities. Report every finding with severity rating (Critical/High/Medium/Low) and remediation steps.")
)

private val ORCHESTRATOR_PERSONAS = listOf(
    PersonaChip("Default", "You are an elite autonomous coding agent powered by a frontier reasoning model. Plan deeply before acting."),
    PersonaChip("TDD Planner", "Break every task into test-first sub-tasks. Ensure each sub-task has a failing test before implementing the feature."),
    PersonaChip("Minimal Scope", "Accomplish the user's request with the smallest possible set of file changes. Avoid scope creep.")
)

/**
 * System Prompt Studio — lets users customize the AI's persona for each execution mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptEditorScreen(
    settingsRepository: SettingsRepository,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Chat", "Agent", "Orchestrator")

    // Observe saved prompts
    val savedChatPrompt by settingsRepository
        .observeCustomPrompt(SettingsRepository.PromptRole.CHAT).collectAsState(initial = null)
    val savedAgentPrompt by settingsRepository
        .observeCustomPrompt(SettingsRepository.PromptRole.AGENT).collectAsState(initial = null)
    val savedOrchestratorPrompt by settingsRepository
        .observeCustomPrompt(SettingsRepository.PromptRole.ORCHESTRATOR).collectAsState(initial = null)

    var chatText by remember { mutableStateOf("") }
    var agentText by remember { mutableStateOf("") }
    var orchestratorText by remember { mutableStateOf("") }

    // Initialise text fields once saved data loads
    LaunchedEffect(savedChatPrompt) { if (chatText.isEmpty()) chatText = savedChatPrompt ?: "" }
    LaunchedEffect(savedAgentPrompt) { if (agentText.isEmpty()) agentText = savedAgentPrompt ?: "" }
    LaunchedEffect(savedOrchestratorPrompt) { if (orchestratorText.isEmpty()) orchestratorText = savedOrchestratorPrompt ?: "" }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            LargeTopAppBar(
                title = { Text("System Prompt Studio") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label) }
                    )
                }
            }

            val (currentText, setCurrentText, personas, promptRole, defaultHint) = when (selectedTab) {
                0 -> Tuple5(chatText, { v: String -> chatText = v }, CHAT_PERSONAS,
                    SettingsRepository.PromptRole.CHAT,
                    "E.g. You are a helpful coding assistant…")
                1 -> Tuple5(agentText, { v: String -> agentText = v }, AGENT_PERSONAS,
                    SettingsRepository.PromptRole.AGENT,
                    "E.g. You are an autonomous AI agent…")
                else -> Tuple5(orchestratorText, { v: String -> orchestratorText = v }, ORCHESTRATOR_PERSONAS,
                    SettingsRepository.PromptRole.ORCHESTRATOR,
                    "E.g. You are an elite autonomous coding agent…")
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Persona Templates",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))

            // Horizontally scrollable persona chip row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                personas.forEach { persona ->
                    FilterChip(
                        selected = currentText == persona.prompt,
                        onClick = { setCurrentText(persona.prompt) },
                        label = { Text(persona.label) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Custom Prompt",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = currentText,
                onValueChange = setCurrentText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(220.dp),
                placeholder = { Text(defaultHint) },
                label = { Text("System Prompt") },
                minLines = 6
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { setCurrentText("") }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("Reset to Default")
                }
                TextButton(onClick = {
                    scope.launch {
                        settingsRepository.setCustomPrompt(
                            promptRole,
                            currentText.takeIf { it.isNotBlank() }
                        )
                        snackbarHost.showSnackbar("System prompt saved")
                    }
                }) { Text("Save") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// Tiny helper to destructure when() branches uniformly
private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

private operator fun <A, B, C, D, E> Tuple5<A, B, C, D, E>.component1() = a
private operator fun <A, B, C, D, E> Tuple5<A, B, C, D, E>.component2() = b
private operator fun <A, B, C, D, E> Tuple5<A, B, C, D, E>.component3() = c
private operator fun <A, B, C, D, E> Tuple5<A, B, C, D, E>.component4() = d
private operator fun <A, B, C, D, E> Tuple5<A, B, C, D, E>.component5() = e
