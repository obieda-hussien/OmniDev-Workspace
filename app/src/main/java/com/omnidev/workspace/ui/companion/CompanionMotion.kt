package com.omnidev.workspace.ui.companion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

internal data class CompanionPerch(val left: Float, val right: Float, val top: Float, val id: String = "")
internal data class CompanionScene(
    val width: Float, val height: Float, val size: Float, val composer: CompanionPerch,
    val console: CompanionPerch? = null, val messages: List<CompanionPerch> = emptyList(),
    val viewportTop: Float = 0f, val viewportBottom: Float = height
) {
    val platforms: List<CompanionPerch> = listOf(composer.copy(id = "composer")) +
        listOfNotNull(console?.let { it.copy(id = it.id.ifBlank { "console" }) }) + messages
    fun valid(p: CompanionPerch): Boolean = width >= size && height >= size &&
        p.right - p.left >= size * .5f && p.top >= size && p.top <= height &&
        (p.id == "composer" || p == composer || (p.top - size >= viewportTop && p.top <= viewportBottom))
    fun safe(p: CompanionPerch): Boolean = valid(p) && (p.id == "composer" || p == composer ||
        (p.top - size >= viewportTop + size * .3f && p.top <= viewportBottom - size * .15f))
    fun x(value: Float, p: CompanionPerch = composer): Float {
        val limit = (width - size).coerceAtLeast(0f)
        if (p.right - p.left < size) return ((p.left + p.right - size) / 2).coerceIn(0f, limit)
        val left = p.left.coerceIn(0f, limit)
        val right = (p.right - size).coerceIn(left, limit)
        return value.coerceIn(left, right)
    }
    fun y(p: CompanionPerch) = (p.top - size).coerceIn(0f, (height - size).coerceAtLeast(0f))
}

internal enum class CompanionActivity { IDLE, THINKING, TOOL, WAITING, LISTENING, SUCCESS, ERROR }
internal enum class CompanionMood { AWAKE, WORKING, FOCUSED, WAITING, LISTENING, HAPPY, CONCERNED, SLEEPY, SURPRISED }
internal data class CompanionPose(
    val x: Float = 0f, val y: Float = 0f, val lift: Float = 0f, val rotation: Float = 0f,
    val stretch: Float = 1f, val blink: Boolean = false, val mood: CompanionMood = CompanionMood.AWAKE,
    val lookX: Float = 0f, val lookY: Float = 0f, val earTilt: Float = 0f, val sparkle: Float = 0f
)

/** Stable platform identities survive scrolling; geometry updates never restart unrelated flights. */
internal class CompanionMotion {
    private enum class Motion { REST, HOP, TUMBLE, THROW, DRAG }
    var pose = CompanionPose(); private set
    var perchId: String? = null; private set
    val isAirborne get() = motion == Motion.HOP || motion == Motion.THROW || motion == Motion.TUMBLE
    private var scene: CompanionScene? = null
    private var motion = Motion.REST
    private var from = pose
    private var destination = pose
    private var targetId: String? = null
    private var elapsed = 0f
    private var time = 0f
    private var resting = 0f
    private var idle = 0f
    private var landing = 1f
    private var hops = 0
    private var vx = 0f
    private var vy = 0f
    private var lastWorking = false
    private var failedRun = false
    private var celebration = 0f
    private var gazeX = 0f
    private var gazeY = 0f
    private var gazeRemaining = 0f

