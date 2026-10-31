package com.omnidev.workspace.ui.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omnidev.workspace.data.review.*
import com.omnidev.workspace.ui.theme.OmniError
import com.omnidev.workspace.ui.theme.OmniPrimary
import com.omnidev.workspace.ui.theme.OmniSecondary
import com.omnidev.workspace.ui.theme.OmniSuccess
import com.omnidev.workspace.ui.theme.OmniWarning
import kotlinx.coroutines.launch

private val AddBg = Color(0xFF22C55E).copy(alpha = .14f)
private val DelBg = Color(0xFFEF4444).copy(alpha = .15f)
private val AddFg = Color(0xFF86EFAC)
private val DelFg = Color(0xFFFCA5A5)
private val Gutter = Color(0xFF0B0B16)
private val CodeBg = Color(0xFF0F0F1C)

/**
 * Compact bar shown above the composer while the agent has unreviewed edits.
 * Replaces the per-edit blocking dialog: the agent keeps working, the user reviews when ready.
 */
@Composable
fun PendingChangesPill(
    pending: List<TrackedChange>,
    actionCount: Int,
    onReview: () -> Unit,
    onKeepAll: () -> Unit,
    onRevertAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (pending.isEmpty() && actionCount == 0) return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(OmniPrimary.copy(.7f), OmniSecondary.copy(.5f)))),
        tonalElevation = 3.dp
    ) {
        Row(
            Modifier.clickable(onClick = onReview).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Difference, null, tint = OmniPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (pending.isEmpty()) "$actionCount action${if (actionCount == 1) "" else "s"} ran" else "${pending.size} file${if (pending.size == 1) "" else "s"} changed", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(if (pending.isEmpty()) "Tap to see activity" else "Tap to review changes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pending.isNotEmpty()) {
                IconButton(onClick = onRevertAll) { Icon(Icons.Filled.Undo, "Revert all", tint = OmniError) }
                IconButton(onClick = onKeepAll) { Icon(Icons.Filled.DoneAll, "Keep all", tint = OmniSuccess) }
            } else Spacer(Modifier.width(10.dp))
        }
    }
}

private enum class ReviewTab { PENDING, HISTORY, ACTIVITY }

@Composable
fun ChangeReviewDialog(
    ledger: SessionChangeLedger,
    sessionKey: Long,
    onDismiss: () -> Unit
) {
    val all by ledger.changes.collectAsState()
    val mine = remember(all, sessionKey) { all.filter { it.sessionKey == sessionKey } }
    val pending = remember(mine) { mine.filter { it.status == ChangeStatus.PENDING } }
    val allActions by ledger.actions.collectAsState()
    val actions = remember(allActions, sessionKey) { allActions.filter { it.sessionKey == sessionKey }.sortedByDescending { it.at } }
    val history = remember(mine) { mine.filter { it.status != ChangeStatus.PENDING }.sortedByDescending { it.resolvedAt } }
    var tab by remember { mutableStateOf(ReviewTab.PENDING) }
    var openId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Drop selections for rows that were resolved or removed.
    LaunchedEffect(pending) { selected = selected.filter { id -> pending.any { it.id == id } }.toSet() }

    fun resolve(ids: Set<String>, revert: Boolean) {
        scope.launch {
            val r = if (revert) ledger.revert(ids) else ledger.keep(ids)
            if (r.errors.isNotEmpty()) snackbar.showSnackbar(r.errors.first())
            else snackbar.showSnackbar(if (revert) "Reverted ${r.succeeded} file(s)" else "Kept ${r.succeeded} file(s)")
        }
    }

    Dialog(onDismissRequest = { if (openId != null) openId = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(OmniPrimary.copy(alpha = .10f), Color.Transparent), endY = 700f))) {
                AnimatedContent(
                    targetState = openId,
                    transitionSpec = {
                        if (targetState != null) (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 } + fadeOut())
                        else (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it / 6 } + fadeOut())
                    }, label = "review-pages"
                ) { id ->
                    val change = id?.let { target -> mine.firstOrNull { it.id == target } }
                    if (change != null) {
                        DiffPage(ledger, change, onBack = { openId = null },
                            onKeep = { resolve(setOf(change.id), false); openId = null },
                            onRevert = { resolve(setOf(change.id), true); openId = null })
                    } else {
                        ListPage(
                            tab = tab, onTab = { tab = it }, pending = pending, history = history, actions = actions,
                            onClearActions = { ledger.clearActions(sessionKey) },
                            selected = selected,
                            onToggle = { cid -> selected = if (cid in selected) selected - cid else selected + cid },
                            onToggleAll = { selected = if (selected.size == pending.size) emptySet() else pending.map { it.id }.toSet() },
                            onOpen = { openId = it },
                            onKeep = { resolve(it, false) }, onRevert = { resolve(it, true) },
                            onClearHistory = { ledger.clearResolved(sessionKey) },
                            onClose = onDismiss
                        )
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
            }
        }
    }
}

