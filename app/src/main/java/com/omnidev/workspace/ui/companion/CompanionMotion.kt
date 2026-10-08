package com.omnidev.workspace.ui.companion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

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
internal enum class CompanionMood { AWAKE, WORKING, FOCUSED, WAITING, LISTENING, HAPPY, CONCERNED, SLEEPY, SURPRISED, SAD, GUARDED, ANNOYED }
internal enum class CompanionPresence { VISIBLE, LEAVING, RETURNING, HIDDEN }
internal enum class CompanionTrick { NONE, PEEK, SNIFF, YAWN, STRETCH, GROOM }
internal data class CompanionPose(
    val x: Float = 0f, val y: Float = 0f, val lift: Float = 0f, val rotation: Float = 0f,
    val stretch: Float = 1f, val blink: Boolean = false, val mood: CompanionMood = CompanionMood.AWAKE,
    val lookX: Float = 0f, val lookY: Float = 0f, val earTilt: Float = 0f, val sparkle: Float = 0f,
    val bodyTilt: Float = 0f, val trick: CompanionTrick = CompanionTrick.NONE, val mouthOpen: Float = 0f
)

/** Stable platform identities survive scrolling; geometry updates never restart unrelated flights. */
internal class CompanionMotion(seed: Int = Random.nextInt(), private val mind: CompanionMind? = null) {
    private enum class Motion { REST, HOP, TUMBLE, THROW, DRAG, EXIT }
    var pose = CompanionPose(); private set
    var perchId: String? = null; private set
    var presence = CompanionPresence.VISIBLE; private set
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
    private var departureOrigin = pose
    private var departurePerch: String? = null
    private var exitDirection = 1f
    private var exitToComposer = true
    private var departureTime = 0f
    private var returnBounces = 0
    private val random = Random(seed)
    private var rareClock = 0f
    private var rareDue = 9f + random.nextFloat() * 7f
    private var rare = CompanionTrick.NONE
    private var previousRare = CompanionTrick.NONE
    private var rareElapsed = 0f
    private var rareDirection = 1f
    var typing = false
    var restCorner = CompanionRestCorner.LEARNED
    private var sleepDocked = false
    private var retreatCooldown = 0f
    private var userRoaming = true
    private var petting = 0f

    private fun mindContext(s: CompanionScene, working: Boolean, roaming: Boolean, reduced: Boolean) = CompanionMindContext(
        working, typing, roaming, reduced, s.console?.let(s::safe) == true, s.messages.any(s::safe),
        pose.x / (s.width - s.size).coerceAtLeast(1f), pose.y / s.height.coerceAtLeast(1f),
        when (perchId) { "composer", null -> 0; s.console?.id, "console" -> 1; else -> 2 })

    fun restoreRestingCorner() {
        if (motion != Motion.REST || perchId != "composer" || presence != CompanionPresence.VISIBLE) return
        val s = scene ?: return
        val side = when (restCorner) {
            CompanionRestCorner.LEFT -> CompanionAction.LEFT
            CompanionRestCorner.RIGHT -> CompanionAction.RIGHT
            CompanionRestCorner.LEARNED -> mind?.startingSide() ?: return
        }
        pose = pose.copy(x = s.x(if (side == CompanionAction.LEFT) s.composer.left + s.size * .3f
            else s.composer.right - s.size * 1.8f))
    }

