package com.omnidev.workspace.ui.companion

import kotlin.math.exp
import kotlin.random.Random

internal data class CompanionFeeling(
    val energy: Float = .7f, val familiarity: Float = .05f, val irritation: Float = 0f,
    val sadness: Float = 0f, val joy: Float = 0f
) {
    fun values() = floatArrayOf(energy, familiarity, irritation, sadness, joy)
    fun afterAbsence(seconds: Float): CompanionFeeling {
        val elapsed = seconds.coerceIn(0f, 86400f)
        return copy(energy = .7f + (energy - .7f) * exp(-elapsed / 180f),
            irritation = irritation * exp(-elapsed / 35f), sadness = sadness * exp(-elapsed / 70f),
            joy = joy * exp(-elapsed / 8f))
    }
    companion object { fun from(v: FloatArray) = CompanionFeeling(v[0], v[1], v[2], v[3], v[4]) }
}

/** Session-local attention and affect; learned weights and bounded experiences belong to the store. */
internal class CompanionMind(
    initial: CompanionFeeling = CompanionFeeling(),
    private val policy: () -> CompanionPolicy = { CompanionPolicy() },
    private val learn: (CompanionDecision, Float) -> Unit = { _, _ -> },
    private val remember: (CompanionFeeling) -> Unit = {},
    seed: Int = Random.nextInt()
) {
    var learning = true
    var expressive = true
    var personality = CompanionPersonality.CURIOUS
    var feeling = initial; private set
    private var restored = false
    private var interacted = false
    private var context = CompanionMindContext()
    private var pending: CompanionDecision? = null
    private var pendingAge = 0f
    private var time = 0f
    private var saved = 0f
    private var lastTouch = -10f
    private var burst = 0
    private var placementContext: CompanionMindContext? = null
    private val random = Random(seed)
    private val recent = ArrayDeque<CompanionAction>()
    val canFeedback get() = learning && pending != null && pendingAge <= 20f
    fun startingSide(): CompanionAction? {
        if (!learning) return null
        val model = policy()
        if (model.observations < 4) return null
        return model.choose(context.copy(feeling = feeling), listOf(CompanionAction.LEFT, CompanionAction.RIGHT))?.action
    }
    val wantsSpace get() = expressive && feeling.irritation > .22f
    val mood: CompanionMood? get() = if (!expressive) null else when {
        feeling.irritation > .48f -> CompanionMood.ANNOYED
        feeling.irritation > .22f -> CompanionMood.GUARDED
        feeling.sadness > .22f -> CompanionMood.SAD
        feeling.joy > .18f -> CompanionMood.HAPPY
        else -> null
    }
    fun restore(value: CompanionFeeling): Boolean {
        if (restored) return false
        restored = true
        feeling = if (!interacted) value else feeling.copy(
            familiarity = (value.familiarity + feeling.familiarity - .05f).coerceIn(0f, 1f))
        return !interacted
    }
    fun context(value: CompanionMindContext) { context = value.copy(feeling = feeling, personality = personality.ordinal) }
    fun step(dt: Float) {
        val seconds = dt.coerceIn(0f, .25f)
        time += seconds; saved += seconds; pendingAge += seconds
        feeling = feeling.copy(
            energy = (feeling.energy + seconds * if (context.working || context.typing) -.001f else .002f).coerceIn(.15f, 1f),
            irritation = feeling.irritation * exp(-seconds / 35f),
            sadness = feeling.sadness * exp(-seconds / 70f), joy = feeling.joy * exp(-seconds / 8f))
        // No interaction is missing feedback, never a negative training label.
        if (pendingAge > 20f) pending = null
        if (saved >= 15f) { remember(feeling); saved = 0f }
    }
    fun choose(allowed: List<CompanionAction>): CompanionAction? {
        if (!learning || allowed.isEmpty()) return null
        val model = policy()
        val candidates = allowed.distinct().map { model.evaluate(context.copy(feeling = feeling), it) }
        // A gentle behavioral prior and limited novelty avoid random cold-start habits.
        val weight = (model.observations / 24f).coerceIn(0f, 1f)
        fun prior(action: CompanionAction): Float = when {
            wantsSpace && action in listOf(CompanionAction.LEFT, CompanionAction.RIGHT, CompanionAction.REST) -> .7f
            context.typing && action == CompanionAction.REST -> .8f
            context.working && action == CompanionAction.CONSOLE -> .7f
            feeling.energy < .4f && action in listOf(CompanionAction.REST, CompanionAction.YAWN) -> .7f
            action in listOf(CompanionAction.PEEK, CompanionAction.SNIFF, CompanionAction.MESSAGE) -> .55f
            else -> .5f
        }
        val selected = candidates.maxByOrNull { candidate ->
            candidate.score * weight + prior(candidate.action) * (1f - weight) +
                (if (candidate.action !in recent) .04f else 0f) + random.nextFloat() * .025f
        } ?: return null
        pending = selected; pendingAge = 0f
        if (recent.size == 4) recent.removeFirst()
        recent.addLast(selected.action)
        return selected.action
    }
    /** Explicit controls attribute feedback to the recent autonomous choice only. */
    fun feedback(liked: Boolean): Boolean {
        val choice = pending ?: return false
        if (!learning || pendingAge > 20f) return false
        learn(choice, if (liked) 1f else 0f); pending = null
        return true
    }
    /** A touch is affect, not an automatic like/dislike label. Rapid repeated taps can annoy it. */
    fun tap(): Boolean {
        interacted = true
        burst = if (time - lastTouch < .7f) burst + 1 else 1
        lastTouch = time
        val irritated = if (burst >= 3) (feeling.irritation + .28f).coerceAtMost(1f) else feeling.irritation
        feeling = feeling.copy(irritation = irritated,
            familiarity = (feeling.familiarity + if (learning) .003f else 0f).coerceAtMost(1f),
            joy = if (irritated > .22f) 0f else (feeling.joy + .3f).coerceAtMost(1f),
            energy = (feeling.energy - .015f).coerceAtLeast(.15f))
        remember(feeling)
        return !wantsSpace
    }
    fun pickup() { interacted = true; placementContext = context.copy(feeling = feeling) }
    /** Deliberate gentle placement teaches a physical side; fling/gesture cancellation do not. */
    fun placed(x: Float, gentle: Boolean) {
        val before = placementContext; placementContext = null
        if (!gentle || before == null || !learning) return
        val action = if (x < .5f) CompanionAction.LEFT else CompanionAction.RIGHT
        learn(policy().evaluate(before, action), .95f)
        feeling = feeling.copy(familiarity = (feeling.familiarity + .005f).coerceAtMost(1f))
        remember(feeling)
    }
    fun tossed() {
        placementContext = null
        feeling = feeling.copy(energy = (feeling.energy - .04f).coerceAtLeast(.15f),
            irritation = (feeling.irritation + .14f).coerceAtMost(1f), joy = 0f)
        remember(feeling)
    }
    fun cancelledPickup() { placementContext = null }
    fun hidden() { interacted = true; pending = null; feeling = feeling.copy(sadness = .4f, joy = 0f); remember(feeling) }
    fun welcomed() {
        interacted = true
        burst = 0; lastTouch = -10f
        feeling = feeling.copy(irritation = 0f, sadness = 0f, joy = .8f,
            familiarity = (feeling.familiarity + if (learning) .01f else 0f).coerceAtMost(1f))
        remember(feeling)
    }
    fun settle() { feeling = feeling.copy(irritation = 0f, sadness = 0f, joy = .3f); remember(feeling) }
    fun flush() { remember(feeling) }
}
