package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import com.omnidev.workspace.domain.taskstate.*

@Composable
internal fun TaskRecoverySection(
    tasks: List<TaskRecoverySummary>,
    loading: Boolean,
    error: String?,
    actionsEnabled: Boolean,
    onResume: (String) -> Unit,
    onReconcile: (String, String, InvocationStatus, String) -> Unit,
    onDismiss: (String) -> Unit
) {
    if (tasks.isEmpty() && !loading && error.isNullOrBlank()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (tasks.isNotEmpty()) {
            Text("Task recovery", style = MaterialTheme.typography.titleMedium)
            tasks.forEach { task -> TaskRecoveryCard(task, actionsEnabled, onResume, onReconcile, onDismiss) }
        }
        if (loading) Text("Checking saved task activity…", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!error.isNullOrBlank()) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Text(error, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TaskRecoveryCard(
    task: TaskRecoverySummary,
    actionsEnabled: Boolean,
    onResume: (String) -> Unit,
    onReconcile: (String, String, InvocationStatus, String) -> Unit,
    onDismiss: (String) -> Unit
) {
    var expanded by rememberSaveable(task.taskId) { mutableStateOf(false) }
    val lastActivity = remember(task.latestActivityAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
            .format(Date(task.latestActivityAt))
    }
    var selectedInvocation by remember(task.taskId) { mutableStateOf<RecoveryInvocationSummary?>(null) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.status.name.replace('_', ' '), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = { onDismiss(task.taskId) }, enabled = actionsEnabled) { Text("Dismiss") }
            }
            Text(task.originalRequest, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (task.objective != task.originalRequest) {
                Text("Current objective: ${task.objective}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("Steps completed: ${task.completedSteps} of ${task.totalSteps} · Plan revision ${task.planRevision}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Cycles ${task.counts.cycles} · Successful ${task.counts.succeeded} · Failed ${task.counts.failed} · Unresolved ${task.counts.unresolved}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Last activity: $lastActivity", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.decision == RecoveryDecision.REQUIRES_RECONCILIATION) {
                Text("Reconciliation required before this task can safely continue.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } else task.reasons.firstOrNull()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { expanded = !expanded }, enabled = actionsEnabled) {
                    Text(if (expanded) "Hide review" else "Review task")
                }
                if (task.canResume) Button(onClick = { onResume(task.taskId) }, enabled = actionsEnabled) { Text("Resume") }
                else if (task.decision == RecoveryDecision.REQUIRES_RECONCILIATION) {
                    Text("Resume unavailable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            if (expanded) {
                Text("Completed steps", style = MaterialTheme.typography.titleSmall)
                if (task.completedStepDescriptions.isEmpty()) Text("No steps are recorded as completed.", style = MaterialTheme.typography.bodySmall)
                task.completedStepDescriptions.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
                Text("Remaining steps", style = MaterialTheme.typography.titleSmall)
                if (task.openSteps.isEmpty()) Text("No unfinished steps are recorded.", style = MaterialTheme.typography.bodySmall)
                task.openSteps.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                if (task.unresolvedInvocations.isNotEmpty()) {
                    Text("Unresolved operations", style = MaterialTheme.typography.titleSmall)
                    task.unresolvedInvocations.forEach { invocation ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${invocation.toolName} · ${invocation.status}", style = MaterialTheme.typography.bodyMedium)
                            Text("Invocation: ${invocation.invocationId}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (invocation.target.isNotBlank()) Text("Target: ${invocation.target}", style = MaterialTheme.typography.bodySmall)
                            if (invocation.resultSummary.isNotBlank()) Text(invocation.resultSummary, style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(invocation.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (invocation.requiresReconciliation) {
                                TextButton(onClick = { selectedInvocation = invocation }, enabled = actionsEnabled) {
                                    Text("Reconcile this operation")
                                }
                            }
                        }
                    }
                }
                Text("Latest cycle: ${task.latestCycle?.toString() ?: "None"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    selectedInvocation?.let { invocation ->
        ReconcileInvocationDialog(task.taskId, invocation,
            onDismiss = { selectedInvocation = null },
            onConfirm = { outcome, verification ->
                onReconcile(task.taskId, invocation.invocationId, outcome, verification)
                selectedInvocation = null
            })
    }
}

@Composable
private fun ReconcileInvocationDialog(
    taskId: String,
    invocation: RecoveryInvocationSummary,
    onDismiss: () -> Unit,
    onConfirm: (InvocationStatus, String) -> Unit
) {
    var outcome by remember(invocation.invocationId) { mutableStateOf<InvocationStatus?>(null) }
    var verification by rememberSaveable(invocation.invocationId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reconcile operation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Task: $taskId", style = MaterialTheme.typography.bodySmall)
                Text("Operation: ${invocation.invocationId}", style = MaterialTheme.typography.bodySmall)
                Text("${invocation.toolName} · ${invocation.status}", style = MaterialTheme.typography.bodyMedium)
                Text("Choose only an outcome you have independently established.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(
                    InvocationStatus.SUCCEEDED to "Succeeded",
                    InvocationStatus.FAILED to "Failed",
                    InvocationStatus.CANCELLED to "Did not execute"
                ).forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = outcome == value, onClick = { outcome = value })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(value = verification, onValueChange = { verification = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    label = { Text("Verification statement") },
                    placeholder = { Text("Describe how you confirmed this outcome.") },
                    supportingText = { Text("Required. A later read alone is not proof.") })
            }
        },
        confirmButton = {
            Button(onClick = { outcome?.let { onConfirm(it, verification.trim()) } },
                enabled = outcome != null && verification.isNotBlank()) {
                Text("Record outcome")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
