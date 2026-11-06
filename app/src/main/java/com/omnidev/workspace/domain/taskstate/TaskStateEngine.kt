package com.omnidev.workspace.domain.taskstate

import com.omnidev.workspace.domain.planning.*

/** The single implementation of the store's rules, over any [TaskStateBackend] (Room, in-memory). */
class TaskStateEngine(private val backend: TaskStateBackend) : TaskStateStore {

    override suspend fun createTask(task: NewTask): Boolean = backend.atomic {
        val inserted = insertTask(TaskRecord(task.taskId, task.sessionId, task.originalRequest, task.normalizedObjective, task.parentTaskId,
            task.workerId, task.constraintsJson, TaskStatus.CREATED, 0, null, 0, task.createdAt, task.createdAt, task.createdAt, null, "", ""))
        if (inserted) appendEvent(task.taskId, "task", task.taskId, "", TaskStatus.CREATED.name, "created", task.createdAt, "create")
        inserted
    }

    override suspend fun task(taskId: String) = backend.atomic { task(taskId) }

    override suspend fun transitionTask(taskId: String, to: TaskStatus, reason: String, at: Long, eventKey: String): TransitionResult = backend.atomic {
        val t = task(taskId) ?: return@atomic TransitionResult.Rejected("unknown task")
        if (hasEvent(taskId, eventKey)) return@atomic TransitionResult.Duplicate
        val r = TaskStateRules.taskTransition(t.status, to)
        if (r == TransitionResult.Applied) {
            updateTask(t.copy(status = to, updatedAt = at))
            appendEvent(taskId, "task", taskId, t.status.name, to.name, reason, at, eventKey)
        }
        r
    }

    override suspend fun heartbeat(taskId: String, at: Long) = backend.atomic {
        task(taskId)?.let { updateTask(it.copy(heartbeatAt = at, updatedAt = maxOf(it.updatedAt, at))) }
        Unit
    }

    override suspend fun setObjective(taskId: String, normalizedObjective: String, at: Long) = backend.atomic {
        // Only the interpreted objective changes; originalRequest is not touched.
        task(taskId)?.let { updateTask(it.copy(normalizedObjective = normalizedObjective, updatedAt = at)) }
        Unit
    }

