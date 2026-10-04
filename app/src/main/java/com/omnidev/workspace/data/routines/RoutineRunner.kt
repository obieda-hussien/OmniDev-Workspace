package com.omnidev.workspace.data.routines

import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** A failed or uncertain mutation is never retried automatically. */
class RoutineRunner(
    private val store: RoutineStore,
    private val execute: suspend (String, Map<String, String>, String?) -> ToolExecutionResult,
    private val verify: suspend (UiSelector) -> Boolean,
    private val onRun: (RoutineRun) -> Unit = {}
) {
    companion object {
        private val lock = Mutex()
        private val active = java.util.concurrent.ConcurrentHashMap<String, RoutineRun>()
        @Volatile private var requestedPause = false
    }
    fun pause() { requestedPause = true }
    suspend fun start(routine: LearnedRoutine, parameters: Map<String, String>, scope: String?): RoutineRun {
        require(routine.enabled) { "Review and enable this routine first" }
        RoutineValidation.validate(routine)
        require(RoutineMatcher.required(routine).all { parameters[it]?.isNotBlank() == true }) { "Missing runtime parameters" }
        return run(routine, RoutineRun(UUID.randomUUID().toString(), routine.id, routine.revision,
            parameters = parameters, scopePath = scope))
    }
    suspend fun resume(id: String, parameters: Map<String, String> = emptyMap(), userCompletedStep: Boolean = false): RoutineRun {
        val saved = active[id] ?: store.runs().find { it.id == id } ?: error("Run not found")
        require(saved.status == RoutineRunStatus.PAUSED) { "Run is not paused; interrupted actions need manual review" }
        val routine = store.get(saved.routineId) ?: error("Routine removed")
        require(routine.enabled && routine.revision == saved.revision) { "Routine changed or disabled; start a new run" }
        val step = routine.steps.getOrNull(saved.nextStep) ?: error("Invalid cursor")
        val bound = saved.copy(parameters = saved.parameters + parameters)
        require(RoutineMatcher.required(routine).all { bound.parameters[it]?.isNotBlank() == true }) { "Re-enter runtime parameters" }
        val needsCompletion = saved.inFlight || step.kind in setOf(RoutineStepKind.DECISION, RoutineStepKind.USER)
        if (!needsCompletion && !userCompletedStep) return run(routine, bound)
        val verified = step.expected?.let { verify(RoutineMatcher.bindSelector(it, bound.parameters)) } == true
        if (!userCompletedStep && !verified) return publish(bound.copy(reason = "Complete this step manually, or verify its expected result before resuming"))
        return run(routine, bound.copy(nextStep = bound.nextStep + 1, inFlight = false))
    }
    private fun publish(run: RoutineRun): RoutineRun {
        val updated = run.copy(updatedAt = System.currentTimeMillis())
        if (updated.status in setOf(RoutineRunStatus.COMPLETED, RoutineRunStatus.CANCELLED)) active.remove(updated.id)
        else active[updated.id] = updated
        // Retain at most 30 paused runs' transient parameter maps.
        active.values.sortedByDescending { it.updatedAt }.drop(30).forEach { active.remove(it.id) }
        store.saveRun(updated); onRun(updated); return updated
    }
    private suspend fun run(routine: LearnedRoutine, initial: RoutineRun): RoutineRun {
        check(lock.tryLock()) { "A local routine is already running" }
        requestedPause = false
        var cursor = initial
        try {
            cursor = publish(cursor.copy(status = RoutineRunStatus.RUNNING, reason = ""))
            for (index in cursor.nextStep until routine.steps.size) {
                val current = store.get(routine.id)
                if (current == null || !current.enabled || current.revision != routine.revision)
                    return publish(cursor.copy(status = RoutineRunStatus.PAUSED, reason = "Routine removed, disabled or edited"))
                val step = routine.steps[index]
                cursor = cursor.copy(nextStep = index, inFlight = false)
                if (requestedPause || step.kind in setOf(RoutineStepKind.DECISION, RoutineStepKind.USER)) {
                    return publish(cursor.copy(status = RoutineRunStatus.PAUSED,
                        reason = if (requestedPause) "User requested takeover" else step.label))
                }
                cursor = publish(cursor.copy(inFlight = true))
                val result = withTimeout(step.timeoutMs + 2_000) {
                    if (step.kind == RoutineStepKind.WAIT) {
                        execute("semantic_ui", mapOf("action" to "routine_wait", "selector" to encode(RoutineMatcher.bindSelector(step.selector!!, cursor.parameters)),
                            "timeout_ms" to step.timeoutMs.toString()), cursor.scopePath)
                    } else if (step.kind == RoutineStepKind.TOOL) {
                        val bound = step.arguments.mapValues { RoutineMatcher.bind(it.value, cursor.parameters) }
                        if (step.tool == "omni_link" && !bound["json_payload"].isNullOrBlank())
                            kotlinx.serialization.json.Json.parseToJsonElement(bound.getValue("json_payload"))
                        execute(step.tool, bound, cursor.scopePath)
                    } else {
                        execute("semantic_ui", mapOf("action" to "routine_${step.kind.name.lowercase()}",
                            "selector" to encode(RoutineMatcher.bindSelector(step.selector!!, cursor.parameters)), "text" to RoutineMatcher.bind(step.value, cursor.parameters), "timeout_ms" to step.timeoutMs.toString()), cursor.scopePath)
                    }
                }
                if (result.isError) return publish(cursor.copy(status = RoutineRunStatus.PAUSED,
                    reason = "${step.label}: ${result.classification ?: "action failed"}. Inspect live state before continuing."))
                if (step.expected == null && step.kind in setOf(RoutineStepKind.CLICK, RoutineStepKind.LONG_CLICK, RoutineStepKind.SCROLL))
                    return publish(cursor.copy(status = RoutineRunStatus.PAUSED, reason = "Action accepted; no outcome was taught. Verify it manually before continuing."))
                if (step.expected != null && !awaitExpected(RoutineMatcher.bindSelector(step.expected, cursor.parameters), step.timeoutMs)) return publish(cursor.copy(status = RoutineRunStatus.PAUSED,
                    reason = "Expected result was not observed after ${step.label}; do not repeat blindly"))
                cursor = publish(cursor.copy(nextStep = index + 1, inFlight = false))
            }
            store.get(routine.id)?.let { store.save(it.copy(successfulRuns = it.successfulRuns + 1)) }
            return publish(cursor.copy(status = RoutineRunStatus.COMPLETED, reason = "Completed locally; 0 model tokens"))
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            return publish(cursor.copy(status = RoutineRunStatus.PAUSED, reason = "Action timed out; inspect its outcome before resuming"))
        } catch (cancelled: CancellationException) {
            publish(cursor.copy(status = RoutineRunStatus.PAUSED, reason = "Interrupted; verify the current step before resuming"))
            throw cancelled
        } catch (_: Exception) {
            return publish(cursor.copy(status = RoutineRunStatus.PAUSED, reason = "Execution interrupted; inspect live state before resuming"))
        } finally { lock.unlock() }
    }
    private suspend fun awaitExpected(selector: UiSelector, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        do {
            if (verify(selector)) return true
            kotlinx.coroutines.delay(150)
        } while (System.nanoTime() < deadline)
        return false
    }
    private fun encode(selector: UiSelector) = kotlinx.serialization.json.Json.encodeToString(UiSelector.serializer(), selector)
}