    fun configure(next: CompanionScene, reduced: Boolean = false) {
        if (next == scene) return
        val first = scene == null
        scene = next
        val composer = next.platforms.first()
        if (first) {
            perchId = composer.id
            pose = pose.copy(x = next.x(composer.right - next.size * 1.8f), y = next.y(composer))
            return
        }
        pose = pose.copy(x = pose.x.coerceIn(0f, (next.width - next.size).coerceAtLeast(0f)),
            y = pose.y.coerceIn(next.viewportTop.coerceAtMost((next.composer.top - next.size).coerceAtLeast(0f)), (next.composer.top - next.size).coerceAtLeast(0f)))
        if (motion == Motion.DRAG || motion == Motion.THROW) return
        if (motion == Motion.HOP || motion == Motion.TUMBLE) {
            val target = next.platforms.firstOrNull { it.id == targetId && next.safe(it) }
            if (target == null) { idle = 0f; hop(escapeTarget(next), reduced = reduced, immediate = true) }
            else destination = destination.copy(x = next.x(destination.x, target), y = next.y(target))
            return
        }
        val standing = next.platforms.firstOrNull { it.id == perchId }
        if (standing != null && next.safe(standing)) {
            pose = pose.copy(x = next.x(pose.x, standing), y = next.y(standing))
        } else {
            idle = 0f
            // Leave before the body is clipped. The launch begins at the current visible position.
            if (standing != null && next.valid(standing)) pose = pose.copy(y = next.y(standing))
            hop(escapeTarget(next), reduced = reduced, immediate = true)
        }
    }

    private fun escapeTarget(s: CompanionScene): CompanionPerch = s.platforms
        .filter { s.safe(it) && it.id != perchId && it.id != targetId }
        .minByOrNull { abs(s.y(it) - pose.y) + abs(s.x(pose.x, it) - pose.x) * .35f }
        ?: s.platforms.first()

    fun lookAt(x: Float, y: Float, wake: Boolean = false) {
        if (wake) idle = 0f
        val s = scene ?: return
        gazeX = ((x - pose.x - s.size / 2) / s.size).coerceIn(-1f, 1f)
        gazeY = ((y - pose.y - s.size * .6f) / s.size).coerceIn(-1f, 1f)
        gazeRemaining = 1.8f
    }

    fun tap(reduced: Boolean) {
        idle = 0f; resting = 0f; elapsed = 0f
        val s = scene ?: return
        val p = s.platforms.firstOrNull { it.id == perchId && s.safe(it) } ?: escapeTarget(s)
        from = pose; destination = pose.copy(x = s.x(pose.x, p), y = s.y(p), rotation = 0f, lift = 0f, stretch = 1f)
        targetId = p.id; perchId = null
        motion = if (reduced) Motion.REST else Motion.TUMBLE
        pose = if (reduced) destination.copy(mood = CompanionMood.SURPRISED) else pose.copy(mood = CompanionMood.SURPRISED)
        if (reduced) perchId = p.id
    }

    fun grab() {
        motion = Motion.DRAG; idle = 0f; perchId = null; targetId = null
        pose = pose.copy(rotation = 0f, lift = 0f, stretch = 1.08f, mood = CompanionMood.SURPRISED)
    }
    fun drag(dx: Float, dy: Float) {
        val s = scene ?: return
        pose = pose.copy(x = (pose.x + dx).coerceIn(0f, (s.width - s.size).coerceAtLeast(0f)),
            y = (pose.y + dy).coerceIn(s.viewportTop.coerceAtMost((s.composer.top - s.size).coerceAtLeast(0f)), (s.composer.top - s.size).coerceAtLeast(0f)),
            earTilt = (-dx / s.size * 60f).coerceIn(-18f, 18f))
    }
    fun release(velocityX: Float, reduced: Boolean, velocityY: Float = 0f) {
        val s = scene ?: return
        idle = 0f; resting = 0f; elapsed = 0f
        val p = s.platforms.filter(s::safe).minByOrNull { abs(s.y(it) - pose.y) + abs(s.x(pose.x, it) - pose.x) * .3f } ?: s.platforms.first()
        if (reduced || (abs(velocityX) + abs(velocityY) < s.size * 2)) {
            hop(p, pose.x, reduced, immediate = true)
        } else {
            motion = Motion.THROW; targetId = null
            vx = velocityX.coerceIn(-s.size * 12f, s.size * 12f)
            vy = velocityY.coerceIn(-s.size * 12f, s.size * 12f)
            pose = pose.copy(stretch = 1f, rotation = 0f)
        }
    }
    fun returnToComposer(reduced: Boolean) {
        val s = scene ?: return
        hop(s.platforms.first(), reduced = reduced, immediate = true)
    }
    private fun hop(p: CompanionPerch, x: Float? = null, reduced: Boolean, immediate: Boolean = false) {
        val s = scene ?: return
        pose = pose.copy(lift = 0f, rotation = 0f, stretch = 1f, earTilt = 0f)
        from = pose; targetId = p.id; perchId = null
        destination = pose.copy(x = s.x(x ?: (p.left + (p.right - p.left - s.size) * if (hops % 2 == 1) .22f else .72f), p),
            y = s.y(p), rotation = 0f, lift = 0f, stretch = 1f, earTilt = 0f)
        elapsed = if (immediate) 0f else -.1f
        motion = if (reduced) Motion.REST else Motion.HOP
        if (reduced) { pose = destination; perchId = p.id }
    }
    private fun land(p: CompanionPerch) {
        val s = scene ?: return
        perchId = p.id; targetId = null; motion = Motion.REST
        resting = 0f; landing = 0f
        pose = pose.copy(x = s.x(pose.x, p), y = s.y(p), lift = 0f, rotation = 0f, earTilt = 0f, stretch = .82f)
    }