    override suspend fun appendPlanRevision(taskId: String, steps: List<NewStep>, reason: String, at: Long, eventKey: String, metadataJson: String, expectedRevision: Int?, runtimeObjective: String?): PlanRevisionRecord? = backend.atomic {
        val t = task(taskId) ?: throw TaskStateException("unknown task $taskId")
        if (hasEvent(taskId, eventKey)) return@atomic null
        if (expectedRevision != null && t.planRevision != expectedRevision) throw TaskStateException("STALE_PLAN_REVISION")
        val graphErrors = PlanValidator.graph(steps)
        if (graphErrors.isNotEmpty()) throw TaskStateException(graphErrors.first().code)
        val metadata = if (metadataJson.isBlank()) null else (PlanCodec.metadata(metadataJson)
            ?: throw TaskStateException("INVALID_PLAN_METADATA"))
        val plans = plans(taskId)
        val revision = (plans.maxOfOrNull { it.revision } ?: 0) + 1
        val previous = plans.lastOrNull()
        val allSteps = steps(taskId)
        val previousSteps = previous?.let { p -> allSteps.filter { it.planId == p.planId } }.orEmpty()
        if (metadata != null) {
            if (t.status != TaskStatus.RUNNING && t.status != TaskStatus.CREATED) throw TaskStateException("TASK_NOT_PLANNABLE")
            if (unresolvedSideEffects(taskId).isNotEmpty()) throw TaskStateException("UNRESOLVED_SIDE_EFFECT")
            if (metadata.policies.keys != steps.map { it.key }.toSet()) throw TaskStateException("INVALID_STEP_POLICY_KEYS")
            val proposed = StructuredPlan(metadata.objective, metadata.summary, metadata.constraints, steps.map {
                PlannedStep(it.key, it.description, it.dependsOnKeys, it.expectedOutput, it.completionCondition, metadata.policies.getValue(it.key))
            })
            val priorMetadata = PlanCodec.metadata(previous?.metadataJson.orEmpty())
            val mandatory = (priorMetadata?.constraints.orEmpty() + PlanningTrigger.constraints(t.originalRequest,
                TaskResumeMetadata.acceptance(t.constraintsJson).orEmpty())).distinct()
            val validation = PlanValidator.validate(proposed, mandatory)
            if (!validation.valid) throw TaskStateException(validation.diagnostic())
            if (validation.normalized!!.steps.map { it.key } != steps.map { it.key }) throw TaskStateException("NON_TOPOLOGICAL_PLAN")
            val completed = previousSteps.filter { it.status == StepStatus.COMPLETED }
            if (completed.any { old -> steps.none { TaskStateRules.carriedOver(listOf(old), it) != null } ||
                    priorMetadata?.policies?.get(old.key)?.let { it != metadata.policies[old.key] } == true })
                throw TaskStateException("COMPLETED_WORK_CHANGED")
            if (previous?.metadataJson == metadataJson && previousSteps.map {
                    NewStep(it.key, it.description, it.dependsOnKeys, it.expectedOutput, it.completionCondition)
                } == steps) return@atomic null
        } else if (previous?.metadataJson?.isNotBlank() == true) throw TaskStateException("STRUCTURED_METADATA_REQUIRED")
        if (previous != null) {
            savePlan(previous.copy(supersededAt = at))
            // Explicit, recorded supersession: nothing in the old revision is rewritten silently.
            previousSteps.filter { !it.status.terminal }.forEach { s ->
                saveStep(s.copy(status = StepStatus.SKIPPED, statusReason = "superseded by revision $revision", updatedAt = at))
                appendEvent(taskId, "step", s.stepId, s.status.name, StepStatus.SKIPPED.name, "superseded by revision $revision", at, "supersede:${s.stepId}:r$revision")
            }
        }
        val planId = TaskStateRules.planId(taskId, revision)
        val record = PlanRevisionRecord(planId, taskId, revision, TaskStateRules.clamp(reason, 300), at, null, metadataJson)
        savePlan(record)
        val byKey = HashMap<String, StepStatus>()
        steps.forEachIndexed { index, s ->
            val carried = TaskStateRules.carriedOver(previousSteps, s)
            val status = carried?.status ?: TaskStateRules.initialStatus(s, byKey)
            byKey[s.key] = status
            saveStep(StepRecord(TaskStateRules.stepId(taskId, revision, s.key), planId, taskId, s.key, index, s.description, s.dependsOnKeys,
                s.expectedOutput, s.completionCondition, status, if (carried != null) "carried from revision ${previous?.revision}" else "",
                carried?.evidenceInvocationIds.orEmpty(), carried?.nonToolJustification.orEmpty(), at, at))
        }
        updateTask(t.copy(planRevision = revision, currentStepId = null, normalizedObjective = runtimeObjective ?: t.normalizedObjective, updatedAt = at))
        appendEvent(taskId, "plan", planId, "r${revision - 1}", "r$revision", reason, at, eventKey)
        record
    }

