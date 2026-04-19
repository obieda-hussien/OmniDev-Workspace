package com.omnidev.workspace.ui.analytics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.repository.AnalyticsStats
import com.omnidev.workspace.data.repository.DailyUsage
import com.omnidev.workspace.data.repository.ModelStats
import com.omnidev.workspace.data.repository.ProviderStats
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

// ── Provider palette ──────────────────────────────────────────────────────────

private val OtherColor = Color(0xFF6B7280)

/**
 * Distinct, accessible color per provider — used for pie slices, bars and badges.
 * Falls back to [OtherColor] for any future provider we haven't themed yet.
 */
private fun ModelProvider.color(): Color = when (this) {
    ModelProvider.ANTHROPIC -> Color(0xFFC96442)        // Claude orange
    ModelProvider.OPENAI -> Color(0xFF10A37F)           // OpenAI teal
    ModelProvider.GEMINI -> Color(0xFF4285F4)           // Google blue
    ModelProvider.XAI -> Color(0xFF111827)              // xAI near-black
    ModelProvider.DEEPSEEK -> Color(0xFF4D6BFE)         // DeepSeek indigo
    ModelProvider.MISTRAL -> Color(0xFFFF7000)          // Mistral orange
    ModelProvider.GROQ -> Color(0xFFF55036)             // Groq red
    ModelProvider.CEREBRAS -> Color(0xFF9333EA)         // Cerebras purple
    ModelProvider.COHERE -> Color(0xFF39594D)           // Cohere green
    ModelProvider.TOGETHER -> Color(0xFF0F766E)         // Together teal
    ModelProvider.FIREWORKS -> Color(0xFFEF4444)        // Fireworks red
    ModelProvider.PERPLEXITY -> Color(0xFF1FB8CD)       // Perplexity turquoise
    ModelProvider.NVIDIA -> Color(0xFF76B900)           // NVIDIA green
    ModelProvider.GITHUB_COPILOT -> Color(0xFF24292F)   // GitHub dark
    ModelProvider.GITHUB_MODELS -> Color(0xFF57606A)    // GitHub gray
    ModelProvider.OPEN_ROUTER -> Color(0xFF6366F1)      // OpenRouter indigo
    ModelProvider.LOCAL_EDGE -> Color(0xFF6B7280)       // Gray
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
                ) { Text("Clear") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearDialog = false }) { Text("Cancel") }
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
            EmptyState(modifier = Modifier.fillMaxSize().padding(padding))
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

                // ── 1. Overview (global metrics) ─────────────────────────────
                SectionTitle("Overview")
                OverviewSection(currentStats)

                // ── 2. Provider Distribution (pie + legend) ──────────────────
                if (currentStats.providerBreakdown.isNotEmpty()) {
                    SectionTitle("Provider Distribution")
                    ProviderDistributionSection(currentStats.providerBreakdown)
                }

                // ── 3. Per-provider deep dive (expandable cards) ─────────────
                if (currentStats.providerBreakdown.isNotEmpty()) {
                    SectionTitle("Providers — Deep Dive")
                    ProvidersDeepDiveSection(currentStats.providerBreakdown)
                }

                // ── 4. Top Models — Tokens / Cost / Requests ─────────────────
                if (currentStats.tokensByModel.isNotEmpty()) {
                    SectionTitle("Top Models")
                    TopModelsSection(currentStats)
                }

                // ── 5. Cost Leaderboard ──────────────────────────────────────
                if (currentStats.totalCostUsd > 0.0) {
                    SectionTitle("Cost Leaderboard")
                    CostLeaderboardSection(currentStats)
                }

                // ── 6. Daily Usage Timeline ──────────────────────────────────
                if (currentStats.dailyTimeline.size >= 2) {
                    SectionTitle("Daily Usage (last ${currentStats.dailyTimeline.size} days)")
                    DailyTimelineSection(currentStats.dailyTimeline)
                }

                // ── 7. Top Tools ─────────────────────────────────────────────
                if (currentStats.toolUsageCount.isNotEmpty()) {
                    SectionTitle("Top Tools Used")
                    TopToolsSection(currentStats)
                }

                // ── 8. Actions ───────────────────────────────────────────────
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
                    ) { Text("🗑  Clear Analytics") }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "📊", style = MaterialTheme.typography.displayMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No analytics data yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Start chatting with the Agent to see\ntoken usage, costs and provider insights.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

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
    // Row 1 — tokens & cost
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCard("Total Tokens", formatLargeNumber(stats.totalTokens), Modifier.weight(1f))
        SummaryCard("Total Cost", formatCostUsd(stats.totalCostUsd), Modifier.weight(1f))
    }
    // Row 2 — requests & providers
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCard("API Requests", formatLargeNumber(stats.totalRequests), Modifier.weight(1f))
        SummaryCard("Providers", stats.providerBreakdown.size.toString(), Modifier.weight(1f))
        SummaryCard("Models", stats.tokensByModel.size.toString(), Modifier.weight(1f))
    }
    // Row 3 — agent / swarm runs
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCard("Agent Runs", stats.totalAgentRuns.toString(), Modifier.weight(1f))
        SummaryCard("Swarm Runs", stats.totalSwarmRuns.toString(), Modifier.weight(1f))
        SummaryCard(
            label = "Error Rate",
            value = formatPercent(stats.errorRate),
            modifier = Modifier.weight(1f),
            accent = if (stats.errorRate > 0.05) MaterialTheme.colorScheme.error else null
        )
    }
    // Row 4 — input / output split & avg tokens
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCard("Input Tokens", formatLargeNumber(stats.totalInputTokens), Modifier.weight(1f))
        SummaryCard("Output Tokens", formatLargeNumber(stats.totalOutputTokens), Modifier.weight(1f))
        SummaryCard(
            "Avg / Req",
            formatLargeNumber(stats.averageTokensPerRequest.toLong()),
            Modifier.weight(1f)
        )
    }
    // Optional meta row — tracking window
    if (stats.firstRecordedAt > 0L && stats.lastRecordedAt > 0L) {
        Text(
            text = "Tracking since ${formatShortDate(stats.firstRecordedAt)} • " +
                "last activity ${formatRelativeTime(stats.lastRecordedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent ?: MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Section 2: Provider Distribution (pie chart + legend) ─────────────────────

@Composable
private fun ProviderDistributionSection(providers: List<ProviderStats>) {
    val totalTokens = providers.sumOf { it.totalTokens }.coerceAtLeast(1L)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Pie chart
            Canvas(
                modifier = Modifier.size(140.dp)
            ) {
                var startAngle = -90f
                providers.forEach { ps ->
                    val sweep = 360f * ps.totalTokens.toFloat() / totalTokens.toFloat()
                    drawArc(
                        color = ps.provider.color(),
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = true,
                        topLeft = Offset.Zero,
                        size = size
                    )
                    startAngle += sweep
                }
                // Donut hole for readability
                val holeSize = size.minDimension * 0.5f
                drawArc(
                    color = Color.White,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = true,
                    topLeft = Offset(
                        (size.width - holeSize) / 2f,
                        (size.height - holeSize) / 2f
                    ),
                    size = Size(holeSize, holeSize)
                )
            }

            // Legend
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                providers.take(6).forEach { ps ->
                    val share = ps.totalTokens.toDouble() / totalTokens.toDouble()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(ps.provider.color())
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = ps.provider.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatPercent(share),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = ps.provider.color()
                        )
                    }
                }
                if (providers.size > 6) {
                    Text(
                        text = "+ ${providers.size - 6} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Section 3: Per-provider deep dive ────────────────────────────────────────

@Composable
private fun ProvidersDeepDiveSection(providers: List<ProviderStats>) {
    // Per-provider expansion state keyed by provider name; first one expanded by default.
    val expandedMap = remember {
        mutableStateMapOf<String, Boolean>().apply {
            providers.firstOrNull()?.let { put(it.provider.name, true) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        providers.forEach { ps ->
            val key = ps.provider.name
            val expanded = expandedMap[key] == true
            ProviderCard(
                stats = ps,
                expanded = expanded,
                onToggle = { expandedMap[key] = !expanded }
            )
        }
    }
}

@Composable
private fun ProviderCard(
    stats: ProviderStats,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val providerColor = stats.provider.color()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // Header — always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Colored dot
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(providerColor)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stats.provider.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${stats.modelCount} model${if (stats.modelCount == 1) "" else "s"} • " +
                            "${formatLargeNumber(stats.requestCount)} req • " +
                            "${formatLargeNumber(stats.totalTokens)} tok",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = formatCostUsd(stats.costUsd),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = providerColor
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Body — visible only when expanded
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Top model spotlight
                    stats.topModel?.let { (topId, topStats) ->
                        Text(
                            text = "🏆  Top model",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = topId.shortenModelId(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = providerColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${formatLargeNumber(topStats.totalTokens)} tok • " +
                                "${formatLargeNumber(topStats.requestCount)} req • " +
                                formatCostUsd(topStats.costUsd),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Metric grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MiniMetric("Input", formatLargeNumber(stats.inputTokens), Modifier.weight(1f))
                        MiniMetric("Output", formatLargeNumber(stats.outputTokens), Modifier.weight(1f))
                        MiniMetric(
                            "Avg / Req",
                            formatLargeNumber(stats.averageTokensPerRequest.toLong()),
                            Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MiniMetric(
                            "Avg Latency",
                            formatLatency(stats.averageLatencyMs.toLong()),
                            Modifier.weight(1f)
                        )
                        MiniMetric(
                            "Errors",
                            "${stats.errorCount} (${formatPercent(stats.errorRate)})",
                            Modifier.weight(1f),
                            accent = if (stats.errorRate > 0.05) MaterialTheme.colorScheme.error else null
                        )
                    }

                    // Per-model breakdown table
                    if (stats.models.size > 1 || (stats.topModel != null && stats.models.size == 1)) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "📚  All models used",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        ModelTable(stats.models, providerColor, totalTokensForBar = stats.totalTokens)
                    }

                    // Usage window meta
                    if (stats.firstUsedAt > 0L && stats.lastUsedAt > 0L) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "First used ${formatShortDate(stats.firstUsedAt)} • " +
                                "Last used ${formatRelativeTime(stats.lastUsedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ModelTable(
    models: List<Pair<String, ModelStats>>,
    accentColor: Color,
    totalTokensForBar: Long
) {
    val denom = max(totalTokensForBar, 1L)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        models.forEach { (modelId, modelStats) ->
            val fraction = modelStats.totalTokens.toFloat() / denom.toFloat()
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = modelId.shortenModelId(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatLargeNumber(modelStats.totalTokens) + " tok",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatCostUsd(modelStats.costUsd),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                ) {
                    drawRect(
                        color = Color.LightGray.copy(alpha = 0.25f),
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height)
                    )
                    drawRect(
                        color = accentColor,
                        topLeft = Offset.Zero,
                        size = Size(size.width * fraction.coerceIn(0f, 1f), size.height)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "${formatLargeNumber(modelStats.inputTokens)} in / " +
                            "${formatLargeNumber(modelStats.outputTokens)} out",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${modelStats.requestCount} req",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (modelStats.averageLatencyMs > 0.0) {
                        Text(
                            text = "~${formatLatency(modelStats.averageLatencyMs.toLong())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (modelStats.errorCount > 0) {
                        Text(
                            text = "⚠ ${modelStats.errorCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

// ── Section 4: Top Models (tabbed: tokens / cost / requests) ─────────────────

@Composable
private fun TopModelsSection(stats: AnalyticsStats) {
    var mode by remember { mutableStateOf(TopModelsMode.TOKENS) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Chip row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TopModelsMode.values().forEach { m ->
                    FilterChipSimple(
                        label = m.label,
                        selected = mode == m,
                        onSelected = { mode = m },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            val data = when (mode) {
                TopModelsMode.TOKENS -> stats.topModelsByTokens
                TopModelsMode.COST -> stats.topModelsByCost
                TopModelsMode.REQUESTS -> stats.topModelsByRequests
            }.take(8)

            val maxValue = when (mode) {
                TopModelsMode.TOKENS -> data.maxOfOrNull { it.second.totalTokens }?.coerceAtLeast(1L)?.toFloat() ?: 1f
                TopModelsMode.COST -> data.maxOfOrNull { it.second.costUsd }?.coerceAtLeast(0.0001)?.toFloat() ?: 1f
                TopModelsMode.REQUESTS -> data.maxOfOrNull { it.second.requestCount }?.coerceAtLeast(1L)?.toFloat() ?: 1f
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                data.forEachIndexed { index, (modelId, modelStats) ->
                    val raw = when (mode) {
                        TopModelsMode.TOKENS -> modelStats.totalTokens.toFloat()
                        TopModelsMode.COST -> modelStats.costUsd.toFloat()
                        TopModelsMode.REQUESTS -> modelStats.requestCount.toFloat()
                    }
                    val display = when (mode) {
                        TopModelsMode.TOKENS -> formatLargeNumber(modelStats.totalTokens)
                        TopModelsMode.COST -> formatCostUsd(modelStats.costUsd)
                        TopModelsMode.REQUESTS -> formatLargeNumber(modelStats.requestCount)
                    }
                    val provider = modelStats.provider
                    val barColor = provider?.color() ?: OtherColor
                    val fraction = (raw / maxValue).coerceIn(0f, 1f)

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(22.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(barColor)
                            )
                            Text(
                                text = modelId.shortenModelId(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = display,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = barColor
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        ) {
                            drawRect(
                                color = Color.LightGray.copy(alpha = 0.25f),
                                topLeft = Offset.Zero,
                                size = Size(size.width, size.height)
                            )
                            drawRect(
                                color = barColor,
                                topLeft = Offset.Zero,
                                size = Size(size.width * fraction, size.height)
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class TopModelsMode(val label: String) {
    TOKENS("Tokens"),
    COST("Cost"),
    REQUESTS("Requests")
}

@Composable
private fun FilterChipSimple(
    label: String,
    selected: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable { onSelected() }
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = fg,
            maxLines = 1
        )
    }
}

// ── Section 5: Cost Leaderboard ──────────────────────────────────────────────

@Composable
private fun CostLeaderboardSection(stats: AnalyticsStats) {
    val sorted = stats.topModelsByCost.take(10)
    if (sorted.isEmpty() || sorted.all { it.second.costUsd <= 0.0 }) return

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
                        cost = formatCostUsd(modelStats.costUsd),
                        accentColor = modelStats.provider?.color()
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
    isHeader: Boolean = false,
    accentColor: Color? = null
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
        if (!isHeader && accentColor != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accentColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
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
            textAlign = TextAlign.End
        )
        Text(
            text = outputTokens,
            style = textStyle,
            fontWeight = fontWeight,
            color = color,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
        Text(
            text = cost,
            style = textStyle,
            fontWeight = if (isHeader) fontWeight else FontWeight.Bold,
            color = if (isHeader) color else (accentColor ?: MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1.3f),
            textAlign = TextAlign.End
        )
    }
}

// ── Section 6: Daily Timeline ────────────────────────────────────────────────

@Composable
private fun DailyTimelineSection(days: List<DailyUsage>) {
    val maxTokens = days.maxOf { it.totalTokens }.coerceAtLeast(1L)
    val totalCostForDays = days.sumOf { it.costUsd }
    val totalReqForDays = days.sumOf { it.requestCount }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Summary row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${days.size} days • ${formatLargeNumber(totalReqForDays)} req • " +
                        formatCostUsd(totalCostForDays),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "peak ${formatLargeNumber(maxTokens)} tok/day",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            // Bar chart
            val accent = MaterialTheme.colorScheme.primary
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                val barCount = days.size
                if (barCount == 0) return@Canvas
                val gap = 3f
                val availableWidth = size.width - gap * (barCount - 1)
                val barWidth = availableWidth / barCount
                days.forEachIndexed { idx, d ->
                    val heightFrac = d.totalTokens.toFloat() / maxTokens.toFloat()
                    val barHeight = size.height * heightFrac
                    val x = idx * (barWidth + gap)
                    val y = size.height - barHeight
                    // Bar
                    drawRect(
                        color = accent,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            // Date range
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = days.first().dateKey,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = days.last().dateKey,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── Section 7: Top Tools ──────────────────────────────────────────────────────

@Composable
private fun TopToolsSection(stats: AnalyticsStats) {
    val sorted = stats.toolUsageCount.entries
        .sortedByDescending { it.value }
        .take(10)

    val maxCount = sorted.firstOrNull()?.value?.let { max(it, 1) } ?: 1
    val totalToolCalls = stats.toolUsageCount.values.sum()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "${formatLargeNumber(totalToolCalls.toLong())} total tool invocations across " +
                    "${stats.toolUsageCount.size} tools",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            sorted.forEachIndexed { index, (toolName, count) ->
                val fraction = count.toFloat() / maxCount.toFloat()
                val share = if (totalToolCalls > 0) count.toDouble() / totalToolCalls.toDouble() else 0.0
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
                        modifier = Modifier.width(22.dp)
                    )
                    Text(
                        text = toolName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
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
                            color = Color(0xFF10A37F),
                            topLeft = Offset.Zero,
                            size = Size(size.width * fraction, size.height)
                        )
                    }
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(36.dp).wrapContentHeight(),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = formatPercent(share),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(40.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

// ── Formatting helpers ────────────────────────────────────────────────────────

private fun formatCostUsd(amount: Double): String {
    // For very small amounts show more decimals; for larger amounts use standard
    // 2-decimal currency formatting to avoid noisy '$1.2345' looking output.
    return when {
        amount == 0.0 -> "—"
        amount < 0.01 -> String.format(Locale.US, "$%.4f", amount)
        amount < 1.0 -> String.format(Locale.US, "$%.3f", amount)
        else -> NumberFormat.getCurrencyInstance(Locale.US).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(amount)
    }
}

private fun formatLargeNumber(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1_000_000_000.0)
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun formatPercent(fraction: Double): String = when {
    fraction <= 0.0 -> "0%"
    fraction < 0.001 -> "<0.1%"
    fraction < 0.01 -> String.format(Locale.US, "%.1f%%", fraction * 100)
    else -> String.format(Locale.US, "%.0f%%", fraction * 100)
}

private fun formatLatency(ms: Long): String = when {
    ms <= 0L -> "—"
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1_000.0)
    else -> String.format(Locale.US, "%.1fmin", ms / 60_000.0)
}

private fun formatShortDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    val fmt = SimpleDateFormat("MMM d, yyyy", Locale.US)
    return fmt.format(Date(epochMillis))
}

private fun formatRelativeTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    val diff = System.currentTimeMillis() - epochMillis
    if (diff < 0) return "just now"
    val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        hours < 24 -> "${hours}h ago"
        days < 30 -> "${days}d ago"
        else -> formatShortDate(epochMillis)
    }
}

private fun String.shortenModelId(): String {
    return this
        .removePrefix("anthropic/")
        .removePrefix("openai/")
        .removePrefix("google/")
        .removePrefix("meta/")
        .removePrefix("mistralai/")
        .removePrefix("deepseek/")
        .take(32)
}
