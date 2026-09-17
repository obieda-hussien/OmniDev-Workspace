package com.omnidev.workspace.domain.engine

import kotlin.math.ceil

/**
 * Deterministic local policy for Team execution.
 *
 * Goals:
 * - spend tokens proportional to task difficulty instead of giving every worker a huge budget
 * - distrust planner parallelSafe metadata when task text clearly mutates shared state
 * - schedule ready tasks in conflict-free waves using inferred resource keys
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

    /**
     * Budget grows sub-linearly with difficulty. Broad/parallel context does not automatically
     * inflate a worker: the worker should receive one atomic slice, not the original whole task.
     */
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

        val maxIterations = ceil(
            MIN_ITERATIONS + (MAX_ITERATIONS - MIN_ITERATIONS) * complexity
        ).toInt().coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)

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

    /**
     * Planner output is untrusted metadata. We independently classify mutation and resource
     * overlap before allowing concurrency.
     */
    fun classify(task: SwarmTask): ClassifiedTask {
        val signals = IntentClassifier.analyze(task.description)
        val lower = task.description.lowercase()
        val explicitMutation = MUTATION_HINTS.any(lower::contains)
        val mutationScore = maxOf(
            signals.mutationIntent,
            if (explicitMutation) 0.78f else 0f
        )
        val effectiveParallel = task.parallelSafe && mutationScore < 0.42f

        return ClassifiedTask(
            task = task,
            effectiveParallelSafe = effectiveParallel,
            resourceKeys = inferResourceKeys(task.description),
            mutationScore = mutationScore
        )
    }

    /**
     * Greedy conflict-aware coloring for a single ready DAG frontier.
     *
     * Every returned inner list is one concurrently executable wave. Mutating tasks are always
     * singleton waves. Read-only tasks can share a wave only when their inferred exclusive
     * resources do not overlap. The algorithm is O(n^2) with n <= 6 in Team mode.
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

            val targetWave = waves.firstOrNull { wave ->
                wave.size < limit &&
                    wave.all { existing ->
                        existing.effectiveParallelSafe && !resourcesConflict(existing, candidate)
                    }
            }
            if (targetWave != null) targetWave += candidate
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
        return a.resourceKeys.any { it in b.resourceKeys }
    }

    /**
     * Extracts conservative resource identities from task text. File paths are the strongest
     * signal; architecture domains are fallback keys. Read-only overlap is safe, but these keys
     * become useful when a planner accidentally labels a mutation as parallel-safe.
     */
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

        // Package/class-looking identifiers help separate Android components even when no path
        // was included in the planner description.
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

    private val DOMAIN_KEYS = linkedMapOf(
        "database" to listOf("database", "sqlite", "room", "dao", "migration", "قاعدة بيانات"),
        "ui" to listOf("compose", "screen", "ui", "viewmodel", "واجهة", "شاشة"),
        "gradle" to listOf("gradle", "build.gradle", "settings.gradle", "dependency"),
        "manifest" to listOf("androidmanifest", "manifest", "permission"),
        "network" to listOf("api", "network", "retrofit", "okhttp", "http"),
        "tests" to listOf("test", "junit", "espresso", "اختبار"),
        "device" to listOf("shizuku", "adb", "rish", "root", "device", "system setting")
    )

    private val PATH_REGEX = Regex(
        "(?:[A-Za-z]:)?(?:[/\\\\][A-Za-z0-9_.@+\\-]+){2,}|" +
            "(?:[A-Za-z0-9_.@+\\-]+[/\\\\]){1,}[A-Za-z0-9_.@+\\-]+"
    )

    private val IDENTIFIER_REGEX = Regex(
        "\\b[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*){1,6}\\b"
    )
}
