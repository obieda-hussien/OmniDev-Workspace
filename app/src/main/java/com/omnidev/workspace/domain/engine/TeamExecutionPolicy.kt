package com.omnidev.workspace.domain.engine

import kotlin.math.ceil

/**
 * Deterministic local policy for Team execution.
 *
 * Goals:
 * - spend tokens proportional to task difficulty instead of giving every worker a huge budget
 * - distrust planner parallelSafe metadata when task text clearly mutates shared state
 * - schedule ready tasks in conflict-free waves using inferred resource keys
 * - preserve planner priority across exclusive mutation barriers
 * - keep the policy cheap enough to run on every planning cycle without an LLM call
 */
object TeamExecutionPolicy {

    data class WorkerBudget(
        val maxIterations: Int,
        val tokenBudget: Int,
        val repeatedToolLimit: Int,
        val complexity: Float,
        val reason: String
    )

    data class ClassifiedTask(
        val task: SwarmTask,
        val effectiveParallelSafe: Boolean,
        val resourceKeys: Set<String>,
        val mutationScore: Float
    )

    private const val MIN_WORKER_TOKENS = 22_000
    private const val MAX_WORKER_TOKENS = 82_000
    private const val MIN_ITERATIONS = 6
    private const val MAX_ITERATIONS = 15
    private const val HARD_MUTATION_THRESHOLD = 0.42f
    private const val BORDERLINE_MUTATION_THRESHOLD = 0.15f

    fun budgetFor(task: SwarmTask): WorkerBudget {
        val signals = IntentClassifier.analyze(task.description)
        val researchBoost = signals.researchIntent * 0.10f
        val verificationBoost = signals.verificationIntent * 0.08f
        val mutationBoost = signals.mutationIntent * 0.08f
        val complexity = (
            signals.structuralComplexity * 0.52f +
                signals.complexity * 0.28f +
                researchBoost + verificationBoost + mutationBoost
            ).coerceIn(0f, 1f)

        val tokenBudget = (
            MIN_WORKER_TOKENS +
                (MAX_WORKER_TOKENS - MIN_WORKER_TOKENS) * complexity
            ).toInt().coerceIn(MIN_WORKER_TOKENS, MAX_WORKER_TOKENS)

        val rawIterations = MIN_ITERATIONS +
            (MAX_ITERATIONS - MIN_ITERATIONS) * complexity
        val maxIterations = ceil(rawIterations.toDouble()).toInt()
            .coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)

        val repeatedToolLimit = when {
            complexity < 0.30f -> 2
            complexity < 0.65f -> 3
            else -> 4
        }

        val reason = when {
            complexity < 0.30f -> "small atomic worker"
            complexity < 0.65f -> "medium worker"
            else -> "complex worker"
        }

