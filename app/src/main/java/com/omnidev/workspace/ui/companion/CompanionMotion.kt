package com.omnidev.workspace.ui.companion

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI

/** Pixel geometry supplied by the actual editor/console, never by a guessed screen size. */
internal data class CompanionPerch(val left: Float, val right: Float, val top: Float)
internal data class CompanionScene(val width: Float, val height: Float, val size: Float,
    val composer: CompanionPerch, val console: CompanionPerch? = null) {
    fun valid(perch: CompanionPerch) = width >= size && height >= size &&
        perch.right - perch.left >= size && perch.top >= size && perch.top <= height
    fun x(value: Float, perch: CompanionPerch = composer) = value.coerceIn(
        perch.left.coerceAtLeast(0f), (perch.right - size).coerceAtMost(width - size).coerceAtLeast(perch.left.coerceAtLeast(0f)))
    fun y(perch: CompanionPerch) = (perch.top - size).coerceIn(0f, (height - size).coerceAtLeast(0f))
}

internal enum class CompanionMood { AWAKE, WORKING, SLEEPY, SURPRISED }
internal data class CompanionPose(val x: Float = 0f, val y: Float = 0f, val lift: Float = 0f,
    val rotation: Float = 0f, val stretch: Float = 1f, val blink: Boolean = false,
    val mood: CompanionMood = CompanionMood.AWAKE)

/** Small deterministic timeline; no threads, network requests, timers or agent-side work. */
internal class CompanionMotion {
    var pose = CompanionPose(); private set
    private var scene: CompanionScene? = null
    private var from = pose
    private var destination = pose
    private var time = 0f
    private var elapsed = 0f
    private var resting = 0f
    private var idle = 0f
    private var hops = 0
    private var dragging = false
    private var tumbling = false
    private var flying = false

    fun configure(next: CompanionScene) {
        if (next == scene) return
        scene = next
        pose = pose.copy(x = next.x(if (time == 0f) next.composer.right - next.size * 1.8f else pose.x),
            y = next.y(next.composer), lift = 0f, rotation = 0f, stretch = 1f)
        flying = false; tumbling = false; resting = 0f
    }

    fun tap(reduced: Boolean) {
        idle = 0f; resting = 0f; elapsed = 0f; dragging = false
        tumbling = !reduced; flying = !reduced
        from = pose; destination = pose.copy(rotation = 0f, lift = 0f, stretch = 1f)
        pose = pose.copy(mood = CompanionMood.SURPRISED)
    }

    fun grab() { dragging = true; flying = false; tumbling = false; idle = 0f; pose = pose.copy(rotation = 0f, lift = 0f, stretch = 1f, mood = CompanionMood.SURPRISED) }
    fun drag(dx: Float, dy: Float) {
        val s = scene ?: return
        pose = pose.copy(x = (pose.x + dx).coerceIn(0f, (s.width - s.size).coerceAtLeast(0f)),
            y = (pose.y + dy).coerceIn(0f, (s.height - s.size).coerceAtLeast(0f)))
    }
    fun release(velocityX: Float, reduced: Boolean) {
        val s = scene ?: return
        dragging = false; resting = 0f; idle = 0f
        val p = listOfNotNull(s.composer, s.console).filter(s::valid).minByOrNull { abs(s.y(it) - pose.y) } ?: s.composer
        launch(s.x(pose.x + velocityX.coerceIn(-s.size * 12, s.size * 12) * .12f, p), s.y(p), reduced)
    }

    private fun launch(x: Float, y: Float, reduced: Boolean) {
        from = pose; destination = pose.copy(x = x, y = y, rotation = 0f, stretch = 1f, lift = 0f)
        elapsed = 0f; flying = !reduced; tumbling = false
        if (reduced) pose = destination
    }

    fun step(seconds: Float, working: Boolean, roaming: Boolean, reduced: Boolean): CompanionPose {
        val s = scene ?: return pose
        // Returning from another app never advances a whole flight in one frame.
        val dt = seconds.coerceIn(0f, .05f)
        time += dt
        if (dragging) return pose
        idle = if (working) 0f else idle + dt
        val mood = if (working) CompanionMood.WORKING else if (idle > 24f) CompanionMood.SLEEPY else CompanionMood.AWAKE
        if (reduced) {
            if (flying) pose = destination
            flying = false; tumbling = false
            pose = pose.copy(rotation = 0f, lift = 0f, stretch = 1f, blink = false, mood = mood)
            return pose
        }
        if (flying) {
            elapsed += dt
            val t = (elapsed / if (tumbling) .85f else .72f).coerceIn(0f, 1f)
            val arc = sin(PI.toFloat() * t)
            val lift = arc * s.size * if (tumbling) .25f else .85f
            pose = pose.copy(x = from.x + (destination.x - from.x) * t,
                y = (from.y + (destination.y - from.y) * t - lift).coerceAtLeast(0f),
                lift = lift, rotation = if (tumbling) when { t < .25f -> t / .25f * 95f; t < .6f -> 95f; else -> 95f + (t - .6f) / .4f * 265f } else sin(t * PI.toFloat() * 2) * -7f,
                stretch = if (tumbling) 1f else 1f + arc * .12f - (if (t > .85f) sin((t - .85f) / .15f * PI.toFloat()) * .22f else 0f),
                mood = if (tumbling) CompanionMood.SURPRISED else mood, blink = false)
            if (t >= 1f) { flying = false; tumbling = false; pose = destination.copy(mood = mood); resting = 0f }
        } else {
            resting += dt
            pose = pose.copy(mood = mood, blink = time % 4.6f > 4.42f)
            if (roaming && mood != CompanionMood.SLEEPY && resting > if (working) 3.5f else 6f) {
                hops++
                val p = if (working && hops % 2 == 1) s.console?.takeIf(s::valid) ?: s.composer else s.composer
                val fraction = if (hops % 2 == 1) .22f else .72f
                launch(s.x(p.left + (p.right - p.left - s.size) * fraction, p), s.y(p), false)
            }
        }
        return pose
    }
}