    fun configure(next: CompanionScene, reduced: Boolean = false) {
        if (next == scene) return
        val first = scene == null
        scene = next
        if (presence == CompanionPresence.HIDDEN) return
        val composer = next.platforms.first()
        if (first) {
            perchId = composer.id
            pose = pose.copy(x = next.x(composer.right - next.size * 1.8f), y = next.y(composer))
            mind?.context(mindContext(next, false, true, reduced))
            if (restCorner == CompanionRestCorner.LEFT || restCorner == CompanionRestCorner.LEARNED &&
                mind?.startingSide() == CompanionAction.LEFT)
                pose = pose.copy(x = next.x(composer.left + next.size * .3f))
            return
        }
        pose = pose.copy(x = if (motion == Motion.EXIT) pose.x else pose.x.coerceIn(0f, (next.width - next.size).coerceAtLeast(0f)),
            y = pose.y.coerceIn(next.viewportTop.coerceAtMost((next.composer.top - next.size).coerceAtLeast(0f)), (next.composer.top - next.size).coerceAtLeast(0f)))
        if (motion == Motion.EXIT) {
            destination = destination.copy(y = next.y(composer))
            return
        }
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
        if (wake) { idle = 0f; cancelRare() }
        val s = scene ?: return
        val dx = x - pose.x - s.size / 2
        val dy = y - pose.y - s.size * .6f
        // Follow the viewing angle, rather than saturating both eyes at +/-1 for every distant target.
        val distance = sqrt(dx * dx + dy * dy + s.size * s.size * .36f)
        gazeX = dx / distance
        gazeY = dy / distance
        gazeRemaining = 1.8f
    }

    fun tap(reduced: Boolean) {
        if (presence == CompanionPresence.LEAVING || presence == CompanionPresence.RETURNING) {
            welcomeBack(); resumeReturn(reduced); return
        }
        val welcoming = mind?.tap() != false
        mind?.cancelledPickup()
        if (!welcoming) {
            cancelRare(); idle = 0f
            pose = pose.copy(mood = mind.mood ?: CompanionMood.GUARDED, earTilt = if (reduced) 0f else -12f)
            if (!reduced && userRoaming) retreat(reduced)
            else {
                val s = scene ?: return
                val p = s.platforms.firstOrNull { it.id == perchId && s.safe(it) } ?: escapeTarget(s)
                land(p)
            }
            return
        }
        cancelRare()
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
        if (presence == CompanionPresence.LEAVING) welcomeBack()
        mind?.pickup()
        cancelRare()
        motion = Motion.DRAG; idle = 0f; perchId = null; targetId = null
        pose = pose.copy(rotation = 0f, bodyTilt = 0f, lift = 0f, stretch = 1.08f,
            mood = if (presence == CompanionPresence.RETURNING) CompanionMood.HAPPY else mind?.mood ?: CompanionMood.SURPRISED)
    }
    fun drag(dx: Float, dy: Float) {
        val s = scene ?: return
        pose = pose.copy(x = (pose.x + dx).coerceIn(0f, (s.width - s.size).coerceAtLeast(0f)),
            y = (pose.y + dy).coerceIn(s.viewportTop.coerceAtMost((s.composer.top - s.size).coerceAtLeast(0f)), (s.composer.top - s.size).coerceAtLeast(0f)),
            earTilt = (-dx / s.size * 60f).coerceIn(-18f, 18f))
    }
    fun release(velocityX: Float, reduced: Boolean, velocityY: Float = 0f, teachPlacement: Boolean = false) {
        if (presence == CompanionPresence.RETURNING) { resumeReturn(reduced); return }
        val s = scene ?: return
        val gentle = abs(velocityX) + abs(velocityY) < s.size * 2
        if (teachPlacement) {
            if (gentle) mind?.placed((pose.x + s.size / 2) / s.width, true) else mind?.tossed()
        } else mind?.cancelledPickup()
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
        if (presence == CompanionPresence.LEAVING || presence == CompanionPresence.RETURNING) {
            welcomeBack(); departurePerch = "composer"; departureOrigin = pose.copy(y = s.y(s.platforms.first()))
        }
        hop(s.platforms.first(), reduced = reduced, immediate = true)
        if (reduced) presence = CompanionPresence.VISIBLE
    }

