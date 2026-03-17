package com.omnidev.workspace.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.repository.AnalyticsStats
import com.omnidev.workspace.data.repository.ModelStats
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max

// ── Color constants ───────────────────────────────────────────────────────────

private val AnthropicColor = Color(0xFF7C3AED)   // purple
private val OpenAIColor = Color(0xFF16A34A)        // green
private val GeminiColor = Color(0xFF2563EB)        // blue
private val OtherColor = Color(0xFF6B7280)         // gray

private fun providerColor(modelId: String): Color = when {
    modelId.contains("claude", ignoreCase = true) ||
    modelId.contains("anthropic", ignoreCase = true) -> AnthropicColor
    modelId.contains("gpt", ignoreCase = true) ||
    modelId.contains("openai", ignoreCase = true) ||
    modelId.contains("o1", ignoreCase = true) ||
    modelId.contains("o3", ignoreCase = true) -> OpenAIColor
    modelId.contains("gemini", ignoreCase = true) ||
    modelId.contains("google", ignoreCase = true) -> GeminiColor
    else -> OtherColor
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsDashboardScreen(
    viewModel: AnalyticsDashboardViewModel,
    onNavigateBack: () -> Unit
) {
    val stats by viewModel.stats.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Analytics?") },
            text = { Text("All usage statistics will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearStats()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "📊 Analytics Dashboard",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        val currentStats = stats
        if (currentStats == null || isStatsEmpty(currentStats)) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No analytics data yet.\nStart chatting to see statistics!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // ── Section 1: Overview ───────────────────────────────────────
                SectionTitle("Overview")
                OverviewSection(currentStats)

                // ── Section 2: Token Usage by Model ──────────────────────────
                SectionTitle("Token Usage by Model")
                TokenBarChartSection(currentStats)

                // ── Section 3: Cost Breakdown ─────────────────────────────────
                SectionTitle("Cost Breakdown")
                CostBreakdownSection(currentStats)

                // ── Section 4: Top Tools Used ─────────────────────────────────
                if (currentStats.toolUsageCount.isNotEmpty()) {
                    SectionTitle("Top Tools Used")
                    TopToolsSection(currentStats)
                }

                // ── Section 5: Actions ────────────────────────────────────────
                SectionTitle("Actions")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = { showClearDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("🗑  Clear Analytics")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun isStatsEmpty(stats: AnalyticsStats): Boolean =
    stats.totalInputTokens == 0L &&
    stats.totalOutputTokens == 0L &&
    stats.totalAgentRuns == 0 &&
    stats.totalSwarmRuns == 0 &&
    stats.toolUsageCount.isEmpty()

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

// ── Section 1: Overview ───────────────────────────────────────────────────────

@Composable
private fun OverviewSection(stats: AnalyticsStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCard(
            label = "Total Tokens",
            value = formatLargeNumber(stats.totalInputTokens + stats.totalOutputTokens),
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            label = "Total Cost",
            value = formatCostUsd(stats.totalCostUsd),
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCard(
            label = "Agent Runs",
            value = stats.totalAgentRuns.toString(),
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            label = "Swarm Runs",
            value = stats.totalSwarmRuns.toString(),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Section 2: Token Bar Chart ────────────────────────────────────────────────

@Composable
private fun TokenBarChartSection(stats: AnalyticsStats) {
    if (stats.tokensByModel.isEmpty()) {
        Text(
            text = "No model usage data yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val top5 = stats.tokensByModel.entries
        .sortedByDescending { it.value.inputTokens + it.value.outputTokens }
        .take(5)

    val maxTokens = top5.maxOf { it.value.inputTokens + it.value.outputTokens }
        .let { max(it, 1L) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            top5.forEach { (modelId, modelStats) ->
                val totalTokens = modelStats.inputTokens + modelStats.outputTokens
                val fraction = totalTokens.toFloat() / maxTokens.toFloat()
                val barColor = providerColor(modelId)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = modelId.shortenModelId(),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatLargeNumber(totalTokens),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        // Background track
                        drawRect(
                            color = Color.LightGray.copy(alpha = 0.3f),
                            topLeft = Offset.Zero,
                            size = Size(size.width, size.height)
                        )
                        // Filled bar
                        drawRect(
                            color = barColor,
                            topLeft = Offset.Zero,
                            size = Size(size.width * fraction, size.height)
                        )
                    }
                }
            }

            // Legend
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LegendDot(color = AnthropicColor, label = "Anthropic")
                LegendDot(color = OpenAIColor, label = "OpenAI")
                LegendDot(color = GeminiColor, label = "Gemini")
                LegendDot(color = OtherColor, label = "Other")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Section 3: Cost Breakdown ─────────────────────────────────────────────────

@Composable
private fun CostBreakdownSection(stats: AnalyticsStats) {
    if (stats.tokensByModel.isEmpty()) {
        Text(
            text = "No cost data yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val sorted = stats.tokensByModel.entries.sortedByDescending { it.value.costUsd }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // Header
            CostBreakdownRow(
                modelName = "Model",
                inputTokens = "Input",
                outputTokens = "Output",
                cost = "Cost (USD)",
                isHeader = true
            )
            HorizontalDivider()
            sorted.forEachIndexed { index, (modelId, modelStats) ->
                val bgColor = if (index % 2 == 0) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                } else {
                    Color.Transparent
                }
                Box(modifier = Modifier.background(bgColor)) {
                    CostBreakdownRow(
                        modelName = modelId.shortenModelId(),
                        inputTokens = formatLargeNumber(modelStats.inputTokens),
                        outputTokens = formatLargeNumber(modelStats.outputTokens),
                        cost = formatCostUsd(modelStats.costUsd)
                    )
                }
            }
        }
    }
}

@Composable
private fun CostBreakdownRow(
    modelName: String,
    inputTokens: String,
    outputTokens: String,
    cost: String,
    isHeader: Boolean = false
) {
    val textStyle = if (isHeader) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall
    val fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal
    val color = if (isHeader) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = modelName,
            style = textStyle,
            fontWeight = fontWeight,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f)
        )
        Text(
            text = inputTokens,
            style = textStyle,
            fontWeight = fontWeight,
            color = color,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
        Text(
            text = outputTokens,
            style = textStyle,
            fontWeight = fontWeight,
            color = color,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
        Text(
            text = cost,
            style = textStyle,
            fontWeight = fontWeight,
            color = if (isHeader) color else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1.2f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

// ── Section 4: Top Tools ──────────────────────────────────────────────────────

@Composable
private fun TopToolsSection(stats: AnalyticsStats) {
    val sorted = stats.toolUsageCount.entries
        .sortedByDescending { it.value }
        .take(10)

    val maxCount = sorted.firstOrNull()?.value?.let { max(it, 1) } ?: 1

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            sorted.forEachIndexed { index, (toolName, count) ->
                val fraction = count.toFloat() / maxCount.toFloat()
                val bgColor = if (index % 2 == 0) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                } else {
                    Color.Transparent
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(bgColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(20.dp)
                    )
                    Text(
                        text = toolName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // Percentage bar
                    Canvas(
                        modifier = Modifier
                            .width(60.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        drawRect(
                            color = Color.LightGray.copy(alpha = 0.3f),
                            topLeft = Offset.Zero,
                            size = Size(size.width, size.height)
                        )
                        drawRect(
                            color = OpenAIColor,
                            topLeft = Offset.Zero,
                            size = Size(size.width * fraction, size.height)
                        )
                    }
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
        }
    }
}

// ── Formatting helpers ────────────────────────────────────────────────────────

private fun formatCostUsd(amount: Double): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.US).apply {
        minimumFractionDigits = 4
        maximumFractionDigits = 4
    }
    return formatter.format(amount)
}

private fun formatLargeNumber(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun String.shortenModelId(): String {
    // Remove common prefixes and keep the model name readable
    return this
        .removePrefix("anthropic/")
        .removePrefix("openai/")
        .removePrefix("google/")
        .removePrefix("meta/")
        .take(30)
}