@Composable
private fun ListPage(
    tab: ReviewTab, onTab: (ReviewTab) -> Unit,
    pending: List<TrackedChange>, history: List<TrackedChange>, actions: List<ActionRecord>, onClearActions: () -> Unit,
    selected: Set<String>, onToggle: (String) -> Unit, onToggleAll: () -> Unit,
    onOpen: (String) -> Unit, onKeep: (Set<String>) -> Unit, onRevert: (Set<String>) -> Unit,
    onClearHistory: () -> Unit, onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
            Column(Modifier.weight(1f)) {
                Text("Review changes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Everything the agent changed in this session", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent, divider = {}) {
            Tab(tab == ReviewTab.PENDING, { onTab(ReviewTab.PENDING) }, text = { Text("Pending (${pending.size})") })
            Tab(tab == ReviewTab.HISTORY, { onTab(ReviewTab.HISTORY) }, text = { Text("History (${history.size})") })
            Tab(tab == ReviewTab.ACTIVITY, { onTab(ReviewTab.ACTIVITY) }, text = { Text("Activity (${actions.size})") })
        }
        val list = if (tab == ReviewTab.PENDING) pending else history
        Box(Modifier.weight(1f)) {
            if (tab == ReviewTab.ACTIVITY) {
                ActivityList(actions, onClearActions)
            } else if (list.isEmpty()) {
                EmptyState(if (tab == ReviewTab.PENDING) "All caught up" else "No resolved changes yet",
                    if (tab == ReviewTab.PENDING) "New agent edits will show up here." else "Kept and reverted files are listed here.")
            } else LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tab == ReviewTab.PENDING) item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggleAll).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected.size == pending.size && pending.isNotEmpty(), { onToggleAll() })
                        Text(if (selected.isEmpty()) "Select all" else "${selected.size} selected", style = MaterialTheme.typography.labelLarge)
                    }
                } else item {
                    TextButton(onClick = onClearHistory) { Text("Clear history") }
                }
                items(list, key = { it.id }) { c ->
                    ChangeRow(c, checked = c.id in selected, selectable = tab == ReviewTab.PENDING,
                        onToggle = { onToggle(c.id) }, onOpen = { onOpen(c.id) },
                        onKeep = { onKeep(setOf(c.id)) }, onRevert = { onRevert(setOf(c.id)) })
                }
            }
            if (tab == ReviewTab.PENDING && pending.isNotEmpty()) {
                val targets = if (selected.isEmpty()) pending.map { it.id }.toSet() else selected
                val label = if (selected.isEmpty()) "all" else "${selected.size}"
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    shape = RoundedCornerShape(24.dp), tonalElevation = 8.dp, shadowElevation = 12.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { onRevert(targets) }, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = OmniError),
                            border = BorderStroke(1.dp, OmniError.copy(.6f))) {
                            Icon(Icons.Filled.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Revert $label")
                        }
                        Button(onClick = { onKeep(targets) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = OmniSuccess, contentColor = Color(0xFF052E16))) {
                            Icon(Icons.Filled.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Keep $label")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(c: TrackedChange, checked: Boolean, selectable: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, onKeep: () -> Unit, onRevert: () -> Unit) {
    val border by animateColorAsState(if (checked) OmniPrimary else MaterialTheme.colorScheme.outlineVariant.copy(.5f), label = "row-border")
    Surface(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, border)) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectable) Checkbox(checked, { onToggle() })
            else Spacer(Modifier.width(12.dp))
            KindBadge(c.kind)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(c.fileName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(buildString {
                    if (c.parentDir.isNotEmpty()) append(c.parentDir).append(" · ")
                    append(c.editCount).append(if (c.editCount == 1) " edit" else " edits")
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (c.status == ChangeStatus.PENDING) {
                IconButton(onClick = onRevert) { Icon(Icons.Filled.Undo, "Revert ${c.fileName}", tint = OmniError) }
                IconButton(onClick = onKeep) { Icon(Icons.Filled.Check, "Keep ${c.fileName}", tint = OmniSuccess) }
            } else {
                StatusChip(c.status)
                if (c.status == ChangeStatus.KEPT) IconButton(onClick = onRevert) { Icon(Icons.Filled.Undo, "Undo keep", tint = OmniWarning) }
            }
        }
    }
}

@Composable
private fun KindBadge(kind: ChangeKind) {
    val (label, color) = when (kind) {
        ChangeKind.CREATED -> "NEW" to OmniSuccess
        ChangeKind.DELETED -> "DEL" to OmniError
        ChangeKind.MODIFIED -> "MOD" to OmniWarning
    }
    Box(Modifier.size(width = 40.dp, height = 28.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(.16f)), contentAlignment = Alignment.Center) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun StatusChip(s: ChangeStatus) {
    val (label, color) = if (s == ChangeStatus.KEPT) "Kept" to OmniSuccess else "Reverted" to OmniError
    Box(Modifier.padding(end = 8.dp).clip(CircleShape).background(color.copy(.16f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyState(title: String, sub: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.TaskAlt, null, Modifier.size(52.dp), tint = OmniPrimary.copy(.7f))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiffPage(ledger: SessionChangeLedger, change: TrackedChange, onBack: () -> Unit, onKeep: () -> Unit, onRevert: () -> Unit) {
    var diff by remember(change.id) { mutableStateOf<FileDiff?>(null) }
    LaunchedEffect(change.id, change.editCount) { diff = ledger.diffFor(change) }
    val hScroll = rememberScrollState()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(change.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(change.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            diff?.let {
                Text("+${it.added}", color = AddFg, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Text("−${it.removed}", color = DelFg, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(12.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(16.dp)).background(CodeBg)) {
            val d = diff
            when {
                d == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                d.lines.isEmpty() -> EmptyState("No differences", "The file matches its original.")
                else -> LazyColumn(Modifier.fillMaxSize().horizontalScroll(hScroll), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(d.lines.size) { i -> DiffRow(d.lines[i]) }
                }
            }
        }
        if (change.status == ChangeStatus.PENDING) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRevert, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = OmniError), border = BorderStroke(1.dp, OmniError.copy(.6f))) {
                    Icon(Icons.Filled.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Revert file")
                }
                Button(onClick = onKeep, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = OmniSuccess, contentColor = Color(0xFF052E16))) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Keep file")
                }
            }
        } else Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

@Composable
private fun DiffRow(line: DiffLine) {
    if (line.type == DiffLineType.COLLAPSED) {
        Row(Modifier.fillMaxWidth().widthIn(min = 360.dp).background(OmniPrimary.copy(.08f)).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Icon(Icons.Filled.UnfoldMore, null, Modifier.size(14.dp), tint = OmniPrimary)
            Spacer(Modifier.width(6.dp))
            Text(line.text, color = OmniPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        return
    }
    val bg = when (line.type) { DiffLineType.ADDED -> AddBg; DiffLineType.REMOVED -> DelBg; else -> Color.Transparent }
    val fg = when (line.type) { DiffLineType.ADDED -> AddFg; DiffLineType.REMOVED -> DelFg; else -> Color(0xFFD7D7E4) }
    val sign = when (line.type) { DiffLineType.ADDED -> "+"; DiffLineType.REMOVED -> "−"; else -> " " }
    Row(Modifier.widthIn(min = 360.dp).background(bg), verticalAlignment = Alignment.Top) {
        LineNo(line.oldNo); LineNo(line.newNo)
        Text(sign, color = fg, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
        Text(line.text.ifEmpty { " " }, color = fg, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, softWrap = false, modifier = Modifier.padding(end = 16.dp))
    }
}

@Composable
private fun LineNo(n: Int?) {
    Text(n?.toString() ?: "", color = Color(0xFF6B6B85), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 18.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
        modifier = Modifier.width(38.dp).background(Gutter).padding(end = 6.dp))
}

@Composable
private fun ActivityList(actions: List<ActionRecord>, onClear: () -> Unit) {
    if (actions.isEmpty()) { EmptyState("No actions yet", "Commands, intents and app actions the agent runs are logged here."); return }
    var expanded by remember { mutableStateOf<String?>(null) }
    val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TextButton(onClick = onClear) { Text("Clear activity") } }
        items(actions, key = { it.id }) { a ->
            val (label, color) = actionStyle(a.kind)
            Surface(Modifier.fillMaxWidth().clickable { expanded = if (expanded == a.id) null else a.id },
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(.5f))) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(CircleShape).background(color.copy(.16f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                            Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(fmt.format(java.util.Date(a.at)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.clip(CircleShape).background(OmniSuccess.copy(.16f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("Ran", color = OmniSuccess, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(a.preview, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                        maxLines = if (expanded == a.id) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun actionStyle(kind: com.omnidev.workspace.core.policy.ConfirmationKind): Pair<String, Color> = when (kind) {
    com.omnidev.workspace.core.policy.ConfirmationKind.SHIZUKU_COMMAND -> "Shell command" to OmniWarning
    com.omnidev.workspace.core.policy.ConfirmationKind.ANDROID_INTENT -> "Intent" to OmniPrimary
    com.omnidev.workspace.core.policy.ConfirmationKind.CONNECTED_APP_ACTION -> "Connected app" to OmniSecondary
    com.omnidev.workspace.core.policy.ConfirmationKind.ASSISTANT_ACTION -> "Assistant action" to OmniPrimary
    com.omnidev.workspace.core.policy.ConfirmationKind.LEARNED_TASK -> "Learned task" to OmniSecondary
    else -> "File action" to OmniWarning
}
