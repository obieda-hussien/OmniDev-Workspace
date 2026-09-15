package com.omnidev.workspace.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Terminal color palette ──────────────────────────────────────────────────
private val TerminalBg      = Color(0xFF0D1117)
private val TerminalBgLight = Color(0xFF161B22)
private val TerminalGreen   = Color(0xFF3FB950)
private val TerminalCyan    = Color(0xFF79C0FF)
private val TerminalYellow  = Color(0xFFD29922)
private val TerminalRed     = Color(0xFFF85149)
private val TerminalGray    = Color(0xFF8B949E)
private val TerminalWhite   = Color(0xFFE6EDF3)
private val TerminalPurple  = Color(0xFFD2A8FF)
private val TerminalOrange  = Color(0xFFFFA657)

private val TimestampFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

/**
 * "The Glass Brain" — an enhanced, real-time terminal-style console for the ReAct loop.
 *
 * Features:
 * - Tap any row to **expand** it and see full parameters / output
 * - Long-press any row to **copy** its content to clipboard
 * - **Fullscreen toggle** — lifts the height cap so every event is visible
 * - **Stats bar** — iteration count, tool calls, tokens used, elapsed time
 * - **Copy All** button — copies the entire log as plain text
 * - **Iteration dividers** — visual group separators between ReAct iterations
 * - **Duration badge** — how many ms each tool execution took
 * - **Elapsed time** — timestamp offset from first event on every row
 */
@Composable
fun AgentLiveConsole(
    entries: List<AgentConsoleEntry>,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    onOpenBrowser: (() -> Unit)? = null
) {
    var expanded    by remember { mutableStateOf(false) }
    var fullscreen  by remember { mutableStateOf(false) }
    val listState   = rememberLazyListState()
    val clipboard   = LocalClipboardManager.current

    // Running cursor blink animation
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink"
    )

    // Derived stats
    val startTs    = entries.firstOrNull()?.timestamp ?: System.currentTimeMillis()
    val iterations = entries.filterIsInstance<AgentConsoleEntry.ThinkingEntry>().size
    val toolCalls  = entries.filterIsInstance<AgentConsoleEntry.ToolEntry>().size
    val tokenEntry = entries.filterIsInstance<AgentConsoleEntry.TokenEntry>().lastOrNull()
    val errorCount = entries.filterIsInstance<AgentConsoleEntry.ResultEntry>().count { it.isError }
    val elapsedMs  = (entries.lastOrNull()?.timestamp ?: startTs) - startTs
    val hasBrowserEntries = entries.any {
        it is AgentConsoleEntry.ToolEntry && it.toolName == "headless_browser"
    }

    // Auto-scroll to latest entry
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    // Build plain-text export of the full log
    fun buildPlainLog(): String = buildString {
        entries.forEach { entry ->
            val offset = entry.timestamp - startTs
            val prefix = "[+${offset}ms] "
            when (entry) {
                is AgentConsoleEntry.ThinkingEntry     -> appendLine("${prefix}THINK  Reasoning... (iteration ${entry.iteration})")
                is AgentConsoleEntry.DeepThinkingEntry -> appendLine("${prefix}DEEP   ${entry.snippet}")
                is AgentConsoleEntry.ToolEntry         -> appendLine("${prefix}RUN    ${entry.toolName}(${entry.fullParams})")
                is AgentConsoleEntry.ResultEntry       -> appendLine("${prefix}${if (entry.isError) "ERR" else "OK"}     ${entry.toolName}: ${entry.fullOutput}")
                is AgentConsoleEntry.TokenEntry        -> appendLine("${prefix}TOKENS ${entry.totalTokens} used${entry.budget?.let { " / $it budget" } ?: ""}")
                is AgentConsoleEntry.PhaseEntry        -> appendLine("${prefix}PHASE  ${entry.phase}${entry.detail?.let { " — $it" } ?: ""}")
                is AgentConsoleEntry.ReplyEntry        -> appendLine("${prefix}REPLY  Generating final response...")
                is AgentConsoleEntry.ErrorEntry        -> appendLine("${prefix}ERROR  ${entry.message}")
                is AgentConsoleEntry.ContextSummaryEntry -> appendLine("${prefix}COMPR  Context compressed")
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = TerminalBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column {
            // ── Header row ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Code, null, tint = TerminalGreen, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Agent Console", color = TerminalGreen, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                // Running / Done status
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(9.dp), color = TerminalCyan, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(3.dp))
                    Text("RUNNING", color = TerminalCyan, fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp, letterSpacing = 1.sp)
                    // Blinking cursor
                    Text("█", color = TerminalCyan.copy(alpha = cursorAlpha),
                        fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                } else if (entries.any { it is AgentConsoleEntry.ErrorEntry }) {
                    Text("FAILED", color = TerminalRed, fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp, letterSpacing = 1.sp)
                } else if (entries.isNotEmpty()) {
                    Text("DONE", color = TerminalGreen, fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp, letterSpacing = 1.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("${entries.size} events", color = TerminalGray,
                    fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                Spacer(Modifier.width(6.dp))
                // Copy all log button
                if (entries.isNotEmpty()) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(buildPlainLog())) },
                        modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Filled.ContentCopy, "Copy log", tint = TerminalGray,
                            modifier = Modifier.size(13.dp))
                    }
                }
                // Browser viewer eye button — always visible so user can open the live browser
                // viewer anytime; icon is cyan when the agent actively used the browser this run.
                if (onOpenBrowser != null) {
                    IconButton(onClick = onOpenBrowser, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Filled.Visibility, "Open Browser Viewer",
                            tint = if (hasBrowserEntries) TerminalCyan else TerminalGray,
                            modifier = Modifier.size(13.dp))
                    }
                }
                // Fullscreen toggle (only when expanded)
                if (expanded) {
                    IconButton(onClick = { fullscreen = !fullscreen },
                        modifier = Modifier.size(20.dp)) {
                        Icon(
                            if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            "Toggle fullscreen", tint = TerminalGray, modifier = Modifier.size(13.dp))
                    }
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(20.dp)) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        if (expanded) "Collapse" else "Expand", tint = TerminalGray,
                        modifier = Modifier.size(13.dp))
                }
            }

            // ── Stats bar ─────────────────────────────────────────────────────
            AnimatedVisibility(visible = expanded && entries.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TerminalBgLight)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatChip("⟳ ${iterations}", TerminalCyan, "Iterations")
                    StatChip("🛠 ${toolCalls}", TerminalYellow, "Tools")
                    tokenEntry?.let { StatChip("📊 ${it.totalTokens}", TerminalGray, "Tokens") }
                    if (errorCount > 0) StatChip("✗ ${errorCount}", TerminalRed, "Errors")
                    Spacer(Modifier.weight(1f))
                    Text("+${formatElapsed(elapsedMs)}", color = TerminalGray,
                        fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                }
            }

            // ── Log body ──────────────────────────────────────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(150)) + expandVertically(tween(150)),
                exit  = fadeOut(tween(100)) + shrinkVertically(tween(100))
            ) {
                val maxH = if (fullscreen) 600.dp else 240.dp
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = maxH).padding(bottom = 6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(120)) + expandVertically(tween(120))
                        ) {
                            ConsoleLogLine(entry, startTs, clipboard)
                        }
                    }
                }
            }
        }
    }
}

