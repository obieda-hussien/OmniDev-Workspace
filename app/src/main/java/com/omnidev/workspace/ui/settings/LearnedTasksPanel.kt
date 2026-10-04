package com.omnidev.workspace.ui.settings

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.WorkspaceChatRuntime
import com.omnidev.workspace.data.accessibility.AccessibilityStateManager
import com.omnidev.workspace.data.routines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LearnedTasksPanel(onAskOmni: (String) -> Unit) {
    val context = LocalContext.current
    val hub = remember { RoutineLearningHub.get(context) }
    val version by hub.version.collectAsState()
    val teaching by hub.teaching.collectAsState()
    val latest by hub.latestRun.collectAsState()
    val recipes = remember(version) { hub.store.list() }
    val paused = latest?.takeIf { it.status == RoutineRunStatus.PAUSED }
        ?: remember(version) { hub.store.runs().filter { it.status == RoutineRunStatus.PAUSED }.maxByOrNull { it.updatedAt } }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<LearnedRoutine?>(null) }
    var delete by remember { mutableStateOf<LearnedRoutine?>(null) }
    var run by remember { mutableStateOf<Pair<LearnedRoutine, RoutineRun?>?>(null) }
    var name by remember { mutableStateOf("") }
    var naming by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        status = if (it) "Notification controls are ready. Start teaching again." else "Enable notifications to stop teaching from other apps."
    }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                review = RoutineVideoImporter.import(context, uri, name.ifBlank { "Video lesson" })
                status = "Sampled locally. Review each step or ask Omni to help interpret the frames."
            } catch (error: Exception) { status = error.message }
            finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Learned tasks", style = MaterialTheme.typography.titleLarge)
        Text("Teach once, review, then run locally. Omni joins only when you ask for help with a paused step.",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Learn drafts from agent actions", Modifier.weight(1f))
            Switch(hub.captureEnabled, { hub.setCapture(it) })
        }
        Text("Drafts stay inactive until reviewed. Inputs become parameters; recorded input text is never saved.", style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(enabled = teaching == null && !busy, onClick = { name = ""; importing = false; naming = true }) { Text("Teach live") }
            OutlinedButton(enabled = !busy, onClick = { name = ""; importing = true; naming = true }) { Text(if (busy) "Importing…" else "From video") }
        }
        teaching?.let {
            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(it)
                Text("Switch to your app and demonstrate. Notification buttons add a decision or stop the lesson.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { hub.decision() }) { Text("Decision") }
                    Button(onClick = { review = hub.stopTeaching() }) { Text("Stop & review") }
                }
            } }
        }
        latest?.takeIf { it.status == RoutineRunStatus.RUNNING }?.let {
            Text("Local task running · step ${it.nextStep + 1} · 0 model tokens")
            OutlinedButton(onClick = { WorkspaceChatRuntime.get(context).pauseLocalRoutine() }) { Text("Pause / take over") }
        }
        paused?.let { checkpoint ->
            val recipe = recipes.find { it.id == checkpoint.routineId }
            if (recipe != null) Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paused: ${recipe.name}", style = MaterialTheme.typography.titleMedium)
                Text(checkpoint.reason, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { run = recipe to checkpoint }) { Text("I completed this step") }
                    Button(onClick = { onAskOmni(handoffPrompt(recipe, checkpoint)) }) { Text("Ask Omni") }
                }
            } }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        recipes.forEach { recipe ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(recipe.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(recipe.enabled, { enabled ->
                        if (enabled) review = recipe else { hub.store.save(recipe.copy(enabled = false)); hub.changed() }
                    })
                }
                Text("${recipe.steps.size} steps · ${recipe.successfulRuns} local runs · ${if (recipe.enabled) "Ready" else "Draft"}", style = MaterialTheme.typography.labelSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { review = recipe }) { Text("Review") }
                    Button(enabled = recipe.enabled, onClick = { run = recipe to null }) { Text("Run") }
                    TextButton(onClick = { delete = recipe }) { Text("Delete") }
                }
            } }
        }
    }
    if (naming) AlertDialog(onDismissRequest = { naming = false }, title = { Text(if (importing) "Video lesson" else "Teach Omni") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Task name / trigger phrase") }) },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = {
            naming = false
            if (importing) video.launch(arrayOf("video/*"))
            else if (!com.omnidev.workspace.core.policy.TierPolicyHolder.current.allowAccessibility) {
                status = "Live teaching is unavailable in this flavor."
            } else if (!AccessibilityStateManager.isServiceConnected.value) {
                status = "Enable Omni Accessibility, then start teaching."
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else { status = "Enable Omni notifications for teaching controls."; context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
            } else runCatching { hub.startTeaching(name) }.onFailure { status = it.message }
        }) { Text(if (importing) "Choose video" else "Start teaching") } }, dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } })
    review?.let { recipe -> RoutineReviewDialog(recipe, onDismiss = { review = null }, onSave = { updated ->
        runCatching { hub.store.save(updated.copy(revision = recipe.revision + 1)); hub.changed(); review = null }
            .onFailure { status = it.message }
    }, onAskOmni = onAskOmni) }
    delete?.let { recipe -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete ${recipe.name}?") },
        text = { Text("Removes its saved steps, checkpoints and imported video samples.") }, confirmButton = { TextButton(onClick = {
            hub.store.delete(recipe.id); RoutineVideoImporter.deleteFrames(context, recipe); hub.changed(); delete = null
        }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
    run?.let { (recipe, checkpoint) -> RoutineParametersDialog(recipe, checkpoint != null, onDismiss = { run = null }) { parameters ->
        WorkspaceChatRuntime.get(context).runLocalRoutine(recipe.id, parameters, checkpoint?.id, userCompletedStep = checkpoint != null)
        run = null
    } }
}

private fun handoffPrompt(recipe: LearnedRoutine, run: RoutineRun): String =
    "Help with one paused learned task step. Recipe ${recipe.id}, run ${run.id}, cursor ${run.nextStep}. " +
    "Reason: ${run.reason}. Step: ${recipe.steps.getOrNull(run.nextStep)?.label}. Inspect the current UI before acting. " +
    "Do not restart or repeat earlier actions. If you verify the expected result, use learned_routine resume with this run_id. " +
    "If there is no verifiable expected result, ask me to complete/review this step in Learned tasks."

@Composable
private fun RoutineParametersDialog(recipe: LearnedRoutine, resuming: Boolean, onDismiss: () -> Unit, onRun: (Map<String, String>) -> Unit) {
    val names = remember(recipe) { RoutineMatcher.required(recipe).toList().sorted() }
    var values by remember(recipe) { mutableStateOf(names.associateWith { "" }) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (resuming) "Confirm step completed" else "Run ${recipe.name}") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (resuming) "Continue after the paused step. Confirm only after completing or verifying it in the target app." else "Runs saved steps on your current screen. Keep the target app open.")
            names.forEach { key -> OutlinedTextField(values[key].orEmpty(), { values = values + (key to it) }, label = { Text(key) }) }
        } }, confirmButton = { Button(enabled = values.values.all { it.isNotBlank() }, onClick = { onRun(values) }) { Text(if (resuming) "Completed — continue" else "Run locally") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun RoutineReviewDialog(recipe: LearnedRoutine, onDismiss: () -> Unit, onSave: (LearnedRoutine) -> Unit, onAskOmni: (String) -> Unit) {
    val context = LocalContext.current
    var name by remember(recipe) { mutableStateOf(recipe.name) }
    var aliases by remember(recipe) { mutableStateOf(recipe.triggers.joinToString("\n")) }
    var steps by remember(recipe) { mutableStateOf(recipe.steps) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var stepMenu by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(enabled: Boolean) {
        val updated = recipe.copy(name = name, triggers = aliases.lines().filter { it.isNotBlank() }, steps = steps, enabled = enabled)
        runCatching { RoutineValidation.validate(updated); onSave(updated) }.onFailure { error = it.message }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Review learned task") }, text = {
        Column(Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") })
            OutlinedTextField(aliases, { aliases = it }, label = { Text("Trigger phrases, one per line") }, supportingText = { Text("Use {{query}} for changing input. Exact phrases only; ambiguous matches never execute.") })
            Text("Confirm targets and outcomes. Missing or duplicate targets pause the task. Steps that need thinking remain decisions.", style = MaterialTheme.typography.bodySmall)
            if (recipe.source.startsWith("video:")) {
                Text("Video evidence · sparse samples", style = MaterialTheme.typography.labelLarge)
                Text("Ask Omni uses your selected model to interpret these samples.", style = MaterialTheme.typography.bodySmall)
                for (index in 0..7) {
                    val bitmap by produceState<android.graphics.Bitmap?>(null, recipe.id, index) {
                        value = withContext(Dispatchers.IO) { RoutineVideoImporter.frameFile(context, recipe, index)?.let { BitmapFactory.decodeFile(it.path) } }
                    }
                    bitmap?.let { Image(it.asImageBitmap(), "Video sample $index", Modifier.fillMaxWidth().height(140.dp)) }
                }
            }
            steps.forEachIndexed { index, step ->
                Card { Column(Modifier.padding(8.dp)) {
                    Text("${index + 1}. ${step.kind} · ${step.label}", style = MaterialTheme.typography.bodySmall)
                    if (step.kind == RoutineStepKind.TOOL) Text(
                        step.arguments.filterKeys { it in setOf("packageName", "extension_id", "action_name") }.values.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall)
                    Row {
                        TextButton(onClick = { editing = index }) { Text("Edit step / outcome") }
                        Box {
                            TextButton(onClick = { stepMenu = index }) { Text("More") }
                            DropdownMenu(stepMenu == index, { stepMenu = null }) {
                                DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0, onClick = {
                                    steps = steps.toMutableList().also { java.util.Collections.swap(it, index, index - 1) }; stepMenu = null
                                })
                                DropdownMenuItem(text = { Text("Move down") }, enabled = index < steps.lastIndex, onClick = {
                                    steps = steps.toMutableList().also { java.util.Collections.swap(it, index, index + 1) }; stepMenu = null
                                })
                                DropdownMenuItem(text = { Text("Remove") }, onClick = { steps = steps.filterIndexed { i, _ -> i != index }; stepMenu = null })
                            }
                        }
                    }
                } }
            }
            OutlinedButton(enabled = steps.size < 100, onClick = {
                steps = steps + RoutineStep(RoutineStepKind.DECISION, "Decide using the current screen")
                editing = steps.lastIndex
            }) { Text("Add step") }
            if (recipe.source.startsWith("video:")) OutlinedButton(onClick = {
                onAskOmni("Help teach learned task ${recipe.id} from its video samples. Use learned_routine inspect and video_frame for available indices 0..7. " +
                    "These are sparse samples, not a complete action log. Ask me for target package/hidden identities if uncertain. " +
                    "Create a disabled save_draft recipe with semantic selectors and {{parameters}}. Keep uncertain actions as DECISION. Never execute the video or enable the task automatically.")
                onDismiss()
            }) { Text("Interpret samples with Omni") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = { save(true) }) { Text("Approve & enable") } },
        dismissButton = { TextButton(onClick = { save(false) }) { Text("Save draft") } })
    editing?.let { index -> StepEditor(steps[index], onDismiss = { editing = null }) { step ->
        steps = steps.toMutableList().also { it[index] = step }; editing = null
    } }
}

@Composable
private fun StepEditor(step: RoutineStep, onDismiss: () -> Unit, onSave: (RoutineStep) -> Unit) {
    var kind by remember { mutableStateOf(step.kind) }
    var expanded by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf(step.label) }
    var targetPackage by remember { mutableStateOf(step.selector?.packageName.orEmpty()) }
    var targetId by remember { mutableStateOf(step.selector?.viewId.orEmpty()) }
    var targetText by remember { mutableStateOf(step.selector?.text.orEmpty()) }
    var targetDescription by remember { mutableStateOf(step.selector?.description.orEmpty()) }
    var expectedPackage by remember { mutableStateOf(step.expected?.packageName.orEmpty()) }
    var expectedId by remember { mutableStateOf(step.expected?.viewId.orEmpty()) }
    var expectedText by remember { mutableStateOf(step.expected?.text.orEmpty()) }
    var value by remember { mutableStateOf(step.value) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Teach this step") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box { OutlinedButton(onClick = { expanded = true }) { Text(kind.name) }
                DropdownMenu(expanded, { expanded = false }) { RoutineStepKind.entries.filter { it != RoutineStepKind.TOOL }.forEach {
                    DropdownMenuItem(text = { Text(it.name) }, onClick = { kind = it; expanded = false })
                } }
            }
            OutlinedTextField(label, { label = it }, label = { Text("Instruction / decision") })
            if (kind !in setOf(RoutineStepKind.DECISION, RoutineStepKind.USER)) {
                Text("Find this element wherever it moves", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(targetPackage, { targetPackage = it }, label = { Text("App package") }, supportingText = { Text("For example, com.example.app. Captured lessons fill this automatically.") })
                OutlinedTextField(targetText, { targetText = it }, label = { Text("Exact visible text") })
                OutlinedTextField(targetDescription, { targetDescription = it }, label = { Text("Accessibility label (optional)") })
                OutlinedTextField(targetId, { targetId = it }, label = { Text("View ID (optional)") })
                if (kind == RoutineStepKind.TYPE || kind == RoutineStepKind.SCROLL)
                    OutlinedTextField(value, { value = it }, label = { Text(if (kind == RoutineStepKind.TYPE) "Input: {{parameter_name}}" else "Scroll direction: up / down") })
            }
            Text("Result that allows the next step", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(expectedPackage, { expectedPackage = it }, label = { Text("Result app package (optional)") })
            OutlinedTextField(expectedText, { expectedText = it }, label = { Text("Exact text visible after completion") })
            OutlinedTextField(expectedId, { expectedId = it }, label = { Text("Result view ID (optional)") })
            Text("Without a taught outcome, clicks pause for your verification.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = {
        runCatching {
            val target = if (kind in setOf(RoutineStepKind.DECISION, RoutineStepKind.USER)) null else
                UiSelector(targetPackage.trim(), targetId.trim(), targetText, targetDescription, step.selector?.className.orEmpty())
            val outcome = if (expectedId.isBlank() && expectedText.isBlank()) null else
                UiSelector(expectedPackage.ifBlank { targetPackage }.trim(), expectedId.trim(), expectedText)
            val updated = step.copy(kind = kind, label = label, selector = target, expected = outcome, value = value)
            RoutineValidation.validate(LearnedRoutine("validation", "Step", listOf("Step"), listOf(updated)))
            onSave(updated)
        }.onFailure { error = it.message }
    }) { Text("Save step") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