    fun step(seconds: Float, working: Boolean, roaming: Boolean, reduced: Boolean,
        activity: CompanionActivity = if (working) CompanionActivity.THINKING else CompanionActivity.IDLE): CompanionPose {
        val s = scene ?: return pose
        val dt = seconds.coerceIn(0f, .05f)
        time += dt
        if (working && (!lastWorking || activity != CompanionActivity.ERROR)) failedRun = false
        if (activity == CompanionActivity.ERROR) failedRun = true
        if (lastWorking && !working && activity == CompanionActivity.SUCCESS && !failedRun) {
            celebration = 1.8f
            if (roaming && motion == Motion.REST) hop(s.platforms.firstOrNull { it.id == perchId } ?: s.platforms.first(), pose.x, reduced)
        }
        lastWorking = working
        celebration = (celebration - dt).coerceAtLeast(0f)
        gazeRemaining = (gazeRemaining - dt).coerceAtLeast(0f)
        val gazeRate = (dt * 12f).coerceAtMost(1f)
        pose = pose.copy(lookX = pose.lookX + ((if (gazeRemaining > 0f) gazeX else 0f) - pose.lookX) * gazeRate,
            lookY = pose.lookY + ((if (gazeRemaining > 0f) gazeY else 0f) - pose.lookY) * gazeRate)
        if (motion == Motion.DRAG) return pose
        idle = if (working || (activity == CompanionActivity.LISTENING || activity == CompanionActivity.WAITING)) 0f else idle + dt
        val mood = when {
            activity == CompanionActivity.ERROR -> CompanionMood.CONCERNED
            activity == CompanionActivity.WAITING -> CompanionMood.WAITING
            activity == CompanionActivity.LISTENING -> CompanionMood.LISTENING
            celebration > 0f -> CompanionMood.HAPPY
            activity == CompanionActivity.TOOL -> CompanionMood.FOCUSED
            working -> CompanionMood.WORKING
            idle > 24f -> CompanionMood.SLEEPY
            else -> CompanionMood.AWAKE
        }
        if (reduced) {
            if (motion != Motion.REST) {
                val p = s.platforms.firstOrNull { it.id == targetId && s.safe(it) } ?: escapeTarget(s)
                land(p)
            }
            pose = pose.copy(rotation = 0f, lift = 0f, stretch = 1f, earTilt = 0f, blink = false, sparkle = 0f, mood = mood)
            return pose
        }
        pose = pose.copy(mood = mood, sparkle = if (celebration > 0f) celebration / 1.8f else 0f)
        when (motion) {
            Motion.HOP, Motion.TUMBLE -> {
                elapsed += dt
                if (elapsed < 0f) {
                    pose = pose.copy(stretch = 1f - sin((elapsed + .1f) / .1f * PI.toFloat() / 2) * .2f)
                    return pose
                }
                val tumble = motion == Motion.TUMBLE
                val t = (elapsed / if (tumble) .85f else .72f).coerceIn(0f, 1f)
                val arc = sin(PI.toFloat() * t)
                val lift = arc * s.size * if (tumble) .25f else .85f
                pose = pose.copy(x = (from.x + (destination.x - from.x) * t).coerceIn(0f, (s.width - s.size).coerceAtLeast(0f)),
                    y = (from.y + (destination.y - from.y) * t - lift).coerceIn(s.viewportTop.coerceAtMost((s.composer.top - s.size).coerceAtLeast(0f)), (s.composer.top - s.size).coerceAtLeast(0f)),
                    lift = lift, rotation = if (tumble) when { t < .25f -> t / .25f * 95f; t < .6f -> 95f; else -> 95f + (t - .6f) / .4f * 265f }
                        else sin(t * PI.toFloat() * 2) * -7f,
                    stretch = if (tumble) 1f else 1f + arc * .12f,
                    earTilt = sin(t * PI.toFloat() * 2) * 12f, mood = if (tumble) CompanionMood.SURPRISED else mood, blink = false)
                if (t >= 1f) land(s.platforms.firstOrNull { it.id == targetId && s.safe(it) } ?: escapeTarget(s))
            }
            Motion.THROW -> {
                elapsed += dt
                val oldFeet = pose.y + s.size
                vy += s.size * 28f * dt
                var x = pose.x + vx * dt
                var y = pose.y + vy * dt
                if (x < 0f || x > s.width - s.size) { x = x.coerceIn(0f, (s.width - s.size).coerceAtLeast(0f)); vx *= -.55f }
                if (y < s.viewportTop) { y = s.viewportTop; vy = abs(vy) * .3f }
                pose = pose.copy(x = x, y = y, rotation = (pose.rotation + vx / s.size * dt * 35f).coerceIn(-35f, 35f),
                    stretch = 1.06f, earTilt = (-vx / s.size).coerceIn(-12f, 12f))
                val hit = if (vy > 0f) s.platforms.filter { s.safe(it) && oldFeet <= it.top + 1f && y + s.size >= it.top &&
                    x + s.size * .75f >= it.left && x + s.size * .25f <= it.right }.minByOrNull { it.top } else null
                if (hit != null) land(hit)
                else if (y >= s.y(s.platforms.first()) || elapsed > 1.4f) {
                    pose = pose.copy(y = y.coerceIn(s.viewportTop.coerceAtMost(s.y(s.platforms.first())), s.y(s.platforms.first())))
                    hop(escapeTarget(s), pose.x, false, immediate = true)
                }
            }
            Motion.REST -> {
                resting += dt; landing = (landing + dt * 5f).coerceAtMost(1f)
                val spring = if (landing < 1f) -sin((1f - landing) * PI.toFloat() / 2) * .18f else 0f
                pose = pose.copy(stretch = 1f + spring, blink = mood != CompanionMood.SLEEPY && time % 4.6f > 4.42f,
                    earTilt = if (mood == CompanionMood.LISTENING) sin(time * 5f) * 5f else 0f)
                if (roaming && mood != CompanionMood.SLEEPY && mood != CompanionMood.WAITING && mood != CompanionMood.LISTENING && mood != CompanionMood.CONCERNED &&
                    resting > if (working) 3.5f else 6f) {
                    hops++
                    val options = (listOfNotNull(if (working) s.platforms.firstOrNull { it.id == s.console?.id || it.id == "console" } else null) +
                        s.messages.filter(s::safe) + s.platforms.first()).filter(s::safe)
                    val p = options[(hops - 1) % options.size]
                    hop(p, reduced = false)
                    lookAt(p.left + (p.right - p.left) / 2, p.top)
                }
            }
            Motion.DRAG -> Unit
        }
        return pose
    }
}