    override suspend fun transitionStep(stepId: String, to: StepStatus, reason: String, at: Long, eventKey: String, completion: StepCompletion?): TransitionResult = backend.atomic {
        val s = step(stepId) ?: return@atomic TransitionResult.Rejected("unknown step")
        if (hasEvent(s.taskId, eventKey)) return@atomic TransitionResult.Duplicate
        val r = TaskStateRules.stepTransition(s.status, to)
        if (r != TransitionResult.Applied) return@atomic r
        val currentPlan = plans(s.taskId).maxByOrNull { it.revision }
        if (currentPlan?.metadataJson?.isNotBlank() == true) {
            if (s.planId != currentPlan.planId) return@atomic TransitionResult.Rejected("STALE_STEP")
            val snapshot = TaskSnapshot(task(s.taskId)!!, plans(s.taskId), steps(s.taskId), cycles(s.taskId), invocations(s.taskId))
            if (to == StepStatus.RUNNING && TaskExecutionScheduler.schedule(snapshot.currentSteps).next?.stepId != stepId)
                return@atomic TransitionResult.Rejected("DEPENDENCIES_NOT_READY")
            if (to == StepStatus.COMPLETED) {
                if (s.dependsOnKeys.any { key -> snapshot.currentSteps.none { it.key == key && it.status == StepStatus.COMPLETED } })
                    return@atomic TransitionResult.Rejected("DEPENDENCIES_NOT_READY")
                StepEvidenceEvaluator.evaluate(snapshot, s, completion)?.let { return@atomic TransitionResult.Rejected(it) }
            }
        }
        if (to == StepStatus.COMPLETED) {
            TaskStateRules.completionProof(invocationStatuses(s.taskId), completion)?.let { return@atomic TransitionResult.Rejected(it) }
        }
        saveStep(s.copy(status = to, statusReason = TaskStateRules.clamp(reason, 300), updatedAt = at,
            evidenceInvocationIds = completion?.evidenceInvocationIds ?: s.evidenceInvocationIds,
            nonToolJustification = completion?.nonToolJustification ?: s.nonToolJustification))
        appendEvent(s.taskId, "step", stepId, s.status.name, to.name, reason, at, eventKey)
        r
    }