        return WorkerBudget(
            maxIterations = maxIterations,
            tokenBudget = tokenBudget,
            repeatedToolLimit = repeatedToolLimit,
            complexity = complexity,
            reason = reason
        )
    }

    /** Planner output is advisory; runtime independently checks mutation risk. */
    fun classify(task: SwarmTask): ClassifiedTask {
        val signals = IntentClassifier.analyze(task.description)
        val lower = task.description.lowercase()
        val mutationText = stripNegatedMutationPhrases(lower)
        val explicitMutation = MUTATION_HINTS.any(mutationText::contains)
        val mutationScore = maxOf(
            signals.mutationIntent * if (containsReadOnlyNegation(lower)) 0.35f else 1f,
            if (explicitMutation) 0.78f else 0f
        )
        val effectiveParallel = task.parallelSafe && mutationScore < HARD_MUTATION_THRESHOLD

        return ClassifiedTask(
            task = task,
            effectiveParallelSafe = effectiveParallel,
            resourceKeys = inferResourceKeys(task.description),
            mutationScore = mutationScore
        )
    }

    /**
     * Ordered conflict-aware wave builder for one ready DAG frontier.
     *
     * Only adjacent compatible read-only tasks are packed into a wave. An exclusive/mutating task
     * is a hard barrier: later tasks are never moved to a wave before it. This preserves planner
     * priority while still exploiting safe concurrency.
     */
    fun buildExecutionWaves(
        readyTasks: List<SwarmTask>,
        maxParallelWorkers: Int
    ): List<List<SwarmTask>> {
        if (readyTasks.isEmpty()) return emptyList()
        val limit = maxParallelWorkers.coerceAtLeast(1)
        val classified = readyTasks
            .sortedWith(compareBy<SwarmTask> { it.priority }.thenBy { it.id })
            .map(::classify)

        val waves = mutableListOf<MutableList<ClassifiedTask>>()
        for (candidate in classified) {
            if (!candidate.effectiveParallelSafe) {
                waves += mutableListOf(candidate)
                continue
            }

            // Only the immediately preceding wave may absorb this task. Searching older waves
            // would reorder it across an exclusive barrier or an intentional priority boundary.
            val lastWave = waves.lastOrNull()
            val canJoinLast = lastWave != null &&
                lastWave.size < limit &&
                lastWave.all { existing ->
                    existing.effectiveParallelSafe && !resourcesConflict(existing, candidate)
                }

            if (canJoinLast) lastWave!!.add(candidate)
            else waves += mutableListOf(candidate)
        }

        return waves.map { wave -> wave.map { it.task } }
    }

    fun canRunConcurrently(a: SwarmTask, b: SwarmTask): Boolean {
        val ca = classify(a)
        val cb = classify(b)
        return ca.effectiveParallelSafe && cb.effectiveParallelSafe && !resourcesConflict(ca, cb)
    }

    private fun resourcesConflict(a: ClassifiedTask, b: ClassifiedTask): Boolean {
        if (a.resourceKeys.isEmpty() || b.resourceKeys.isEmpty()) return false
        if (a.mutationScore < BORDERLINE_MUTATION_THRESHOLD &&
            b.mutationScore < BORDERLINE_MUTATION_THRESHOLD
        ) return false
        return a.resourceKeys.any { it in b.resourceKeys }
    }

    private fun stripNegatedMutationPhrases(value: String): String = value
        .replace(NEGATED_MUTATION_REGEX, " read-only ")
        .replace("read-only", "readonly")
        .replace("read only", "readonly")
        .replace("بدون تعديل", "قراءة فقط")
        .replace("من غير تعديل", "قراءة فقط")
        .replace("لا تعدل", "قراءة فقط")
        .replace("لا تكتب", "قراءة فقط")

    private fun containsReadOnlyNegation(value: String): Boolean =
        NEGATED_MUTATION_REGEX.containsMatchIn(value) ||
            listOf("read-only", "read only", "without changes", "بدون تعديل", "من غير تعديل", "لا تعدل", "قراءة فقط")
                .any(value::contains)

    private fun inferResourceKeys(description: String): Set<String> {
        val lower = description.lowercase()
        val keys = linkedSetOf<String>()

        PATH_REGEX.findAll(description).forEach { match ->
            match.value
                .trim('`', '\'', '"', ',', '.', ';', ':', ')', '(', '[', ']')
                .replace('\\', '/')
                .lowercase()
                .takeIf { it.length >= 3 }
                ?.let { keys += "path:$it" }
        }

        DOMAIN_KEYS.forEach { (key, hints) ->
            if (hints.any(lower::contains)) keys += "domain:$key"
        }

        IDENTIFIER_REGEX.findAll(description).take(8).forEach { match ->
            val value = match.value.lowercase()
            if (value.contains('.') && value.length <= 120) keys += "symbol:$value"
        }

        return keys.take(12).toSet()
    }

    private val MUTATION_HINTS = listOf(
        "write", "edit", "modify", "patch", "delete", "remove", "create", "implement",
        "refactor", "migrate", "install", "uninstall", "deploy", "commit", "push", "merge",
        "rename", "move", "grant", "revoke", "set ", "update ",
        "اكتب", "عدل", "احذف", "انشئ", "أنشئ", "صلح", "ثبت", "غيّر", "غير"
    )

    private val NEGATED_MUTATION_REGEX = Regex(
        "\\b(?:without|do\\s+not|don't|dont|never)\\s+(?:edit(?:ing)?|write|modify(?:ing)?|change(?:s|ing)?|patch(?:ing)?|delete|remove)\\b"
    )

    private val DOMAIN_KEYS = linkedMapOf(
        "database" to listOf("database", "sqlite", "room", "dao", "migration", "قاعدة بيانات"),
        "ui" to listOf("compose", "screen", "ui", "viewmodel", "واجهة", "شاشة"),
        "gradle" to listOf("gradle", "build.gradle", "settings.gradle", "dependency"),
        "manifest" to listOf("androidmanifest", "manifest", "permission"),
        "network" to listOf(" api ", "network", "retrofit", "okhttp", "http"),
        "tests" to listOf("test", "junit", "espresso", "اختبار"),
        "device" to listOf("shizuku", " adb ", "rish", "root", "device", "system setting")
    )

    private val PATH_REGEX = Regex(
        "(?:[A-Za-z]:)?(?:[/\\\\][A-Za-z0-9_.@+\\-]+){2,}|" +
            "(?:[A-Za-z0-9_.@+\\-]+[/\\\\]){1,}[A-Za-z0-9_.@+\\-]+"
    )

    private val IDENTIFIER_REGEX = Regex(
        "\\b[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*){1,6}\\b"
    )
}
