package com.omnidev.workspace.ui.brain

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnidev.workspace.data.brain.ToolAwarenessEngine
import com.omnidev.workspace.data.db.entities.SystemKnowledgeEntry
import com.omnidev.workspace.data.db.entities.ToolExecutionEntry
import java.text.SimpleDateFormat
import java.util.*

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * AgentBrainDashboard —     Agent
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * :
 * 1.
 * 2.
 * 3.
 * 4.
 * 5.
 */
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
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        //
                        PulsingDot()
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "🧠 Agent Brain",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "Tool activity, learned knowledge and environment status",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
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
            // ───    ─────────────────────────────
            StatsHeaderRow(uiState)

            // ───  ───────────────────────────────────────────────
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("📊 Overview") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("📔 Activity") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("🧠 Knowledge") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("🌐 Environment") }
                )
            }

            // ───   ─────────────────────────────────────────
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

// ───    ──────────────────────────────────────────────────

@Composable
private fun StatsHeaderRow(state: AgentBrainUiState) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            MiniStatCard(
                icon = "⚡",
                value = state.totalExecutions.toString(),
                label = "Tool executions",
                color = Color(0xFF2196F3)
            )
        }
        item {
            MiniStatCard(
                icon = "✅",
                value = "${(state.successRate * 100).toInt()}%",
                label = "Success rate",
                color = if (state.successRate > 0.8f) Color(0xFF4CAF50) else Color(0xFFFF9800)
            )
        }
        item {
            MiniStatCard(
                icon = "🧠",
                value = state.totalKnowledge.toString(),
                label = "Knowledge entries",
                color = Color(0xFF9C27B0)
            )
        }
        item {
            MiniStatCard(
                icon = "🔧",
                value = state.recentExecutions.size.toString(),
                label = "Recent events shown",
                color = Color(0xFF00BCD4)
            )
        }
        if (state.problematicTools.isNotEmpty()) {
            item {
                MiniStatCard(
                    icon = "⚠️",
                    value = state.problematicTools.size.toString(),
                    label = "Tools with failures",
                    color = Color(0xFFF44336)
                )
            }
        }
    }
}

@Composable
private fun MiniStatCard(
    icon: String,
    value: String,
    label: String,
    color: Color
) {
    Card(
        modifier = Modifier.width(90.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.12f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(icon, fontSize = 20.sp)
            Text(
                text = value,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = color
            )
            Text(
                text = label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

// ───   ────────────────────────────────────────────────────────

@Composable
private fun PerformanceTab(state: AgentBrainUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        //
        item {
            InfoCard(
                title = "🏆 Tool performance",
                content = buildString {
                    appendLine("🥇 Best success rate: ${state.bestTool}")
                    appendLine("🥉 Lowest success rate: ${state.worstTool}")
                    appendLine("🔥 Most used: ${state.mostUsedTool}")
                }
            )
        }

        //
        if (state.problematicTools.isNotEmpty()) {
            item {
                WarningCard(
                    title = "⚠️ Tools needing attention",
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
    if (entries.isEmpty()) {
        EmptyState(message = "No tool executions recorded yet.")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(entries, key = { it.id }) { entry ->
            ExecutionEntryCard(entry = entry, onDeleteEntry = onDeleteEntry)
        }
    }
}

@Composable
private fun ExecutionEntryCard(
    entry: ToolExecutionEntry,
    onDeleteEntry: (Long) -> Unit
) {
    val bgColor = if (entry.success)
        Color(0xFF4CAF50).copy(alpha = 0.08f)
    else
        Color(0xFFF44336).copy(alpha = 0.08f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            //
            Text(
                text = if (entry.success) "✅" else "❌",
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.toolName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = entry.parametersJson.take(180),
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.errorMessage.isNotBlank()) {
                    Text(
                        text = entry.errorMessage,
                        fontSize = 11.sp,
                        color = Color(0xFFF44336),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (entry.learningNote.isNotBlank()) {
                    Text(
                        text = entry.learningNote,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${entry.executionTimeMs}ms",
                    fontSize = 11.sp,
                    color = when {
                        entry.executionTimeMs < 500 -> Color(0xFF4CAF50)
                        entry.executionTimeMs < 3000 -> Color(0xFFFF9800)
                        else -> Color(0xFFF44336)
                    }
                )
                Text(
                    text = formatTimestamp(entry.timestamp),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                IconButton(
                    onClick = { onDeleteEntry(entry.id) },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete execution",
                        tint = Color(0xFFF44336),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
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

    var selectedType by remember { mutableStateOf("ALL") }
    val filteredEntries = remember(entries, selectedType) {
        if (selectedType == "ALL") entries else entries.filter { it.knowledgeType == selectedType }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Text("Showing the latest ${entries.size} active entries. Category counts below refer to this view.",
                style = MaterialTheme.typography.labelSmall)
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

    val (icon, color) = when (entry.knowledgeType) {
        ToolAwarenessEngine.TYPE_BEST_PRACTICE -> "💡" to Color(0xFF4CAF50)
        ToolAwarenessEngine.TYPE_WARNING -> "⚠️" to Color(0xFFFF9800)
        ToolAwarenessEngine.TYPE_TOOL_LIMITATION -> "🚫" to Color(0xFFF44336)
        ToolAwarenessEngine.TYPE_ENVIRONMENT -> "🌐" to Color(0xFF2196F3)
        ToolAwarenessEngine.TYPE_PATTERN -> "🔗" to Color(0xFF9C27B0)
        ToolAwarenessEngine.TYPE_SYSTEM_INFO -> "📱" to Color(0xFF00BCD4)
        else -> "ℹ️" to Color(0xFF607D8B)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.06f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(icon, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = entry.subject,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = color
                    )
                    Text(
                        text = "${(entry.confidence * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = color.copy(alpha = 0.7f)
                    )
                }
                Text(
                    text = entry.content,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit knowledge",
                            tint = color,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    IconButton(
                        onClick = { onDeleteEntry(entry.id) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete knowledge",
                            tint = Color(0xFFF44336),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            fontSize = 11.sp
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
    val types = listOf("ALL") + entries.map { it.knowledgeType }.distinct().sorted()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(types) { type ->
            FilterChip(
                selected = selectedType == type,
                onClick = { onTypeSelected(type) },
                label = {
                    val count = if (type == "ALL") entries.size else entries.count { it.knowledgeType == type }
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
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        //
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "🌐 Available Environments",
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "📊 Knowledge Base Distribution (${stats.totalKnowledge} Total)",
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
                            if (stats.isInitialized) "Environment scan complete" else "System Discovering",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            if (stats.isInitialized) "Available capabilities have been recorded; access can change."
                            else "Agent is discovering available capabilities...",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
    Card(modifier = Modifier.fillMaxWidth()) {
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
                Text("🎯 Execution history", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
        Text(name, fontSize = 13.sp)
        Badge(
            containerColor = if (available) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
        ) {
            Text(
                if (available) "Available" else "Unavailable",
                fontSize = 10.sp,
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
            Text("🧠", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                message,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(Color(0xFF4CAF50).copy(alpha = alpha))
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
