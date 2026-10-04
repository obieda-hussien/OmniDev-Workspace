package com.omnidev.workspace.data.routines

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.omnidev.workspace.data.accessibility.SemanticUITool
import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class RoutineLearningHub private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: RoutineLearningHub? = null
        fun get(context: Context): RoutineLearningHub = instance ?: synchronized(this) {
            instance ?: RoutineLearningHub(context.applicationContext).also { instance = it }
        }
    }
    val store = RoutineStore(File(context.filesDir, "learned-routines"))
    private val _version = MutableStateFlow(0)
    val version = _version.asStateFlow()
    private val _teaching = MutableStateFlow<String?>(null)
    val teaching = _teaching.asStateFlow()
    private val _latestRun = MutableStateFlow<RoutineRun?>(null)
    val latestRun = _latestRun.asStateFlow()
    private val demo = mutableListOf<RoutineStep>()
    private var pendingTextSelector: UiSelector? = null
    private var demoName = ""
    val preferences = context.getSharedPreferences("routine-learning", Context.MODE_PRIVATE)
    val captureEnabled: Boolean get() = preferences.getBoolean("capture", true)
    init {
        // A process can die between an action and its cursor commit. Never rerun that action.
        store.runs().filter { it.status == RoutineRunStatus.RUNNING }.forEach {
            store.saveRun(it.copy(status = RoutineRunStatus.PAUSED, reason = "Process interrupted. Inspect and complete the current step before resuming."))
        }
        _latestRun.value = store.runs().maxByOrNull { it.updatedAt }
        TeachingNotification.hide(context)
    }
    fun changed() { _version.value++ }
    fun createRunner(execute: suspend (String, Map<String, String>, String?) -> ToolExecutionResult) =
        RoutineRunner(store, { name, args, scope ->
            if (name == "app_manager_tool" && args["action"] == "launch_app" &&
                com.omnidev.workspace.data.accessibility.AccessibilityStateManager.activePackage.value == args["packageName"])
                ToolExecutionResult("Target app is already foreground")
            else execute(name, args, scope)
        }, RoutineUi::present) { _latestRun.value = it; changed() }
    fun setCapture(enabled: Boolean) { preferences.edit().putBoolean("capture", enabled).apply(); changed() }
    @Synchronized fun startTeaching(name: String) {
        check(_teaching.value == null) { "Teaching already active" }
        require(name.isNotBlank())
        require(com.omnidev.workspace.core.policy.TierPolicyHolder.current.allowAccessibility) { "Live teaching unavailable in this flavor" }
        check(com.omnidev.workspace.data.accessibility.AccessibilityStateManager.isServiceConnected.value) { "Enable Omni Accessibility before teaching" }
        check(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) { "Enable notifications for teaching controls" }
        demoName = name.take(120); demo.clear(); pendingTextSelector = null
        _teaching.value = "Teaching $demoName · 0 steps"
        TeachingNotification.show(context)
    }
    @Synchronized fun decision(label: String = "Choose using the current screen, then verify the result") {
        if (_teaching.value == null) return
        flushText()
        append(RoutineStep(RoutineStepKind.DECISION, label.take(500)))
    }
    @Synchronized fun stopTeaching(save: Boolean = true): LearnedRoutine? {
        if (_teaching.value == null) return null
        flushText(); _teaching.value = null; TeachingNotification.hide(context)
        val steps = demo.toList(); demo.clear()
        if (!save || steps.isEmpty()) return null
        val recipe = LearnedRoutine(UUID.randomUUID().toString(), demoName, listOf(demoName), RoutineCompilation.withEntryPoint(steps), source = "demonstration")
        store.save(recipe); changed(); return recipe
    }
    @Synchronized fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (_teaching.value == null || event.packageName?.toString() == context.packageName) return
        if (event.eventType !in setOf(AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED)) return
        val node = event.source ?: return
        try {
            if (event.isPassword || node.isPassword) { flushText(); append(RoutineStep(RoutineStepKind.USER, "Protected input: user completes this step")); return }
            val selector = RoutineUi.selector(node)
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                // Never read event.text, beforeText or node.text for an input field.
                if (selector != pendingTextSelector) flushText()
                pendingTextSelector = selector
                if (selector == null) append(RoutineStep(RoutineStepKind.USER, "Input has no safe identity; user completes it"))
                return
            }
            flushText()
            if (selector == null || event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                append(RoutineStep(RoutineStepKind.DECISION, "Inspect current screen and navigate; no reliable target/direction was captured"))
            } else {
                append(RoutineStep(if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) RoutineStepKind.LONG_CLICK else RoutineStepKind.CLICK,
                    "${selector.text.ifBlank { selector.description.ifBlank { selector.viewId } }}", selector))
            }
        } finally { node.recycle() }
    }
    private fun flushText() {
        pendingTextSelector?.let {
            append(RoutineStep(RoutineStepKind.TYPE, "Enter runtime input", it, "{{input_${demo.size + 1}}}"))
        }
        pendingTextSelector = null
    }
    private fun append(step: RoutineStep) {
        if (demo.size >= 100) return
        // A next target is evidence for the previous action's resulting screen.
        if (demo.isNotEmpty() && step.selector != null && demo.last().expected == null &&
            demo.last().selector != step.selector) demo[demo.lastIndex] = demo.last().copy(expected = step.selector)
        demo += step
        _teaching.value = "Teaching $demoName · ${demo.size} steps"
    }
    fun capture(goal: String): RoutineCapture? = if (captureEnabled) RoutineCapture(goal, this) else null
}