    override suspend fun beginCycle(taskId: String, stepId: String?, at: Long, eventKey: String): CycleRecord = backend.atomic {
        val t = task(taskId) ?: throw TaskStateException("unknown task $taskId")
        val explicitlyReleased = t.status == TaskStatus.INTERRUPTED &&
            hasEvent(taskId, "resume-release:$taskId:${t.currentCycle}")
        if (explicitlyReleased || t.status in setOf(TaskStatus.BLOCKED, TaskStatus.AWAITING_APPROVAL,
                TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            throw TaskStateException("cannot start a cycle while task is ${t.status}")
        }
        if (hasEvent(taskId, eventKey)) cycle(TaskStateRules.cycleId(taskId, t.currentCycle))?.let { return@atomic it }
        if (stepId != null) {
            val active = step(stepId) ?: throw TaskStateException("UNKNOWN_ACTIVE_STEP")
            if (active.taskId != taskId || active.planId != plans(taskId).maxByOrNull { it.revision }?.planId)
                throw TaskStateException("STALE_ACTIVE_STEP")
            if (TaskExecutionScheduler.schedule(steps(taskId).filter { it.planId == active.planId }).next?.stepId != stepId)
                throw TaskStateException("DEPENDENCIES_NOT_READY")
        }
        val seq = t.currentCycle + 1
        val c = CycleRecord(TaskStateRules.cycleId(taskId, seq), taskId, seq, t.planRevision, stepId, at, null, "", "", "[]")
        saveCycle(c)
        updateTask(t.copy(currentCycle = seq, currentStepId = stepId, updatedAt = at))
        appendEvent(taskId, "cycle", c.cycleId, "", "STARTED", "cycle $seq", at, eventKey)
        c
    }

    override suspend fun endCycle(cycleId: String, outcome: String, summary: String, decisionsJson: String, at: Long): TransitionResult = backend.atomic {
        val c = cycle(cycleId) ?: return@atomic TransitionResult.Rejected("unknown cycle")
        if (c.endedAt != null) return@atomic TransitionResult.Duplicate
        saveCycle(c.copy(endedAt = at, outcome = outcome, summary = TaskStateRules.clamp(summary, 600), decisionsJson = TaskStateRules.clamp(decisionsJson, 2000)))
        appendEvent(c.taskId, "cycle", cycleId, "STARTED", "ENDED:$outcome", summary, at, "end:$cycleId")
        TransitionResult.Applied
    }

    override suspend fun recordInvocationIntent(invocation: NewInvocation): Boolean = backend.atomic {
        invocation.stepId?.let { id ->
            val active = step(id) ?: throw TaskStateException("UNKNOWN_INVOCATION_STEP")
            val cycle = cycle(invocation.cycleId) ?: throw TaskStateException("UNKNOWN_INVOCATION_CYCLE")
            val owner = task(invocation.taskId) ?: throw TaskStateException("UNKNOWN_INVOCATION_TASK")
            if (cycle.seq != owner.currentCycle || owner.currentStepId != id) throw TaskStateException("STALE_INVOCATION_CYCLE")
            if (active.taskId != invocation.taskId || active.planId != plans(invocation.taskId).maxByOrNull { it.revision }?.planId ||
                cycle.taskId != invocation.taskId || cycle.stepId != id || cycle.endedAt != null)
                throw TaskStateException("INVALID_INVOCATION_CONTEXT")
        }
        val inserted = insertInvocation(InvocationRecord(invocation.invocationId, invocation.callId, invocation.taskId, invocation.cycleId, invocation.stepId,
            invocation.toolName, invocation.schemaId, invocation.inputSanitized, invocation.inputHash, invocation.effect, invocation.idempotencyKey,
            invocation.attempt, invocation.retryOfInvocationId, InvocationStatus.PENDING, invocation.requestedAt, null, null, "", "", false, 0,
            false, "", "", "", nextInvocationSeq(invocation.taskId)))
        if (inserted) appendEvent(invocation.taskId, "invocation", invocation.invocationId, "", InvocationStatus.PENDING.name,
            "requested ${invocation.toolName}", invocation.requestedAt, "intent:${invocation.invocationId}")
        inserted
    }

    override suspend fun transitionInvocation(invocationId: String, to: InvocationStatus, patch: InvocationPatch, at: Long, eventKey: String, reason: String): TransitionResult = backend.atomic {
        val i = invocation(invocationId) ?: return@atomic TransitionResult.Rejected("unknown invocation")
        if (hasEvent(i.taskId, eventKey)) return@atomic TransitionResult.Duplicate
        val r = TaskStateRules.invocationTransition(i.status, to)
        if (r != TransitionResult.Applied) return@atomic r
        updateInvocation(i.copy(status = to,
            startedAt = patch.startedAt ?: i.startedAt, completedAt = patch.completedAt ?: i.completedAt,
            resultSummary = patch.resultSummary ?: i.resultSummary, resultHash = patch.resultHash ?: i.resultHash,
            resultTruncated = patch.resultTruncated ?: i.resultTruncated, resultChars = patch.resultChars ?: i.resultChars,
            isError = patch.isError ?: i.isError, errorDetail = patch.errorDetail ?: i.errorDetail,
            classification = patch.classification ?: i.classification, verification = patch.verification ?: i.verification))
        appendEvent(i.taskId, "invocation", invocationId, i.status.name, to.name, reason.ifBlank { i.toolName }, at, eventKey)
        r
    }

    override suspend fun finalizeTask(taskId: String, status: TaskStatus, finish: FinishRecord, stepOutcomes: List<StepOutcome>, at: Long, eventKey: String): TransitionResult = backend.atomic {
        val t = task(taskId) ?: return@atomic TransitionResult.Rejected("unknown task")
        if (hasEvent(taskId, eventKey)) return@atomic TransitionResult.Duplicate
        val r = TaskStateRules.taskTransition(t.status, status)
        if (r != TransitionResult.Applied) return@atomic r
        val latest = plans(taskId).maxByOrNull { it.revision }
        if (status == TaskStatus.COMPLETED && latest?.metadataJson?.isNotBlank() == true &&
            (steps(taskId).any { it.planId == latest.planId && it.status != StepStatus.COMPLETED } || unresolvedSideEffects(taskId).isNotEmpty()))
            return@atomic TransitionResult.Rejected("PLAN_NOT_VERIFIED")
        // Validate every step first: a rejected proof leaves the whole finalization unapplied.
        val statuses = invocationStatuses(taskId)
        val planned = ArrayList<Pair<StepRecord, StepOutcome>>()
        for (o in stepOutcomes) {
            val s = step(o.stepId)?.takeIf { it.taskId == taskId } ?: return@atomic TransitionResult.Rejected("unknown step ${o.stepId}")
            val sr = TaskStateRules.stepTransition(s.status, o.status)
            if (sr is TransitionResult.Rejected) return@atomic sr
            if (o.status == StepStatus.COMPLETED && sr == TransitionResult.Applied)
                TaskStateRules.completionProof(statuses, o.completion)?.let { return@atomic TransitionResult.Rejected(it) }
            if (sr == TransitionResult.Applied) planned += s to o
        }
        for ((s, o) in planned) {
            saveStep(s.copy(status = o.status, statusReason = TaskStateRules.clamp(o.reason, 300), updatedAt = at,
                evidenceInvocationIds = o.completion?.evidenceInvocationIds ?: s.evidenceInvocationIds,
                nonToolJustification = o.completion?.nonToolJustification ?: s.nonToolJustification))
            appendEvent(taskId, "step", s.stepId, s.status.name, o.status.name, o.reason, at, "final:${s.stepId}:${o.status}")
        }
        updateTask(t.copy(status = status, updatedAt = at, finishStatus = finish.finishStatus,
            finishSummary = TaskStateRules.clamp(finish.summary, 1000), completionDigest = finish.digest))
        appendEvent(taskId, "task", taskId, t.status.name, status.name, finish.finishStatus, at, eventKey)
        r
    }

    override suspend fun snapshot(taskId: String): TaskSnapshot? = backend.atomic {
        val t = task(taskId) ?: return@atomic null
        TaskSnapshot(t, plans(taskId), steps(taskId), cycles(taskId), invocations(taskId))
    }

    override suspend fun recoverySnapshot(taskId: String): TaskRecoverySnapshot? = backend.atomic {
        val t = task(taskId) ?: return@atomic null
        val plans = plans(taskId)
        val latest = plans.maxByOrNull { it.revision }
        val allSteps = steps(taskId)
        val currentSteps = latest?.let { p -> allSteps.filter { it.planId == p.planId }.sortedBy { it.ordinal } }.orEmpty()
        val statuses = invocationCounts(taskId)
        val unresolved = listOf(InvocationStatus.PENDING, InvocationStatus.RUNNING, InvocationStatus.INTERRUPTED, InvocationStatus.UNKNOWN)
            .sumOf { statuses[it] ?: 0 }
        val counts = TaskCounts(
            cycles = cycleCount(taskId),
            invocations = statuses.values.sum(),
            succeeded = statuses[InvocationStatus.SUCCEEDED] ?: 0,
            failed = statuses[InvocationStatus.FAILED] ?: 0,
            unresolved = unresolved,
            stepsTotal = currentSteps.size,
            stepsCompleted = currentSteps.count { it.status == StepStatus.COMPLETED }
        )
        TaskRecoverySnapshot(t, latest, currentSteps, cycles(taskId).maxByOrNull { it.seq }, counts,
            unresolvedInvocations(taskId), unresolvedSideEffects(taskId))
    }

    /**
     * The recovery normalization and the RUNNING claim share one Room transaction. A second caller
     * sees RUNNING and is rejected before it can alter the first caller's task state.
     */
    override suspend fun claimForResume(taskId: String, at: Long, eventKey: String): TaskResumeClaimResult = backend.atomic {
        val initial = task(taskId) ?: return@atomic TaskResumeClaimResult.Rejected("Task no longer exists.")
        appendEvent(taskId, "resume", taskId, initial.status.name, "RESUME_REQUESTED",
            "user explicitly requested task continuation", at, "resume-requested:$eventKey")
        appendEvent(taskId, "recovery", taskId, initial.status.name, "DISCOVERED",
            "user requested task recovery", at, "recovery-discovered:$eventKey")
        if (initial.status != TaskStatus.INTERRUPTED && initial.status != TaskStatus.INCOMPLETE) {
            val reason = when (initial.status) {
                TaskStatus.RUNNING -> "This task is already running."
                TaskStatus.COMPLETED -> "This task is already completed."
                TaskStatus.FAILED, TaskStatus.CANCELLED -> "This task has permanently ended."
                TaskStatus.BLOCKED -> "This task is blocked and needs user intervention."
                TaskStatus.AWAITING_APPROVAL -> "This task is waiting for approval."
                else -> "This task is not in a resumable state."
            }
            appendEvent(taskId, "resume", taskId, initial.status.name, "CLAIM_REJECTED",
                reason, at, "resume-rejected:$eventKey")
            return@atomic TaskResumeClaimResult.Rejected(reason)
        }
        val before = TaskSnapshot(initial, plans(taskId), steps(taskId), cycles(taskId), invocations(taskId))
        val recovery = TaskRecoveryPlanner.plan(before)
        appendEvent(taskId, "recovery", taskId, "DISCOVERED", recovery.decision.name,
            recovery.reasons.joinToString("; ").take(300), at, "recovery-decision:$eventKey")
        closeInterruptedCycles(taskId, at, "closed before explicit resume")

        for (move in recovery.invocationTransitions) {
            val current = invocation(move.invocationId) ?: continue
            if (TaskStateRules.invocationTransition(current.status, move.to) == TransitionResult.Applied) {
                val completedAt = if (move.to == InvocationStatus.CANCELLED) at else current.completedAt
                updateInvocation(current.copy(status = move.to, completedAt = completedAt,
                    isError = current.isError || move.to == InvocationStatus.UNKNOWN || move.to == InvocationStatus.INTERRUPTED))
                appendEvent(taskId, "invocation", current.invocationId, current.status.name, move.to.name,
                    move.reason, at, "resume-normalize:${eventKey}:${current.invocationId}:${move.to}")
            }
        }
        for (move in recovery.stepTransitions) {
            val current = step(move.stepId) ?: continue
            if (TaskStateRules.stepTransition(current.status, move.to) == TransitionResult.Applied) {
                saveStep(current.copy(status = move.to, statusReason = TaskStateRules.clamp(move.reason, 300), updatedAt = at))
                appendEvent(taskId, "step", current.stepId, current.status.name, move.to.name,
                    move.reason, at, "resume-normalize:${eventKey}:${current.stepId}:${move.to}")
            }
        }
        recovery.taskStatus?.let { target ->
            val current = task(taskId) ?: return@atomic TaskResumeClaimResult.Rejected("Task no longer exists.")
            if (TaskStateRules.taskTransition(current.status, target) == TransitionResult.Applied) {
                updateTask(current.copy(status = target, updatedAt = at))
                appendEvent(taskId, "task", taskId, current.status.name, target.name,
                    "normalized before user-requested resume", at, "resume-normalize:${eventKey}:task")
            }
        }
        if (recovery.decision !in setOf(RecoveryDecision.SAFE_TO_RESUME, RecoveryDecision.SAFE_TO_RETRY)) {
            val reason = recovery.reasons.joinToString("; ").ifBlank { "Recovery policy requires user intervention." }
            appendEvent(taskId, "resume", taskId, initial.status.name, "CLAIM_REJECTED",
                reason, at, "resume-rejected:$eventKey")
            return@atomic TaskResumeClaimResult.Rejected(reason, recovery.decision)
        }
        val current = task(taskId) ?: return@atomic TaskResumeClaimResult.Rejected("Task no longer exists.")
        if (current.status !in setOf(TaskStatus.INTERRUPTED, TaskStatus.INCOMPLETE)) {
            appendEvent(taskId, "resume", taskId, current.status.name, "CLAIM_REJECTED",
                "task state changed before claim", at, "resume-rejected:$eventKey")
            return@atomic TaskResumeClaimResult.Rejected("The task changed while recovery was being prepared.")
        }
        val transition = TaskStateRules.taskTransition(current.status, TaskStatus.RUNNING)
        if (transition != TransitionResult.Applied) {
            return@atomic TaskResumeClaimResult.Rejected((transition as? TransitionResult.Rejected)?.reason ?: "Resume claim was already taken.")
        }
        updateTask(current.copy(status = TaskStatus.RUNNING, updatedAt = at, heartbeatAt = at))
        appendEvent(taskId, "resume", taskId, current.status.name, "RESUME_CLAIMED",
            "exclusive persistent claim acquired", at, "resume-claimed:$eventKey")
        appendEvent(taskId, "task", taskId, current.status.name, TaskStatus.RUNNING.name,
            "user requested resume; exclusive claim acquired", at, eventKey)
        val after = task(taskId) ?: return@atomic TaskResumeClaimResult.Rejected("Task disappeared after claim.")
        val claimed = TaskSnapshot(after, plans(taskId), steps(taskId), cycles(taskId), invocations(taskId))
        TaskResumeClaimResult.Claimed(TaskResumeClaim(claimed, recovery))
    }

    override suspend fun releaseResumeClaim(taskId: String, expectedCycle: Int, at: Long, eventKey: String): TransitionResult = backend.atomic {
        val current = task(taskId) ?: return@atomic TransitionResult.Rejected("unknown task")
        if (hasEvent(taskId, eventKey)) return@atomic TransitionResult.Duplicate
        if (current.status != TaskStatus.RUNNING || current.currentCycle != expectedCycle) {
            return@atomic TransitionResult.Rejected("resume already entered a new execution cycle or changed state")
        }
        updateTask(current.copy(status = TaskStatus.INTERRUPTED, updatedAt = at))
        appendEvent(taskId, "resume", taskId, "RUNNING", "RESUME_FAILED",
            "resume initialization failed before a new cycle started", at, "resume-failed:$eventKey")
        appendEvent(taskId, "task", taskId, TaskStatus.RUNNING.name, TaskStatus.INTERRUPTED.name,
            "resume initialization failed before a new cycle started", at, eventKey)
        TransitionResult.Applied
    }

    override suspend fun recoverTask(taskId: String, at: Long): RecoveryOutcome? =
        backend.atomic { applyRecovery(taskId, at) }

    override suspend fun recoverIfStale(taskId: String, cutoff: Long, liveTaskIds: Set<String>, at: Long): RecoveryOutcome? =
        backend.atomic {
            if (taskId in liveTaskIds) return@atomic null
            val current = task(taskId) ?: return@atomic null
            if (current.status !in setOf(TaskStatus.CREATED, TaskStatus.RUNNING) || current.heartbeatAt >= cutoff) return@atomic null
            applyRecovery(taskId, at)
        }

    private suspend fun TaskStateAccess.applyRecovery(taskId: String, at: Long): RecoveryOutcome? {
        val currentTask = task(taskId) ?: return null
        val snapshot = TaskSnapshot(currentTask, plans(taskId), steps(taskId), cycles(taskId), invocations(taskId))
        val plan = TaskRecoveryPlanner.plan(snapshot)
        appendEvent(taskId, "recovery", taskId, currentTask.status.name, "DISCOVERED",
            "recovery evaluation started", at, "recovery-discovered:$taskId:${currentTask.currentCycle}:${currentTask.status}")
        appendEvent(taskId, "recovery", taskId, "DISCOVERED", plan.decision.name,
            plan.reasons.joinToString("; ").take(300), at, "recovery-decision:$taskId:${currentTask.currentCycle}:${plan.decision}")
        closeInterruptedCycles(taskId, at, "closed by task recovery")
        for (move in plan.invocationTransitions) {
            val current = invocation(move.invocationId) ?: continue
            if (TaskStateRules.invocationTransition(current.status, move.to) != TransitionResult.Applied) continue
            updateInvocation(current.copy(
                status = move.to,
                completedAt = if (move.to == InvocationStatus.CANCELLED) at else current.completedAt,
                isError = current.isError || move.to == InvocationStatus.UNKNOWN || move.to == InvocationStatus.INTERRUPTED
            ))
            appendEvent(taskId, "invocation", current.invocationId, current.status.name, move.to.name,
                move.reason, at, "recover:${current.invocationId}:${move.to}")
        }
        for (move in plan.stepTransitions) {
            val current = step(move.stepId) ?: continue
            if (TaskStateRules.stepTransition(current.status, move.to) != TransitionResult.Applied) continue
            saveStep(current.copy(status = move.to, statusReason = TaskStateRules.clamp(move.reason, 300), updatedAt = at))
            appendEvent(taskId, "step", current.stepId, current.status.name, move.to.name,
                move.reason, at, "recover:${current.stepId}:${move.to}")
        }
        plan.taskStatus?.let { target ->
            val latest = task(taskId) ?: return null
            if (TaskStateRules.taskTransition(latest.status, target) == TransitionResult.Applied) {
                updateTask(latest.copy(status = target, updatedAt = at))
                appendEvent(taskId, "task", taskId, latest.status.name, target.name,
                    "recovered: ${plan.decision}", at, "recover:$taskId:${snapshot.task.currentCycle}")
            }
        }
        return RecoveryOutcome(taskId, plan, applied = true)
    }

    /** Close every abandoned cycle once, inside the same recovery transaction. */
    private suspend fun TaskStateAccess.closeInterruptedCycles(taskId: String, at: Long, reason: String) {
        for (cycle in cycles(taskId)) {
            if (cycle.endedAt != null) continue
            saveCycle(cycle.copy(
                endedAt = at,
                outcome = "interrupted",
                summary = TaskStateRules.clamp(reason, 600)
            ))
            appendEvent(taskId, "cycle", cycle.cycleId, "STARTED", "ENDED:interrupted",
                reason, at, "recover-cycle:${cycle.cycleId}")
        }
    }

    override suspend fun recordEvent(taskId: String, entityType: String, entityId: String, from: String, to: String, reason: String, at: Long, eventKey: String) =
        backend.atomic {
            if (task(taskId) == null) false else appendEvent(taskId, entityType, entityId, from, to,
                TaskStateRules.clamp(reason, 300), at, eventKey)
        }

    override suspend fun eventRecorded(taskId: String, eventKey: String) = backend.atomic { hasEvent(taskId, eventKey) }

    override suspend fun invocation(invocationId: String) = backend.atomic { invocation(invocationId) }

    override suspend fun invocations(taskId: String, limit: Int, offset: Int) = backend.atomic { invocationsPage(taskId, limit, offset) }
    override suspend fun events(taskId: String, limit: Int, offset: Int) = backend.atomic { events(taskId, limit, offset) }

    override suspend fun counts(taskId: String): TaskCounts = backend.atomic {
        val inv = invocationCounts(taskId)
        val latest = plans(taskId).lastOrNull()
        val st = latest?.let { p -> steps(taskId).filter { it.planId == p.planId } }.orEmpty()
        TaskCounts(cycleCount(taskId), inv.values.sum(), inv[InvocationStatus.SUCCEEDED] ?: 0, inv[InvocationStatus.FAILED] ?: 0,
            listOf(InvocationStatus.PENDING, InvocationStatus.RUNNING, InvocationStatus.INTERRUPTED, InvocationStatus.UNKNOWN).sumOf { inv[it] ?: 0 },
            st.size, st.count { it.status == StepStatus.COMPLETED })
    }

    override suspend fun tasksByStatus(statuses: Set<TaskStatus>, limit: Int, offset: Int) = backend.atomic { tasksByStatus(statuses, limit, offset) }
    override suspend fun tasksForSession(sessionId: Long, limit: Int, offset: Int) = backend.atomic { tasksForSession(sessionId, limit, offset) }
    override suspend fun unresolvedSideEffects(taskId: String) = backend.atomic { unresolvedSideEffects(taskId) }
}