    /** A sad, unhurried departure stays cancellable until the entire sprite has crossed the edge. */
    fun hideTemporarily(reduced: Boolean) {
        val s = scene ?: return
        if (presence == CompanionPresence.HIDDEN || presence == CompanionPresence.LEAVING) return
        mind?.hidden()
        cancelRare(); celebration = 0f; idle = 0f
        departureOrigin = pose; departurePerch = perchId ?: targetId
        presence = CompanionPresence.LEAVING; motion = Motion.EXIT
        exitDirection = if (pose.x + s.size / 2 < s.width / 2) -1f else 1f
        departureTime = 0f; exitToComposer = true; elapsed = -.7f
        from = pose
        destination = pose.copy(x = s.x(pose.x), y = s.y(s.platforms.first()))
        perchId = null; targetId = null
        pose = pose.copy(mood = CompanionMood.SAD, earTilt = if (reduced) 0f else -15f,
            rotation = 0f, bodyTilt = 0f, sparkle = 0f, trick = CompanionTrick.NONE, mouthOpen = 0f)
    }

    private fun welcomeBack() {
        mind?.welcomed()
        if (presence == CompanionPresence.LEAVING) {
            presence = CompanionPresence.RETURNING; returnBounces = 2; celebration = 2.8f
        }
        idle = 0f; pose = pose.copy(mood = CompanionMood.HAPPY, earTilt = 0f, rotation = 0f, bodyTilt = 0f)
    }

    fun settle() { mind?.settle(); petting = 1.6f; idle = 0f; pose = pose.copy(mood = CompanionMood.HAPPY) }
    fun feedback(liked: Boolean) = mind?.feedback(liked) ?: false
    fun flushMemory() { mind?.flush() }

    /** A near pointer can start a short retreat, but captured drags always follow the finger. */
    fun noticePointer(x: Float, y: Float, roaming: Boolean, reduced: Boolean) {
        if (mind?.wantsSpace != true || !roaming || reduced || motion != Motion.REST ||
            presence != CompanionPresence.VISIBLE || retreatCooldown > 0f) return
        val s = scene ?: return
        if (abs(x - pose.x - s.size / 2) < s.size * 1.5f && abs(y - pose.y - s.size / 2) < s.size * 1.5f) retreat(false)
    }
    private fun retreat(reduced: Boolean) {
        val s = scene ?: return
        retreatCooldown = 3f
        val p = s.platforms.filter(s::safe).maxByOrNull { abs(s.y(it) - pose.y) + abs(s.x(pose.x, it) - pose.x) }
            ?: s.platforms.first()
        val x = if (pose.x + s.size / 2 > s.width / 2) p.left else p.right - s.size
        hop(p, x, reduced, immediate = false)
    }

    private fun resumeReturn(reduced: Boolean) {
        val s = scene ?: return
        celebration = 2.8f
        val p = s.platforms.firstOrNull { it.id == departurePerch && s.safe(it) } ?: s.platforms.first()
        hop(p, departureOrigin.x, reduced, immediate = true)
        if (reduced) { presence = CompanionPresence.VISIBLE; returnBounces = 0; pose = pose.copy(mood = CompanionMood.HAPPY) }
    }

    private fun stepDeparture(dt: Float, reduced: Boolean): CompanionPose {
        val s = scene ?: return pose
        departureTime += dt
        if (reduced) {
            pose = pose.copy(mood = CompanionMood.SAD, lift = 0f, rotation = 0f, bodyTilt = 0f, earTilt = 0f, stretch = 1f)
            if (departureTime >= 3f) presence = CompanionPresence.HIDDEN
            return pose
        }
        elapsed += dt
        val t = (elapsed / if (exitToComposer) 1f else .8f).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        val lift = sin(PI.toFloat() * t) * s.size * if (exitToComposer) .35f else .16f
        pose = pose.copy(x = from.x + (destination.x - from.x) * smooth,
            y = (from.y + (destination.y - from.y) * smooth - lift).coerceAtLeast(s.viewportTop.coerceAtMost(s.y(s.platforms.first()))),
            lift = lift, mood = CompanionMood.SAD, earTilt = -15f, bodyTilt = exitDirection * 3f,
            stretch = 1f - sin(PI.toFloat() * t) * .04f, blink = false, sparkle = 0f)
        if (t >= 1f) {
            val edge = if (exitDirection < 0f) -s.size else s.width
            if (!exitToComposer && kotlin.math.abs(pose.x - edge) < .01f) presence = CompanionPresence.HIDDEN
            else {
                exitToComposer = false; elapsed = -.25f; from = pose
                destination = pose.copy(x = if (exitDirection < 0f) (pose.x - s.size * .38f).coerceAtLeast(edge)
                    else (pose.x + s.size * .38f).coerceAtMost(edge), y = s.y(s.platforms.first()))
            }
        }
        return pose
    }