/** Per-run buffer; concurrent chat/worker runs never share recording cursors. */
class RoutineCapture(private val goal: String, private val hub: RoutineLearningHub) {
    private val steps = mutableListOf<RoutineStep>()
    suspend fun before(call: ToolCall, readOnly: Boolean): RoutineStep? = withContext(Dispatchers.Main) {
        if (readOnly || call.name == "learned_routine") return@withContext null
        val args = call.arguments
        if (RoutineValidation.replayableTool(call.name, args)) {
            val safeArgs = if (call.name == "omni_link" && !args["json_payload"].isNullOrBlank())
                args.filterKeys { it in setOf("action", "extension_id", "action_name") } + ("json_payload" to "{{payload_${steps.size + 1}}}")
                else args
            return@withContext RoutineStep(RoutineStepKind.TOOL, "${call.name}: ${args["action"].orEmpty()}", tool = call.name, arguments = safeArgs)
        }
        val selector = if (call.name == "semantic_ui") runCatching { SemanticUITool.recordedSelector(args["node_id"]) }.getOrNull() else null
        val kind = when (args["action"]) {
            "click" -> RoutineStepKind.CLICK
            "long_click" -> RoutineStepKind.LONG_CLICK
            "type" -> RoutineStepKind.TYPE
            "scroll" -> RoutineStepKind.SCROLL
            else -> null
        }
        if (selector != null && kind != null) RoutineStep(kind, "${args["action"]}: ${selector.text.ifBlank { selector.viewId }}",
            selector, if (kind == RoutineStepKind.TYPE) "{{input_${steps.size + 1}}}" else if (kind == RoutineStepKind.SCROLL) args["direction"] ?: "down" else "")
        else RoutineStep(RoutineStepKind.DECISION, "Agent action ${call.name}/${args["action"].orEmpty()} needs fresh inspection and a decision")
    }
    fun after(step: RoutineStep?, result: ToolExecutionResult) {
        if (step == null || steps.size >= 100) return
        val saved = if (result.isError) RoutineStep(RoutineStepKind.DECISION, "Previous attempt failed; inspect and solve this step") else step
        if (steps.isNotEmpty() && saved.selector != null && steps.last().selector != saved.selector)
            steps[steps.lastIndex] = steps.last().copy(expected = saved.selector)
        steps += saved
    }
    fun finish(): LearnedRoutine? {
        if (steps.isEmpty()) return null
        // All learned behavior remains a disabled draft until the user's review.
        val previous = hub.store.list().find { !it.enabled && it.source == "agent" && it.triggers == listOf(goal.take(500)) }
        val recipe = LearnedRoutine(previous?.id ?: UUID.randomUUID().toString(), goal.take(120), listOf(goal.take(500)), RoutineCompilation.withEntryPoint(steps.toList()),
            revision = (previous?.revision ?: 0) + 1)
        return runCatching { hub.store.save(recipe); hub.changed(); recipe }.getOrNull()
    }
}
