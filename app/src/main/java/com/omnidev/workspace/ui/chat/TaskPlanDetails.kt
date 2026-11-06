package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.domain.planning.TaskPlanSummary

@Composable
internal fun TaskPlanDetails(plan: TaskPlanSummary) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Task plan · Revision ${plan.revision}", style = MaterialTheme.typography.titleSmall)
        Text(plan.objective, style = MaterialTheme.typography.bodyMedium)
        Text(plan.summary, style = MaterialTheme.typography.bodySmall)
        if (plan.revision > 1) Text("Plan changed: ${plan.reason}", style = MaterialTheme.typography.bodySmall)
        plan.constraints.forEach { Text("Constraint: $it", style = MaterialTheme.typography.bodySmall) }
        plan.steps.forEach { step ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${step.key} · ${step.state.name.replace('_', ' ')}", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(step.description, style = MaterialTheme.typography.bodyMedium)
                if (step.dependencies.isNotEmpty()) Text("Depends on: ${step.dependencies.joinToString()}", style = MaterialTheme.typography.bodySmall)
                Text("Expected: ${step.expectedOutput}", style = MaterialTheme.typography.bodySmall)
                Text("Verification: ${step.condition}", style = MaterialTheme.typography.bodySmall)
                if (step.evidenceCount > 0) Text("Verified evidence: ${step.evidenceCount}", style = MaterialTheme.typography.bodySmall)
                step.approval?.let { Text("Policy review: ${it.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (plan.stalled) Text("No step can run. Review failed dependencies or required approval.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
