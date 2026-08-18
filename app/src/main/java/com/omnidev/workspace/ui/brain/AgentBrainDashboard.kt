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
 * AgentBrainDashboard — [Localized] [Localized] [Localized] [Localized] Agent
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * [Localized]:
 * 1. [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
 * 2. [Localized] [Localized] [Localized]
 * 3. [Localized] [Localized] [Localized]
 * 4. [Localized] [Localized] [Localized]
 * 5. [Localized] [Localized] [Localized]
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
                        // [Localized] [Localized] [Localized]
                        PulsingDot()
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "🧠 Agent Brain",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "[Localized] [Localized] [Localized] [Localized]",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "[Localized]")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "[Localized]")
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
            // ─── [Localized] [Localized] [Localized] ─────────────────────────────
            StatsHeaderRow(uiState)

            // ─── [Localized] ───────────────────────────────────────────────
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("📊 [Localized]") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("📔 [Localized]") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("🧠 [Localized]") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("🌐 [Localized]") }
                )
            }

            // ─── [Localized] [Localized] ─────────────────────────────────────────
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

// ─── [Localized] [Localized] [Localized] ──────────────────────────────────────────────────

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
                label = "[Localized]",
                color = Color(0xFF2196F3)
            )
        }
        item {
            MiniStatCard(
                icon = "✅",
                value = "${(state.successRate * 100).toInt()}%",
                label = "[Localized]",
                color = if (state.successRate > 0.8f) Color(0xFF4CAF50) else Color(0xFFFF9800)
            )
        }
        item {
            MiniStatCard(
                icon = "🧠",
                value = state.totalKnowledge.toString(),
                label = "[Localized]",
                color = Color(0xFF9C27B0)
            )
        }
        item {
            MiniStatCard(
                icon = "🔧",
                value = state.sessionToolCount.toString(),
                label = "[Localized]/[Localized]",
                color = Color(0xFF00BCD4)
            )
        }
        if (state.problematicTools.isNotEmpty()) {
            item {
                MiniStatCard(
                    icon = "⚠️",
                    value = state.problematicTools.size.toString(),
                    label = "[Localized]",
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

// ─── [Localized] [Localized] ────────────────────────────────────────────────────────

@Composable
private fun PerformanceTab(state: AgentBrainUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // [Localized] [Localized] [Localized]
        item {
            InfoCard(
                title = "🏆 [Localized] [Localized]",
                content = buildString {
                    appendLine("🥇 [Localized]: ${state.bestTool}")
                    appendLine("🥉 [Localized]: ${state.worstTool}")
                    appendLine("🔥 [Localized] [Localized]: ${state.mostUsedTool}")
                }
            )
        }

        // [Localized] [Localized]
        if (state.problematicTools.isNotEmpty()) {
            item {
                WarningCard(
                    title = "⚠️ [Localized] [Localized] [Localized]",
                    items = state.problematicTools
                )
            }
        }

        // [Localized] [Localized]
        item {
            LearningProgressCard(
                totalExecutions = state.totalExecutions,
                successRate = state.successRate
            )
        }
    }
}

// ─── [Localized] [Localized] ────────────────────────────────────────────────────────

@Composable
private fun ExecutionLogTab(
    entries: List<ToolExecutionEntry>,
    onDeleteEntry: (Long) -> Unit
) {
    if (entries.isEmpty()) {
        EmptyState(message = "[Localized] [Localized] [Localized] [Localized] [Localized]")
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
            // [Localized] [Localized]
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
                        contentDescription = "[Localized] [Localized]",
                        tint = Color(0xFFF44336),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// ─── [Localized] [Localized] ───────────────────────────────────────────────────────

@Composable
private fun KnowledgeTab(
    entries: List<SystemKnowledgeEntry>,
    onDeleteEntry: (Long) -> Unit,
    onUpdateEntry: (Long, String, String, Float) -> Unit
) {
    if (entries.isEmpty()) {
        EmptyState(message = "[Localized] [Localized] [Localized] Agent [Localized] [Localized]")
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
                            contentDescription = "[Localized] [Localized]",
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
                            contentDescription = "[Localized] [Localized]",
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
                            validationError = "[Localized] [Localized] [Localized]: [Localized] [Localized] [Localized] [Localized] [Localized] 0.0 [Localized] 1.0"
                        }
                    }
                ) { Text("[Localized]") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("[Localized]") }
            },
            title = { Text("[Localized] [Localized]") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editedSubject,
                        onValueChange = { editedSubject = it },
                        label = { Text("[Localized]") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editedContent,
                        onValueChange = { editedContent = it },
                        label = { Text("[Localized]") },
                        minLines = 3
                    )
                    OutlinedTextField(
                        value = editedConfidence,
                        onValueChange = { editedConfidence = it },
                        label = { Text("[Localized] (0.0 - 1.0)") },
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

// ─── [Localized] [Localized] ────────────────────────────────────────────────────────

@Composable
private fun EnvironmentTab(stats: ToolAwarenessEngine.AwarenessStats?) {
    if (stats == null) {
        EmptyState(message = "[Localized] [Localized] [Localized]...")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // [Localized] [Localized] [Localized]
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "🌐 [Localized] [Localized]",
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

        // [Localized] [Localized]
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "📊 [Localized] [Localized] [Localized] (${stats.totalKnowledge} [Localized])",
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

        // [Localized] [Localized]
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
                            if (stats.isInitialized) "[Localized] [Localized] [Localized]" else "[Localized] [Localized] [Localized] [Localized]",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            if (stats.isInitialized) "[Localized] Agent [Localized] [Localized] [Localized]"
                            else "[Localized] Agent [Localized] [Localized] [Localized]...",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

// ─── [Localized] [Localized] ───────────────────────────────────────────────────────

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
                Text("🎯 [Localized] [Localized]", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    text = when {
                        totalExecutions < 20 -> "[Localized]"
                        totalExecutions < 100 -> "[Localized]"
                        totalExecutions < 500 -> "[Localized]"
                        else -> "[Localized]"
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
                text = "[Localized] [Localized]: ${(successRate * 100).toInt()}% | [Localized]: $totalExecutions",
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
                if (available) "[Localized]" else "[Localized] [Localized]",
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
    "ALL" -> "📚 [Localized] [Localized]"
    ToolAwarenessEngine.TYPE_BEST_PRACTICE -> "💡 [Localized] [Localized]"
    ToolAwarenessEngine.TYPE_TOOL_CAPABILITY -> "🔧 [Localized] [Localized]"
    ToolAwarenessEngine.TYPE_TOOL_LIMITATION -> "🚫 [Localized] [Localized]"
    ToolAwarenessEngine.TYPE_SYSTEM_INFO -> "📱 [Localized] [Localized]"
    ToolAwarenessEngine.TYPE_ENVIRONMENT -> "🌐 [Localized] [Localized]"
    ToolAwarenessEngine.TYPE_WARNING -> "⚠️ [Localized]"
    ToolAwarenessEngine.TYPE_PATTERN -> "🔗 [Localized] [Localized]"
    ToolAwarenessEngine.TYPE_SYSTEM_CAPABILITY -> "🧩 [Localized] [Localized]"
    ToolAwarenessEngine.TYPE_TOOL_REQUIREMENT -> "📌 [Localized] [Localized]"
    ToolAwarenessEngine.TYPE_TOOL_DEPENDENCY -> "🔀 [Localized] [Localized]"
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
