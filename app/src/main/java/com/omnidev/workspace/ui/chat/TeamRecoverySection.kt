package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.domain.teamstate.TeamRecoverySummary

/** Durable state only. Worker text and chat checkpoints cannot manufacture a successful status. */
@Composable
internal fun TeamRecoverySection(teams: List<TeamRecoverySummary>, enabled: Boolean,
    onResume: (String) -> Unit, onCancel: (String) -> Unit,
    onReconcile: (String, String, String, String) -> Unit, onRefresh: () -> Unit,
    onReviewIntervention: (String, String) -> Unit = { _, _ -> }) {
    if (teams.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onRefresh, enabled = enabled) { Text("Refresh saved Teams") }
        teams.forEach { team -> key(team.id) {
            Card(Modifier.fillMaxWidth().testTag("team-recovery-${team.id}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Saved Team · ${team.status}", style = MaterialTheme.typography.titleSmall)
                    Text(team.originalObjective.take(600), style = MaterialTheme.typography.bodySmall)
                    Text("Plan revision ${team.revision} · ${team.workers.count { it.status == "COMPLETED" }}/${team.workers.size} verified",
                        style = MaterialTheme.typography.labelMedium)
                    var inspect by rememberSaveable(team.id) { mutableStateOf(false) }
                    TextButton(onClick = { inspect = !inspect }) { Text(if (inspect) "Hide recovery details" else "Inspect plan and failures") }
                    if (inspect) {
                        if (team.verifiedSummary.isNotBlank()) Text(team.verifiedSummary, style = MaterialTheme.typography.bodySmall)
                        if (team.objective != team.originalObjective) Text("Current objective: ${team.objective.take(600)}")
                        team.workers.forEach { worker ->
                            Text("${worker.id}: ${worker.status} (attempt ${worker.attempt})", style = MaterialTheme.typography.bodySmall)
                            if (worker.description.isNotBlank()) Text(worker.description, style = MaterialTheme.typography.bodySmall)
                            worker.acceptance.forEach { Text("Acceptance: $it", style = MaterialTheme.typography.bodySmall) }
                            if (worker.dependencies.isNotEmpty()) Text("Dependencies: ${worker.dependencies.joinToString()}", style = MaterialTheme.typography.bodySmall)
                            worker.lastOperation?.let { Text("Last attempted operation: $it", style = MaterialTheme.typography.bodySmall) }
                            worker.blocker?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            worker.lastVerifiedOperation?.let { Text("Last verified tool result: $it", style = MaterialTheme.typography.bodySmall) }
                            if (worker.interventionRequired && worker.unresolvedCalls.isEmpty() && worker.attemptRunId != null) {
                                Text("Review permission or backend setup first. A retry still checks all normal tool permissions.", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onReviewIntervention(team.id, worker.attemptRunId) }, enabled = enabled) {
                                    Text("Recheck permissions on next attempt")
                                }
                            }
                            worker.unresolvedCalls.forEach { callId ->
                                Text("Review unresolved effect: $callId. An operation may have succeeded; it will not replay automatically.",
                                    style = MaterialTheme.typography.bodySmall)
                                if (callId in worker.reconcilableCalls && worker.attemptRunId != null) {
                                    var verification by rememberSaveable(team.id, worker.attemptRunId, callId) { mutableStateOf("") }
                                    OutlinedTextField(verification, { verification = it.take(1_000) },
                                        label = { Text("Evidence that this exact operation did not execute") },
                                        modifier = Modifier.fillMaxWidth(), enabled = enabled)
                                    TextButton(onClick = { onReconcile(team.id, worker.attemptRunId, callId, verification) },
                                        enabled = enabled && verification.trim().length >= 20) { Text("Confirm exact operation was not executed") }
                                }
                            }
                        }
                        team.blockers.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (team.canResume) Button(onClick = { onResume(team.id) }, enabled = enabled,
                        modifier = Modifier.testTag("team-resume-${team.id}")) { Text("Resume safe work") }
                    if (team.status !in setOf("COMPLETED", "CANCELLED", "RUNNING"))
                        TextButton(onClick = { onCancel(team.id) }, enabled = enabled) { Text("Cancel remaining work") }
                }
            }
        } }
    }
}
