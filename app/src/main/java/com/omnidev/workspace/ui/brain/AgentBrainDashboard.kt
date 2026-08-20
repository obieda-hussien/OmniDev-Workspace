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
 * AgentBrainDashboard — System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note Agent
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * System and domain documentation note:
 * 1. System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
 * 2. System and domain documentation note System and domain documentation note System and domain documentation note
 * 3. System and domain documentation note System and domain documentation note System and domain documentation note
 * 4. System and domain documentation note System and domain documentation note System and domain documentation note
 * 5. System and domain documentation note System and domain documentation note System and domain documentation note
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBrainDashboard(
    viewModel: AgentBrainViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // System and domain documentation note System and domain documentation note System and domain documentation note
                        PulsingDot()
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "🧠 Agent Brain",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "System component status System component status System component status System component status",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "System component status")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "System component status")
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
            // ─── System and domain documentation note System and domain documentation note System and domain documentation note ─────────────────────────────
            StatsHeaderRow(uiState)

            // ─── System and domain documentation note ───────────────────────────────────────────────
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("📊 System component status") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("📔 System component status") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("🧠 System component status") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("🌐 System component status") }
                )
            }

            // ─── System and domain documentation note System and domain documentation note ─────────────────────────────────────────
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

// ─── System and domain documentation note System and domain documentation note System and domain documentation note ──────────────────────────────────────────────────

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
                label = "System component status",
                color = Color(0xFF2196F3)
            )
        }
        item {
            MiniStatCard(
                icon = "✅",
                value = "${(state.successRate * 100).toInt()}%",
                label = "System component status",
                color = if (state.successRate > 0.8f) Color(0xFF4CAF50) else Color(0xFFFF9800)
            )
        }
        item {
            MiniStatCard(
                icon = "🧠",
                value = state.totalKnowledge.toString(),
                label = "System component status",
                color = Color(0xFF9C27B0)
            )
        }
        item {
            MiniStatCard(
                icon = "🔧",
                value = state.sessionToolCount.toString(),
                label = "System component status/System component status",
                color = Color(0xFF00BCD4)
            )
        }
        if (state.problematicTools.isNotEmpty()) {
            item {
                MiniStatCard(
                    icon = "⚠️",
                    value = state.problematicTools.size.toString(),
                    label = "System component status",
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

// ─── System and domain documentation note System and domain documentation note ────────────────────────────────────────────────────────

@Composable
private fun PerformanceTab(state: AgentBrainUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // System and domain documentation note System and domain documentation note System and domain documentation note
        item {
            InfoCard(
                title = "🏆 System component status System component status",
                content = buildString {
                    appendLine("🥇 System component status: ${state.bestTool}")
                    appendLine("🥉 System component status: ${state.worstTool}")
                    appendLine("🔥 System component status System component status: ${state.mostUsedTool}")
                }
            )
        }

        // System and domain documentation note System and domain documentation note
        if (state.problematicTools.isNotEmpty()) {
            item {
                WarningCard(
                    title = "⚠️ System component status System component status System component status",
                    items = state.problematicTools
                )
            }
        }

        // System and domain documentation note System and domain documentation note
        item {
            LearningProgressCard(
                totalExecutions = state.totalExecutions,
                successRate = state.successRate
            )
        }
    }
}

// ─── System and domain documentation note System and domain documentation note ────────────────────────────────────────────────────────

@Composable
private fun ExecutionLogTab(
    entries: List<ToolExecutionEntry>,
    onDeleteEntry: (Long) -> Unit
) {
    if (entries.isEmpty()) {
        EmptyState(message = "System component status System component status System component status System component status System component status")
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
            // System and domain documentation note System and domain documentation note
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
                        contentDescription = "System component status System component status",
                        tint = Color(0xFFF44336),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// ─── System and domain documentation note System and domain documentation note ───────────────────────────────────────────────────────

@Composable
private fun KnowledgeTab(
    entries: List<SystemKnowledgeEntry>,
    onDeleteEntry: (Long) -> Unit,
    onUpdateEntry: (Long, String, String, Float) -> Unit
) {
    if (entries.isEmpty()) {
        EmptyState(message = "System component status System component status System component status Agent System component status System component status")
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
                            contentDescription = "System component status System component status",
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
                            contentDescription = "System component status System component status",
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
                            validationError = "System component status System component status System component status: System component status System component status System component status System component status System component status 0.0 System component status 1.0"
                        }
                    }
                ) { Text("System component status") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("System component status") }
            },
            title = { Text("System component status System component status") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editedSubject,
                        onValueChange = { editedSubject = it },
                        label = { Text("System component status") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editedContent,
                        onValueChange = { editedContent = it },
                        label = { Text("System component status") },
                        minLines = 3
                    )
                    OutlinedTextField(
                        value = editedConfidence,
                        onValueChange = { editedConfidence = it },
                        label = { Text("System component status (0.0 - 1.0)") },
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

// ─── System and domain documentation note System and domain documentation note ────────────────────────────────────────────────────────

@Composable
private fun EnvironmentTab(stats: ToolAwarenessEngine.AwarenessStats?) {
    if (stats == null) {
        EmptyState(message = "System component status System component status System component status...")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // System and domain documentation note System and domain documentation note System and domain documentation note
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "🌐 System component status System component status",
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

        // System and domain documentation note System and domain documentation note
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "📊 System component status System component status System component status (${stats.totalKnowledge} System component status)",
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

        // System and domain documentation note System and domain documentation note
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
                            if (stats.isInitialized) "System component status System component status System component status" else "System component status System component status System component status System component status",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            if (stats.isInitialized) "System component status Agent System component status System component status System component status"
                            else "System component status Agent System component status System component status System component status...",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

// ─── System and domain documentation note System and domain documentation note ───────────────────────────────────────────────────────

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
                Text("🎯 System component status System component status", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    text = when {
                        totalExecutions < 20 -> "System component status"
                        totalExecutions < 100 -> "System component status"
                        totalExecutions < 500 -> "System component status"
                        else -> "System component status"
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
                text = "System and domain documentation note System and domain documentation note: ${(successRate * 100).toInt()}% | System and domain documentation note: $totalExecutions",
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
                if (available) "System component status" else "System component status System component status",
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
    "ALL" -> "📚 System component status System component status"
    ToolAwarenessEngine.TYPE_BEST_PRACTICE -> "💡 System component status System component status"
    ToolAwarenessEngine.TYPE_TOOL_CAPABILITY -> "🔧 System component status System component status"
    ToolAwarenessEngine.TYPE_TOOL_LIMITATION -> "🚫 System component status System component status"
    ToolAwarenessEngine.TYPE_SYSTEM_INFO -> "📱 System component status System component status"
    ToolAwarenessEngine.TYPE_ENVIRONMENT -> "🌐 System component status System component status"
    ToolAwarenessEngine.TYPE_WARNING -> "⚠️ System component status"
    ToolAwarenessEngine.TYPE_PATTERN -> "🔗 System component status System component status"
    ToolAwarenessEngine.TYPE_SYSTEM_CAPABILITY -> "🧩 System component status System component status"
    ToolAwarenessEngine.TYPE_TOOL_REQUIREMENT -> "📌 System component status System component status"
    ToolAwarenessEngine.TYPE_TOOL_DEPENDENCY -> "🔀 System component status System component status"
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