// ── Stat chip ───────────────────────────────────────────────────────────────

@Composable
private fun StatChip(text: String, color: Color, contentDescription: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
    ) {
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
    }
}

// ── Individual log line with tap-to-expand and long-press-to-copy ──────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleLogLine(
    entry: AgentConsoleEntry,
    startTs: Long,
    clipboard: androidx.compose.ui.platform.ClipboardManager
) {
    var expanded by remember { mutableStateOf(false) }
    val elapsedMs = entry.timestamp - startTs

    // Resolve display data
    val (icon, label, labelColor, shortText, fullText, extraDetail) = when (entry) {
        is AgentConsoleEntry.ThinkingEntry ->
            ConsoleRowData("🧠", "THINK", TerminalCyan,
                "Reasoning... (iteration ${entry.iteration})",
                "Reasoning... (iteration ${entry.iteration})", null)

        is AgentConsoleEntry.DeepThinkingEntry ->
            ConsoleRowData("💭", "DEEP ", TerminalPurple,
                entry.snippet.take(80).trimEnd().let { if (entry.snippet.length > 80) "$it…" else it },
                entry.snippet, null)

        is AgentConsoleEntry.ToolEntry ->
            ConsoleRowData("🛠", " RUN ", TerminalYellow,
                "${entry.toolName}(${entry.params.take(55).let { if (entry.params.length > 55) "$it…" else it }})",
                entry.toolName, entry.fullParams)

        is AgentConsoleEntry.ResultEntry -> {
            val durationBadge = if (entry.durationMs > 0) "  [${entry.durationMs}ms]" else ""
            ConsoleRowData(
                if (entry.isError) "✗" else "✓",
                if (entry.isError) " ERR " else "  OK ",
                if (entry.isError) TerminalRed else TerminalGreen,
                "${entry.toolName}: ${entry.snippet.take(80).let { if (entry.snippet.length > 80) "$it…" else it }}$durationBadge",
                "${entry.toolName}$durationBadge", entry.fullOutput)
        }

        is AgentConsoleEntry.TokenEntry ->
            ConsoleRowData("📊", " TOK ", TerminalGray,
                "${entry.totalTokens} tokens${entry.budget?.let { " / $it budget" } ?: ""}",
                "${entry.totalTokens} tokens${entry.budget?.let { b ->
                    val pct = if (b > 0) (entry.totalTokens * 100.0 / b).toInt() else 0
                    " / $b budget ($pct% used)"
                } ?: ""}",
                null)

        is AgentConsoleEntry.PhaseEntry ->
            ConsoleRowData("🧭", "PHASE", TerminalOrange,
                "${entry.phase}${entry.detail?.let { " — ${it.take(60)}" } ?: ""}",
                entry.phase, entry.detail)

        is AgentConsoleEntry.ReplyEntry ->
            ConsoleRowData("💬", "REPLY", TerminalCyan,
                "Generating final response...", "Generating final response...", null)


        is AgentConsoleEntry.ContextSummaryEntry ->
            ConsoleRowData("🗜", "COMPR", TerminalGray,
                "Context compressed (${entry.summary.take(40)}...)",
                "Context compressed", entry.summary)

        is AgentConsoleEntry.ErrorEntry ->
            ConsoleRowData("✗", " ERR ", TerminalRed,
                entry.message.take(100).let { if (entry.message.length > 100) "$it…" else it },
                entry.message, null)
    }

    val copyText = "$icon[$label] $fullText${extraDetail?.let { "\n$it" } ?: ""}"
    val hasDetail = (fullText != shortText) || extraDetail != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(
                onClick    = { if (hasDetail) expanded = !expanded },
                onLongClick = { clipboard.setText(AnnotatedString(copyText)) }
            )
            .background(if (expanded) TerminalBgLight else Color.Transparent)
            .padding(vertical = 2.dp, horizontal = 2.dp)
    ) {
        // ── Main row ─────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            // Elapsed time tag
            Text("+${formatElapsedShort(elapsedMs)}", color = TerminalGray.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                modifier = Modifier.width(46.dp))
            // Bracket + label
            Text("[", color = TerminalGray, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(label, color = labelColor, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
            Text("] ", color = TerminalGray, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            // Message text
            Text(
                text = shortText,
                color = TerminalWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                softWrap = true, overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f)
            )
            // Expand indicator
            if (hasDetail) {
                Text(if (expanded) "▲" else "▼", color = TerminalGray.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                    modifier = Modifier.padding(start = 2.dp))
            }
        }

        // ── Expanded detail panel ─────────────────────────────────────────────
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 46.dp)
            ) {
                HorizontalDivider(color = TerminalGray.copy(alpha = 0.2f), thickness = 0.5.dp)
                Spacer(Modifier.height(4.dp))
                // Full message / name
                if (fullText != shortText) {
                    Text(fullText, color = TerminalWhite.copy(alpha = 0.9f),
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp, softWrap = true)
                    Spacer(Modifier.height(4.dp))
                }
                // Extra detail (full params / full output)
                if (extraDetail != null) {
                    Text("── details ──", color = TerminalGray, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(extraDetail, color = TerminalOrange, fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp, softWrap = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TerminalBg.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(6.dp))
                }
                // Absolute timestamp
                Spacer(Modifier.height(4.dp))
                Text("🕐 ${TimestampFmt.format(Date(entry.timestamp))}  (+${formatElapsed(elapsedMs)})",
                    color = TerminalGray.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp)
            }
        }
    }
}

// ── Helper data holder ──────────────────────────────────────────────────────

private data class ConsoleRowData(
    val icon: String,
    val label: String,
    val labelColor: Color,
    val shortText: String,
    val fullText: String,
    val extraDetail: String?
)

// ── Time formatting helpers ──────────────────────────────────────────────────

private fun formatElapsed(ms: Long): String = when {
    ms < 1_000  -> "${ms}ms"
    ms < 60_000 -> "${"%.1f".format(Locale.US, ms / 1000.0)}s"
    else        -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
}

private fun formatElapsedShort(ms: Long): String = when {
    ms < 1_000  -> "${ms}ms"
    ms < 60_000 -> "${"%.1f".format(Locale.US, ms / 1000.0)}s"
    else        -> "${ms / 60000}m${(ms % 60000) / 1000}s"
}
