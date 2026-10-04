package com.omnidev.workspace.ui.brain

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidev.workspace.data.brain.ToolAwarenessEngine
import com.omnidev.workspace.data.db.entities.SystemKnowledgeEntry
import com.omnidev.workspace.data.db.entities.ToolExecutionEntry
import com.omnidev.workspace.ui.components.SettingsDisclosure
import com.omnidev.workspace.ui.components.SettingsEmptyState
import com.omnidev.workspace.ui.components.SettingsPageTabs
import com.omnidev.workspace.ui.components.SettingsSearchField
import com.omnidev.workspace.ui.motion.OmniIconButton
import java.text.SimpleDateFormat
import java.util.*

/** Focused views of tool activity, learned knowledge and available capabilities. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBrainDashboard(
    viewModel: AgentBrainViewModel,
    onNavigateBack: () -> Unit
) {
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr
    ) { AgentBrainDashboardContent(viewModel, onNavigateBack) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentBrainDashboardContent(viewModel: AgentBrainViewModel, onNavigateBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val tabStates = rememberSaveableStateHolder()
    var actionsExpanded by remember { mutableStateOf(false) }
    var clearKnowledge by remember { mutableStateOf<Boolean?>(null) }

    clearKnowledge?.let { knowledge ->
        AlertDialog(
            onDismissRequest = { clearKnowledge = null },
            title = { Text(if (knowledge) "Clear knowledge?" else "Clear activity log?") },
            text = { Text(if (knowledge)
                "Remove all stored knowledge entries, including archived entries. The agent can learn new knowledge later."
                else "Remove all recorded tool activity. Running tasks will continue and can create new entries.") },
            confirmButton = { TextButton(onClick = {
                viewModel.clearLog(knowledge)
                clearKnowledge = null
            }) { Text("Clear all") } },
            dismissButton = { TextButton(onClick = { clearKnowledge = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent brain", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    OmniIconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    OmniIconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    if (selectedTab == 1 || selectedTab == 2) {
                        Box {
                            OmniIconButton(onClick = { actionsExpanded = true }) { Icon(Icons.Default.MoreVert, "Log actions") }
                            DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (selectedTab == 2) "Clear knowledge" else "Clear activity", color = MaterialTheme.colorScheme.error) },
                                    enabled = !uiState.isClearing,
                                    onClick = { actionsExpanded = false; clearKnowledge = selectedTab == 2 }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            uiState.error?.let { error ->
                Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            SettingsPageTabs(listOf("Overview", "Activity", "Knowledge", "Environment"), selectedTab, {
                actionsExpanded = false
                selectedTab = it
            })
            tabStates.SaveableStateProvider(selectedTab) {
                when (selectedTab) {
                    0 -> PerformanceTab(uiState)
                    1 -> ExecutionLogTab(
                        entries = uiState.recentExecutions,
                        onDeleteEntry = viewModel::deleteExecution
                    )
                    2 -> KnowledgeTab(
                        entries = uiState.recentKnowledge,
                        onDeleteEntry = viewModel::deleteKnowledge,
                        onUpdateEntry = { id, subject, content, confidence ->
                            viewModel.updateKnowledge(id, subject, content, confidence)
                        }
                    )
                    3 -> EnvironmentTab(uiState.awarenessStats)
                }
            }
        }
    }
}

// ───    ──────────────────────────────────────────────────

@Composable
private fun StatsHeaderRow(state: AgentBrainUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrainMetric("Tool executions", state.totalExecutions.toString(), Modifier.weight(1f))
            BrainMetric("Success rate", if (state.totalExecutions == 0) "—" else "${(state.successRate * 100).toInt()}%", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrainMetric("Knowledge entries", state.totalKnowledge.toString(), Modifier.weight(1f))
            BrainMetric("Tools with failures", state.problematicTools.size.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun BrainMetric(label: String, value: String, modifier: Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ───   ────────────────────────────────────────────────────────

@Composable
private fun PerformanceTab(state: AgentBrainUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "summary") { StatsHeaderRow(state) }
        item {
            InfoCard(
                title = "Tool performance",
                content = buildString {
                    appendLine("Best success rate: ${state.bestTool}")
                    appendLine("Lowest success rate: ${state.worstTool}")
                    appendLine("Most used: ${state.mostUsedTool}")
                }
            )
        }

        //
        if (state.problematicTools.isNotEmpty()) {
            item {
                WarningCard(
                    title = "Tools needing attention",
                    items = state.problematicTools
                )
            }
        }

        //
        item {
            LearningProgressCard(
                totalExecutions = state.totalExecutions,
                successRate = state.successRate
            )
        }
    }
}

// ───   ────────────────────────────────────────────────────────

@Composable
private fun ExecutionLogTab(
    entries: List<ToolExecutionEntry>,
    onDeleteEntry: (Long) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf("All") }
    val filtered = remember(entries, query, selectedFilter) {
        entries.filter { entry ->
            (selectedFilter == "All" || entry.success == (selectedFilter == "Succeeded")) &&
                (query.isBlank() || entry.toolName.contains(query.trim(), true) || entry.errorMessage.contains(query.trim(), true) || entry.parametersJson.contains(query.trim(), true))
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSearchField(query, { query = it }, "Search tool activity")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All", "Failed", "Succeeded")) { filter ->
                        FilterChip(selected = selectedFilter == filter, onClick = { selectedFilter = filter }, label = { Text(filter) })
                    }
                }
                Text("${filtered.size} shown · ${entries.size} recent records", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(filtered, key = { it.id }) { entry -> ExecutionEntryCard(entry, onDeleteEntry) }
        if (filtered.isEmpty()) item(key = "empty") {
            SettingsEmptyState(if (entries.isEmpty()) "No tool activity yet" else "No matching activity", "Try another filter or search term.")
        }
    }
}

@Composable
private fun ExecutionEntryCard(entry: ToolExecutionEntry, onDeleteEntry: (Long) -> Unit) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    SettingsDisclosure(
        title = entry.toolName,
        description = "${entry.executionTimeMs} ms · ${formatTimestamp(entry.timestamp)}",
        status = if (entry.success) "Succeeded" else "Failed",
        expanded = expanded, onToggle = { expanded = !expanded }
    ) {
        if (entry.errorMessage.isNotBlank()) Text(entry.errorMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (entry.learningNote.isNotBlank()) Text(entry.learningNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Parameters", style = MaterialTheme.typography.labelLarge)
        SelectionContainer { Text(entry.parametersJson, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        TextButton(onClick = { onDeleteEntry(entry.id) }) { Text("Delete record", color = MaterialTheme.colorScheme.error) }
    }
}

// ───   ───────────────────────────────────────────────────────

@Composable
private fun KnowledgeTab(
    entries: List<SystemKnowledgeEntry>,
    onDeleteEntry: (Long) -> Unit,
    onUpdateEntry: (Long, String, String, Float) -> Unit
) {
    if (entries.isEmpty()) {
        EmptyState(message = "No active knowledge recorded yet.")
        return
    }

    var selectedType by rememberSaveable { mutableStateOf("ALL") }
    var query by rememberSaveable { mutableStateOf("") }
    val filteredEntries = remember(entries, selectedType, query) {
        entries.filter { (selectedType == "ALL" || it.knowledgeType == selectedType) &&
            (query.isBlank() || it.subject.contains(query.trim(), true) || it.content.contains(query.trim(), true)) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            SettingsSearchField(query, { query = it }, "Search knowledge")
            Spacer(Modifier.height(8.dp))
            Text("${filteredEntries.size} shown · ${entries.size} recent active entries",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KnowledgeTypeFilters(
                selectedType = selectedType,
                entries = entries,
                onTypeSelected = { selectedType = it }
            )
        }
        items(filteredEntries, key = { it.id }) { entry ->
            KnowledgeEntryCard(
                entry = entry,
                onDeleteEntry = onDeleteEntry,
                onUpdateEntry = onUpdateEntry
            )
        }
        if (filteredEntries.isEmpty()) item { SettingsEmptyState("No matching knowledge", "Try another category or search term.") }
    }
}

@Composable
private fun KnowledgeEntryCard(
    entry: SystemKnowledgeEntry,
    onDeleteEntry: (Long) -> Unit,
    onUpdateEntry: (Long, String, String, Float) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var editedSubject by remember(entry.id) { mutableStateOf(entry.subject) }
    var editedContent by remember(entry.id) { mutableStateOf(entry.content) }
    var editedConfidence by remember(entry.id) { mutableStateOf(entry.confidence.toString()) }
    var validationError by remember(entry.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(showEditDialog, entry.id, entry.subject, entry.content, entry.confidence) {
        if (showEditDialog) {
            editedSubject = entry.subject
            editedContent = entry.content
            editedConfidence = entry.confidence.toString()
            validationError = null
        }
    }

    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    SettingsDisclosure(
        title = entry.subject,
        description = knowledgeTypeLabel(entry.knowledgeType),
        status = "${(entry.confidence * 100).toInt()}% confidence",
        expanded = expanded, onToggle = { expanded = !expanded }
    ) {
        SelectionContainer { Text(entry.content, style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showEditDialog = true }) { Text("Edit") }
            TextButton(onClick = { onDeleteEntry(entry.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val confidence = editedConfidence.toFloatOrNull()
                        if (!editedSubject.isBlank() &&
                            !editedContent.isBlank() &&
                            confidence != null &&
                            confidence in 0f..1f
                        ) {
                            onUpdateEntry(entry.id, editedSubject, editedContent, confidence)
                            showEditDialog = false
                        } else {
                            validationError = "Confidence must be a number between 0.0 and 1.0."
                        }
                    }
                ) { Text("Save changes") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancel") }
            },
            title = { Text("Edit knowledge") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editedSubject,
                        onValueChange = { editedSubject = it },
                        label = { Text("Title") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editedContent,
                        onValueChange = { editedContent = it },
                        label = { Text("Content") },
                        minLines = 3
                    )
                    OutlinedTextField(
                        value = editedConfidence,
                        onValueChange = { editedConfidence = it },
                        label = { Text("Confidence (0.0 - 1.0)") },
                        singleLine = true
                    )
                    validationError?.let {
                        Text(
                            text = it,
                            color = Color(0xFFF44336),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun KnowledgeTypeFilters(
    selectedType: String,
    entries: List<SystemKnowledgeEntry>,
    onTypeSelected: (String) -> Unit
) {
    val counts = remember(entries) { entries.groupingBy { it.knowledgeType }.eachCount() }
    val types = remember(counts) { listOf("ALL") + counts.keys.sorted() }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(types) { type ->
            FilterChip(
                selected = selectedType == type,
                onClick = { onTypeSelected(type) },
                label = {
                    val count = if (type == "ALL") entries.size else counts[type] ?: 0
                    Text("${knowledgeTypeLabel(type)} ($count)")
                }
            )
        }
    }
}

// ───   ────────────────────────────────────────────────────────

@Composable
private fun EnvironmentTab(stats: ToolAwarenessEngine.AwarenessStats?) {
    if (stats == null) {
        EmptyState(message = "Discovering environment...")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        //
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Available environments",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    stats.environmentCache.forEach { (env, available) ->
                        EnvironmentRow(
                            name = env.replaceFirstChar { it.uppercase() },
                            available = available == "true"
                        )
                    }
                }
            }
        }

        //
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Knowledge distribution (${stats.totalKnowledge} Total)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    stats.byType.entries.sortedByDescending { it.value }.forEach { (type, count) ->
                        KnowledgeTypeRow(type = type, count = count)
                    }
                }
            }
        }

        //
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (stats.isInitialized)
                        Color(0xFF4CAF50).copy(alpha = 0.1f)
                    else
                        Color(0xFFFF9800).copy(alpha = 0.1f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (stats.isInitialized) "✅" else "⏳",
                        fontSize = 22.sp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            if (stats.isInitialized) "Environment scan complete" else "Discovering environment",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            if (stats.isInitialized) "Available capabilities have been recorded; access can change."
                            else "Agent is discovering available capabilities...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ───   ───────────────────────────────────────────────────────

@Composable
private fun InfoCard(title: String, content: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                content.trim(),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun WarningCard(title: String, items: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF44336).copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFF44336))
            Spacer(modifier = Modifier.height(6.dp))
            items.forEach { item ->
                Text(
                    "• $item",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun LearningProgressCard(totalExecutions: Int, successRate: Float) {
    if (totalExecutions == 0) {
        SettingsEmptyState("No execution history yet", "Tool activity will build this overview as Omni works.")
        return
    }
    val progressColor = when {
        successRate > 0.85f -> Color(0xFF4CAF50)
        successRate > 0.6f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = progressColor.copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Execution history", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    text = when {
                        totalExecutions < 20 -> "Getting started"
                        totalExecutions < 100 -> "Building experience"
                        totalExecutions < 500 -> "Experienced"
                        else -> "Extensive history"
                    },
                    fontWeight = FontWeight.Bold,
                    color = progressColor,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { successRate },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = progressColor,
                trackColor = progressColor.copy(alpha = 0.2f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Success Rate: ${(successRate * 100).toInt()}% | Executions: $totalExecutions",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EnvironmentRow(name: String, available: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Badge(
            containerColor = if (available) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
        ) {
            Text(
                if (available) "Available" else "Unavailable",
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun KnowledgeTypeRow(type: String, count: Int) {
    val label = knowledgeTypeLabel(type)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp)
        Text(
            count.toString(),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun knowledgeTypeLabel(type: String): String = when (type) {
    "ALL" -> "📚 All Knowledge"
    ToolAwarenessEngine.TYPE_BEST_PRACTICE -> "💡 Best Practices"
    ToolAwarenessEngine.TYPE_TOOL_CAPABILITY -> "🔧 Tool Capabilities"
    ToolAwarenessEngine.TYPE_TOOL_LIMITATION -> "🚫 Tool Limitations"
    ToolAwarenessEngine.TYPE_SYSTEM_INFO -> "📱 System Info"
    ToolAwarenessEngine.TYPE_ENVIRONMENT -> "🌐 Execution Environments"
    ToolAwarenessEngine.TYPE_WARNING -> "⚠️ Warnings"
    ToolAwarenessEngine.TYPE_PATTERN -> "🔗 Discovered Patterns"
    ToolAwarenessEngine.TYPE_SYSTEM_CAPABILITY -> "🧩 System Capabilities"
    ToolAwarenessEngine.TYPE_TOOL_REQUIREMENT -> "📌 Tool Requirements"
    ToolAwarenessEngine.TYPE_TOOL_DEPENDENCY -> "🔀 Tool Dependencies"
    else -> "ℹ️ $type"
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Psychology, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                message,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