    private fun cancelRare(resetClock: Boolean = true) {
        if (rare != CompanionTrick.NONE) { rareDue = 9f + random.nextFloat() * 7f; rare = CompanionTrick.NONE; rareClock = 0f }
        if (resetClock) rareClock = 0f
        pose = pose.copy(trick = CompanionTrick.NONE, mouthOpen = 0f)
    }

    private fun curiousMoment(dt: Float, eligible: Boolean): Boolean {
        if (!eligible) { cancelRare(resetClock = false); return false }
        if (rare == CompanionTrick.NONE) {
            if (rareClock < rareDue) return false
            val options = CompanionTrick.entries.filter { it != CompanionTrick.NONE && it != previousRare }
            val choices = options.map { CompanionAction.valueOf(it.name) }
            val learned = mind?.choose(choices)
            rare = learned?.let { CompanionTrick.valueOf(it.name) } ?: options[random.nextInt(options.size)]; previousRare = rare
            rareElapsed = 0f; rareDirection = if (random.nextBoolean()) 1f else -1f
        }
        rareElapsed += dt
        val duration = when (rare) { CompanionTrick.PEEK -> 1.9f; CompanionTrick.SNIFF -> 1.4f; CompanionTrick.YAWN -> 2.2f; else -> 1.8f }
        val t = (rareElapsed / duration).coerceIn(0f, 1f)
        val pulse = sin(t * PI.toFloat())
        pose = when (rare) {
            CompanionTrick.PEEK -> pose.copy(trick = rare, bodyTilt = rareDirection * pulse * 5f,
                lookX = rareDirection * pulse * .65f, lookY = -pulse * .3f, earTilt = pulse * 9f)
            CompanionTrick.SNIFF -> pose.copy(trick = rare, bodyTilt = sin(t * PI.toFloat() * 6) * pulse * 2f,
                earTilt = sin(t * PI.toFloat() * 8) * pulse * 8f, mouthOpen = pulse * .2f)
            CompanionTrick.YAWN -> pose.copy(trick = rare, bodyTilt = -pulse * 4f, blink = pulse > .4f,
                earTilt = -pulse * 13f, mouthOpen = pulse)
            CompanionTrick.STRETCH -> pose.copy(trick = rare, stretch = 1f + pulse * .14f, earTilt = pulse * 7f, blink = pulse > .8f)
            CompanionTrick.GROOM -> pose.copy(trick = rare, bodyTilt = -pulse * 4f + sin(t * PI.toFloat() * 6) * pulse * 2f,
                earTilt = -pulse * 16f, lookX = -pulse * .35f, lookY = -pulse * .5f, blink = pulse > .4f, stretch = 1f + pulse * .04f)
            CompanionTrick.NONE -> pose
        }
        if (t >= 1f) cancelRare()
        return true
    }
    private fun hop(p: CompanionPerch, x: Float? = null, reduced: Boolean, immediate: Boolean = false) {
        val s = scene ?: return
        cancelRare(resetClock = false)
        pose = pose.copy(lift = 0f, rotation = 0f, bodyTilt = 0f, stretch = 1f, earTilt = 0f)
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
        userRoaming = roaming
        mind?.context(mindContext(s, working, roaming, reduced)); mind?.step(if (reduced) seconds.coerceIn(0f, .25f) else dt)
        retreatCooldown = (retreatCooldown - dt).coerceAtLeast(0f)
        petting = (petting - dt).coerceAtLeast(0f)
        if (presence == CompanionPresence.HIDDEN) return pose
        if (motion == Motion.EXIT) return stepDeparture(dt, reduced)
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
            presence == CompanionPresence.RETURNING -> CompanionMood.HAPPY
            activity == CompanionActivity.ERROR -> CompanionMood.CONCERNED
            activity == CompanionActivity.WAITING -> CompanionMood.WAITING
            activity == CompanionActivity.LISTENING -> CompanionMood.LISTENING
            celebration > 0f -> CompanionMood.HAPPY
            activity == CompanionActivity.TOOL -> CompanionMood.FOCUSED
            working -> CompanionMood.WORKING
            mind?.mood != null -> mind.mood!!
            idle > 24f -> CompanionMood.SLEEPY
            else -> CompanionMood.AWAKE
        }
        if (mood != CompanionMood.SLEEPY) sleepDocked = false
        if (mood == CompanionMood.SLEEPY && !sleepDocked && motion == Motion.REST && roaming && !reduced && mind != null) {
            sleepDocked = true
            val left = restCorner == CompanionRestCorner.LEFT || restCorner == CompanionRestCorner.LEARNED &&
                mind.choose(listOf(CompanionAction.LEFT, CompanionAction.RIGHT)) == CompanionAction.LEFT
            val composer = s.platforms.first()
            hop(composer, if (left) composer.left + s.size * .2f else composer.right - s.size * 1.2f, false)
        }
        if (reduced) {
            cancelRare()
            if (motion != Motion.REST) {
                val p = s.platforms.firstOrNull { it.id == targetId && s.safe(it) } ?: escapeTarget(s)
                land(p)
            }
            pose = pose.copy(rotation = 0f, bodyTilt = 0f, lift = 0f, stretch = 1f, earTilt = 0f, blink = false, sparkle = 0f, mood = mood)
            if (presence == CompanionPresence.RETURNING) { presence = CompanionPresence.VISIBLE; returnBounces = 0 }
            return pose
        }
        pose = pose.copy(mood = mood, sparkle = if (celebration > 0f) celebration / 1.8f else 0f)
        // The interval spans automatic hops too; otherwise roaming can starve all rare moments.
        if (mood == CompanionMood.AWAKE && idle > 4f && presence == CompanionPresence.VISIBLE) rareClock += dt *
            when (mind?.personality) { CompanionPersonality.CALM -> .65f; CompanionPersonality.PLAYFUL -> 1.3f; else -> 1f }
        when (motion) {
            Motion.HOP, Motion.TUMBLE -> {
                elapsed += dt
                if (elapsed < 0f) {
                    pose = pose.copy(stretch = 1f - sin((elapsed + .1f) / .1f * PI.toFloat() / 2) * .2f)
                    return pose
                }
                val tumble = motion == Motion.TUMBLE
                val t = (elapsed / if (tumble) .85f else if (presence == CompanionPresence.RETURNING) .3f else .72f).coerceIn(0f, 1f)
                val arc = sin(PI.toFloat() * t)
                val lift = arc * s.size * if (tumble) .25f else .85f
                pose = pose.copy(x = (from.x + (destination.x - from.x) * t).coerceIn(0f, (s.width - s.size).coerceAtLeast(0f)),
                    y = (from.y + (destination.y - from.y) * t - lift).coerceIn(s.viewportTop.coerceAtMost((s.composer.top - s.size).coerceAtLeast(0f)), (s.composer.top - s.size).coerceAtLeast(0f)),
                    lift = lift, rotation = if (tumble) when { t < .25f -> t / .25f * 95f; t < .6f -> 95f; else -> 95f + (t - .6f) / .4f * 265f }
                        else sin(t * PI.toFloat() * 2) * -7f,
                    stretch = if (tumble) 1f else 1f + arc * .12f,
                    earTilt = sin(t * PI.toFloat() * 2) * 12f, mood = if (tumble) CompanionMood.SURPRISED else mood, blink = false)
                if (t >= 1f) {
                    val p = s.platforms.firstOrNull { it.id == targetId && s.safe(it) } ?: escapeTarget(s)
                    land(p)
                    if (presence == CompanionPresence.RETURNING) {
                        if (returnBounces-- > 0) hop(p, departureOrigin.x + if (returnBounces % 2 == 1) s.size * .3f else 0f, false, immediate = true)
                        else presence = CompanionPresence.VISIBLE
                    }
                }
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
                val spring = if (landing < 1f) -cos(landing * PI.toFloat() * 3) * (1f - landing) * .18f else 0f
                val sleeping = mood == CompanionMood.SLEEPY
                val breath = sin(time * if (sleeping) 1.6f else 2.4f) * if (sleeping) .022f else .012f
                val curious = if (mood == CompanionMood.AWAKE && resting > 2f) sin(time * .9f) * 2f else 0f
                val twitch = if (mood == CompanionMood.AWAKE && time % 9.2f > 8.6f) sin((time % 9.2f - 8.6f) * 18f) * 6f else 0f
                val guarded = mood == CompanionMood.GUARDED || mood == CompanionMood.ANNOYED
                pose = pose.copy(stretch = 1f + spring + breath, bodyTilt = if (guarded) pose.lookX * -4f else curious,
                    blink = !sleeping && (time % 4.6f > 4.42f || mood == CompanionMood.HAPPY && time % 3.8f > 3.3f),
                    earTilt = when (mood) { CompanionMood.LISTENING -> sin(time * 5f) * 5f; CompanionMood.SLEEPY -> -7f;
                        CompanionMood.ANNOYED -> -15f; CompanionMood.GUARDED, CompanionMood.SAD -> -9f; else -> twitch })
                val inspecting = curiousMoment(dt, !working && mood == CompanionMood.AWAKE && gazeRemaining <= 0f && idle > 4f)
                if (petting > 0f && !working && mind?.expressive != false) {
                    val purr = sin(petting * PI.toFloat() / 1.6f)
                    pose = pose.copy(bodyTilt = purr * 5f, stretch = 1f + purr * .035f,
                        blink = purr > .4f, earTilt = purr * 4f)
                }
                if (roaming && mood != CompanionMood.SLEEPY && mood != CompanionMood.WAITING && mood != CompanionMood.LISTENING && mood != CompanionMood.CONCERNED &&
                    !inspecting && petting <= 0f &&
                    resting > if (working) 3.5f else when (mind?.personality) {
                        CompanionPersonality.CALM -> 10f; CompanionPersonality.PLAYFUL -> 4.5f; else -> 6f }) {
                    hops++
                    val allowed = mutableListOf(CompanionAction.REST, CompanionAction.LEFT, CompanionAction.RIGHT)
                    if (s.console?.let(s::safe) == true) allowed.add(CompanionAction.CONSOLE)
                    if (s.messages.any(s::safe)) allowed.add(CompanionAction.MESSAGE)
                    val choice = mind?.choose(allowed)
                    if (choice == CompanionAction.REST) { resting = 0f; return pose }
                    val options = (listOfNotNull(if (working) s.platforms.firstOrNull { it.id == s.console?.id || it.id == "console" } else null) +
                        s.messages.filter(s::safe) + s.platforms.first()).filter(s::safe)
                    val p = when (choice) {
                        CompanionAction.CONSOLE -> s.console?.takeIf(s::safe) ?: s.platforms.first()
                        CompanionAction.MESSAGE -> s.messages.filter(s::safe).let { it[(hops - 1) % it.size] }
                        CompanionAction.LEFT, CompanionAction.RIGHT -> s.platforms.first()
                        else -> options[(hops - 1) % options.size]
                    }
                    val x = when (choice) { CompanionAction.LEFT -> p.left + s.size * .2f; CompanionAction.RIGHT -> p.right - s.size * 1.2f; else -> null }
                    hop(p, x, reduced = false)
                    lookAt(p.left + (p.right - p.left) / 2, p.top)
                }
            }
            Motion.DRAG -> Unit
            Motion.EXIT -> Unit
        }
        return pose
    }
}
