package com.omnidev.workspace.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Terminal color palette ──────────────────────────────────────────────────
private val TerminalBg = Color(0xFF0D1117)
private val TerminalGreen = Color(0xFF3FB950)
private val TerminalCyan = Color(0xFF79C0FF)
private val TerminalYellow = Color(0xFFD29922)
private val TerminalRed = Color(0xFFF85149)
private val TerminalGray = Color(0xFF8B949E)
private val TerminalWhite = Color(0xFFE6EDF3)
private val TerminalPurple = Color(0xFFD2A8FF)

/**
 * "The Glass Brain" — a real-time, terminal-style streaming console that renders
 * [AgentConsoleEntry] events as the ReAct loop progresses.
 *
 * Displays a collapsible dark panel above the chat messages, showing each step of
 * the agent's reasoning, tool execution, observation, and token usage cycle.
 *
 * @param entries The ordered list of console entries emitted by the agent pipeline.
 * @param isRunning Whether the agent is currently executing (controls spinner + status badge).
 * @param modifier Optional layout modifier.
 */
@Composable
fun AgentLiveConsole(
    entries: List<AgentConsoleEntry>,
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // Auto-scroll to the latest entry as new ones arrive
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TerminalBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column {
            // ── Header / toggle bar ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Code,
                    contentDescription = null,
                    tint = TerminalGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Agent Console",
                    color = TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(Modifier.width(8.dp))
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        color = TerminalCyan,
                        strokeWidth = 1.5.dp
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "RUNNING",
                        color = TerminalCyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )
                } else if (entries.isNotEmpty()) {
                    Text(
                        text = "DONE",
                        color = TerminalGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${entries.size} events",
                    color = TerminalGray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse console" else "Expand console",
                    tint = TerminalGray,
                    modifier = Modifier.size(16.dp)
                )
            }

            // ── Scrollable log body ───────────────────────────────────────────
            AnimatedVisibility(visible = expanded) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .padding(bottom = 8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(entries) { entry ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(150)) + expandVertically(tween(150))
                        ) {
                            ConsoleLogLine(entry)
                        }
                    }
                }
            }
        }
    }
}

// ── Individual log line ─────────────────────────────────────────────────────

@Composable
private fun ConsoleLogLine(entry: AgentConsoleEntry) {
    when (entry) {
        is AgentConsoleEntry.ThinkingEntry ->
            ConsoleRow("🧠", "THINK", TerminalCyan, "Reasoning... (iteration ${entry.iteration})")

        is AgentConsoleEntry.DeepThinkingEntry ->
            ConsoleRow("💭", "DEEP ", TerminalPurple,
                entry.snippet.take(120).let { if (it.length == 120) "$it…" else it })

        is AgentConsoleEntry.ToolEntry ->
            ConsoleRow("🛠", " RUN ", TerminalYellow,
                "${entry.toolName}(${entry.params.take(60).let { if (it.length == 60) "$it…" else it }})")

        is AgentConsoleEntry.ResultEntry ->
            ConsoleRow(
                prefix = if (entry.isError) "✗" else "✓",
                label = if (entry.isError) " ERR " else "  OK ",
                labelColor = if (entry.isError) TerminalRed else TerminalGreen,
                text = "${entry.toolName}: ${entry.snippet.take(100).let { if (it.length == 100) "$it…" else it }}"
            )

        is AgentConsoleEntry.TokenEntry ->
            ConsoleRow("📊", " TOK ", TerminalGray,
                "${entry.totalTokens} tokens used${entry.budget?.let { " / $it budget" } ?: ""}")

        is AgentConsoleEntry.ReplyEntry ->
            ConsoleRow("💬", "REPLY", TerminalCyan, "Generating final response...")

        is AgentConsoleEntry.ErrorEntry ->
            ConsoleRow("✗", " ERR ", TerminalRed, entry.message.take(120))
    }
}

@Composable
private fun ConsoleRow(
    prefix: String,
    label: String,
    labelColor: Color,
    text: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "$prefix [",
            color = TerminalGray,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = label,
            color = labelColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(40.dp)
        )
        Text(
            text = "] ",
            color = TerminalGray,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Text(
            text = text,
            color = TerminalWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            softWrap = true,
            modifier = Modifier.weight(1f)
        )
    }
}
