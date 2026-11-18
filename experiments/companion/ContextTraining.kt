package com.omnidev.workspace.ui.companion

import java.io.File
import kotlin.random.Random

// Offline controls. All feedback is simulated; no user's saved companion is loaded.
private val actions = listOf(CompanionAction.REST, CompanionAction.PEEK)
private fun context(state: Int, random: Random) = CompanionMindContext(
    typing = state and 1 != 0, working = state and 2 != 0, hasConsole = true,
    x = random.nextFloat(), y = random.nextFloat(), personality = random.nextInt(3)
)
private fun target(state: Int, profile: Int) =
    (if (state == 2) 1 else 0) xor profile

private class ContextCounts {
    private val count = IntArray(8)
    private val sum = IntArray(8)
    fun update(state: Int, action: Int, reward: Int) {
        val i = state * 2 + action
        count[i]++
        sum[i] += reward
    }
    fun predict(state: Int, action: Int, prior: Float): Float {
        val i = state * 2 + action
        return (sum[i] + 4 * prior.coerceIn(0f, 1f)) / (4 + count[i])
    }
}

fun main(args: Array<String>) {
    val output = File(args.single()).apply { mkdirs() }
    val budgets = listOf(0, 32, 128, 512)
    val totals = budgets.associateWith { IntArray(4) }
    for (profile in 0..1) for (seed in 1..4) {
        val rng = Random(seed)
        val learner = CompanionLearner()
        val counts = ContextCounts()
        for (step in 0..512) {
            val policy = learner.policy()
            if (step in budgets) {
                val eval = Random(10000 + seed)
                val total = totals.getValue(step)
                repeat(64) {
                    val state = it % 4
                    val c = context(state, eval)
                    val scores = actions.map { action -> policy.evaluate(c, action).score }
                    val neural = if (scores[1] > scores[0]) 1 else 0
                    val adapted = if (counts.predict(state, 1, scores[1]) > counts.predict(state, 0, scores[0])) 1 else 0
                    val simple = if (counts.predict(state, 1, .5f) > counts.predict(state, 0, .5f)) 1 else 0
                    val expected = target(state, profile)
                    total[0] += if (neural == expected) 1 else 0
                    total[1] += if (adapted == expected) 1 else 0
                    total[2] += if (simple == expected) 1 else 0
                    total[3]++
                }
            }
            if (step == 512) break
            val state = rng.nextInt(4)
            val action = rng.nextInt(2)
            val c = context(state, rng)
            val label = if (action == target(state, profile)) 1 else 0
            val reward = if (rng.nextFloat() < .1f) 1-label else label
            learner.learn(policy.evaluate(c, actions[action]), reward.toFloat())
            counts.update(state, action, reward)
        }
        val bytes = learner.encode()
        val restored = checkNotNull(CompanionLearner.decode(bytes))
        check(restored.observations == 512 && bytes.size < CompanionLearner.MAX_BYTES)
        val probe = context(3, Random(99))
        for (a in actions) check(restored.policy().evaluate(probe,a).score == learner.policy().evaluate(probe,a).score)
        File(output,"neural-profile-$profile-seed-$seed.bin").writeBytes(bytes)
    }
    val report = buildString {
        appendLine("updates,neural,neural_plus_counts,counts_only,decisions")
        for ((budget,m) in totals) appendLine("$budget," + m.joinToString(","))
    }
    print(report)
    File(output,"accuracy.csv").writeText(report)
}
