package com.omnidev.workspace.domain.engine

import kotlin.math.ceil

/** Deterministic local policy for Team budgeting, plan cleanup and safe scheduling. */
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

    data class NormalizedPlan(
        val tasks: List<SwarmTask>,
        val aliases: Map<String, String>
    ) {
        val removedTaskCount: Int get() = aliases.size
    }

    private const val MIN_WORKER_TOKENS = 22_000
    private const val MAX_WORKER_TOKENS = 82_000
    private const val MIN_ITERATIONS = 6
    private const val MAX_ITERATIONS = 15
    private const val HARD_MUTATION_THRESHOLD = 0.42f
    private const val BORDERLINE_MUTATION_THRESHOLD = 0.15f
    private const val DUPLICATE_SIMILARITY = 0.84f
    private const val STRONG_DUPLICATE_SIMILARITY = 0.93f

    fun budgetFor(task: SwarmTask): WorkerBudget {
        val signals = IntentClassifier.analyze(task.description)
        val complexity = (
            signals.structuralComplexity * 0.52f +
                signals.complexity * 0.28f +
                signals.researchIntent * 0.10f +
                signals.verificationIntent * 0.08f +
                signals.mutationIntent * 0.08f
            ).coerceIn(0f, 1f)

        val tokenBudget = (
            MIN_WORKER_TOKENS + (MAX_WORKER_TOKENS - MIN_WORKER_TOKENS) * complexity
            ).toInt().coerceIn(MIN_WORKER_TOKENS, MAX_WORKER_TOKENS)
        val maxIterations = ceil(
            (MIN_ITERATIONS + (MAX_ITERATIONS - MIN_ITERATIONS) * complexity).toDouble()
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
        return WorkerBudget(maxIterations, tokenBudget, repeatedToolLimit, complexity, reason)
    }

    /**
     * Removes near-duplicate planner tasks before any worker starts. Dependencies that referenced a
     * removed task are rewired to its canonical task, and dependency sets from duplicates are
     * merged. This prevents wording variations from becoming redundant workers.
     */
    fun normalizePlan(tasks: List<SwarmTask>): NormalizedPlan {
        if (tasks.size <= 1) return NormalizedPlan(tasks, emptyMap())

        val canonical = mutableListOf<SwarmTask>()
        val aliases = linkedMapOf<String, String>()

        tasks.sortedWith(compareBy<SwarmTask> { it.priority }.thenBy { it.id }).forEach { candidate ->
            val duplicateIndex = canonical.indexOfFirst { existing -> areDuplicates(existing, candidate) }
            if (duplicateIndex < 0) {
                canonical += candidate
            } else {
                val existing = canonical[duplicateIndex]
                aliases[candidate.id] = existing.id
                canonical[duplicateIndex] = existing.copy(
                    priority = minOf(existing.priority, candidate.priority),
                    dependencies = (existing.dependencies + candidate.dependencies).distinct(),
                    needsConnectedTools = existing.needsConnectedTools || candidate.needsConnectedTools,
                    parallelSafe = existing.parallelSafe && candidate.parallelSafe,
                    requiredPersona = existing.requiredPersona.ifBlank { candidate.requiredPersona }
                )
            }
        }

        fun resolveAlias(id: String): String {
            var current = id
            val seen = mutableSetOf<String>()
            while (current in aliases && seen.add(current)) current = aliases.getValue(current)
            return current
        }

        val rewired = canonical.map { task ->
            task.copy(
                dependencies = task.dependencies
                    .map(::resolveAlias)
                    .filter { it != task.id }
                    .distinct()
            )
        }
        return NormalizedPlan(rewired, aliases)
    }

    fun classify(task: SwarmTask): ClassifiedTask {
        val signals = IntentClassifier.analyze(task.description)
        val lower = task.description.lowercase()
        val mutationText = stripNegatedMutationPhrases(lower)
        val explicitMutation = MUTATION_HINTS.any(mutationText::contains)
        val mutationScore = maxOf(
            signals.mutationIntent * if (containsReadOnlyNegation(lower)) 0.35f else 1f,
            if (explicitMutation) 0.78f else 0f
        )
        return ClassifiedTask(
            task = task,
            effectiveParallelSafe = task.parallelSafe && mutationScore < HARD_MUTATION_THRESHOLD,
            resourceKeys = inferResourceKeys(task.description),
            mutationScore = mutationScore
        )
    }

    /** Adjacent compatible read-only tasks share a wave; mutation is an ordering barrier. */
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
            val lastWave = waves.lastOrNull()
            val canJoin = lastWave != null && lastWave.size < limit &&
                lastWave.all { it.effectiveParallelSafe && !resourcesConflict(it, candidate) }
            if (canJoin) lastWave!!.add(candidate) else waves += mutableListOf(candidate)
        }
        return waves.map { wave -> wave.map { it.task } }
    }

    fun canRunConcurrently(a: SwarmTask, b: SwarmTask): Boolean {
        val ca = classify(a)
        val cb = classify(b)
        return ca.effectiveParallelSafe && cb.effectiveParallelSafe && !resourcesConflict(ca, cb)
    }

    private fun areDuplicates(a: SwarmTask, b: SwarmTask): Boolean {
        val similarity = descriptionSimilarity(a.description, b.description)
        if (similarity < DUPLICATE_SIMILARITY) return false
        val ca = classify(a)
        val cb = classify(b)
        val sameMutationClass = (ca.mutationScore >= HARD_MUTATION_THRESHOLD) ==
            (cb.mutationScore >= HARD_MUTATION_THRESHOLD)
        if (!sameMutationClass) return false
        val resourceOverlap = ca.resourceKeys.isNotEmpty() &&
            ca.resourceKeys.any { it in cb.resourceKeys }
        return resourceOverlap || similarity >= STRONG_DUPLICATE_SIMILARITY
    }

    internal fun descriptionSimilarity(a: String, b: String): Float {
        val left = semanticTokens(a)
        val right = semanticTokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0f
        val intersection = left.count { it in right }
        val union = (left + right).size
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }

    private fun semanticTokens(value: String): Set<String> = TOKEN.findAll(value.lowercase())
        .map { it.value }
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .map { token ->
            when {
                token.endsWith("ing") && token.length > 5 -> token.dropLast(3)
                token.endsWith("ed") && token.length > 4 -> token.dropLast(2)
                token.endsWith("s") && token.length > 4 -> token.dropLast(1)
                else -> token
            }
        }
        .toSet()

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
    private val TOKEN = Regex("[a-z0-9_./-]+|[\\p{L}\\p{N}_-]+")
    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "into", "from", "this", "that", "task", "worker",
        "please", "need", "using", "use", "check", "make", "fix",
        "عايز", "اعمل", "خلي", "على", "من", "في", "ده", "هذه", "هذا"
    )
}
