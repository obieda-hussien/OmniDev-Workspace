package com.omnidev.workspace.ui.companion

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal enum class CompanionAnchor { COMPOSER, CONSOLE }
private class AnchorRegistry {
    val positions = mutableStateMapOf<Any, Pair<CompanionAnchor, Rect>>()
}
private val LocalCompanionAnchors = staticCompositionLocalOf<AnchorRegistry?> { null }

/** Register a real surface without keeping detached LazyColumn item coordinates alive. */
@Composable
internal fun Modifier.companionAnchor(kind: CompanionAnchor): Modifier {
    val registry = LocalCompanionAnchors.current ?: return this
    val token = remember { Any() }
    DisposableEffect(registry, token) { onDispose { registry.positions.remove(token) } }
    return padding(top = 60.dp).onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) registry.positions[token] = kind to coordinates.unclippedRootRect()
    }
}

private fun LayoutCoordinates.unclippedRootRect(): Rect {
    val p = localToRoot(androidx.compose.ui.geometry.Offset.Zero)
    return Rect(p.x, p.y, p.x + size.width, p.y + size.height)
}

/** Pointer input is confined to the little sprite. The conversation behind it stays interactive. */
@Composable
internal fun ChatCompanionHost(
    sessionKey: Any?, working: Boolean, visible: Boolean = true,
    modifier: Modifier = Modifier, content: @Composable () -> Unit
) {
    val preferences by rememberCompanionPreferences()
    val registry = remember { AnchorRegistry() }
    var parent by remember { mutableStateOf<Rect?>(null) }
    Box(modifier.onGloballyPositioned { parent = it.unclippedRootRect() }) {
        CompositionLocalProvider(LocalCompanionAnchors provides if (preferences.enabled && visible) registry else null) { content() }
        if (preferences.enabled && visible) {
            val size = with(LocalDensity.current) { 60.dp.toPx() }
            val area = parent
            val anchors = registry.positions.values.toList()
            if (area != null) {
                fun perch(rect: Rect) = CompanionPerch((rect.left - area.left).coerceAtLeast(0f),
                    (rect.right - area.left).coerceAtMost(area.width), rect.top - area.top)
                val editor = anchors.lastOrNull { it.first == CompanionAnchor.COMPOSER }?.second
                if (editor != null) {
                    val base = CompanionScene(area.width, area.height, size, perch(editor))
                    if (base.valid(base.composer)) {
                        // Only a fully visible console's top is a platform; clipped history is ignored.
                        val console = anchors.filter { it.first == CompanionAnchor.CONSOLE }
                            .map { perch(it.second) }.filter { base.valid(it) && it.top < base.composer.top - size }
                            .maxByOrNull { it.top }
                        CompanionOverlay(sessionKey, base.copy(console = console), working, preferences.roaming)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanionOverlay(sessionKey: Any?, scene: CompanionScene, working: Boolean, roaming: Boolean) {
    val motion = LocalOmniMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val engine = remember(sessionKey) { CompanionMotion() }
    val pose = remember(engine) { mutableStateOf(CompanionPose()) }
    val currentWorking by rememberUpdatedState(working)
    val currentRoaming by rememberUpdatedState(roaming)
    // Geometry updates snap back to a safe perch, including IME resize and scrolling consoles.
    SideEffect { engine.configure(scene); pose.value = engine.step(0f, working, roaming, motion.reduced) }
    LaunchedEffect(engine, lifecycle, motion) {
        if (!motion.reduced) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = System.nanoTime()
            while (true) {
                // A bounded local ticker lets Compose and UI tests become idle between frames.
                // Only the sprite's draw/layout phases observe pose, never the message list.
                delay(if (motion.compact) 34L else 17L)
                val now = System.nanoTime()
                pose.value = engine.step((now - previous) / 1_000_000_000f, currentWorking, currentRoaming, false)
                previous = now
            }
        }
    }
    val slop = LocalViewConfiguration.current.touchSlop
    val feedback = LocalHapticFeedback.current
    CompanionArtwork(Modifier.offset { IntOffset(pose.value.x.roundToInt(), pose.value.y.roundToInt()) }
        .size(60.dp).testTag("omni-companion")
        .semantics {
            contentDescription = "Omni companion"
            stateDescription = if (working) "Working alongside you" else "Your chat companion"
            onClick("Play with Omni") { engine.tap(motion.reduced); pose.value = engine.pose; true }
            customActions = listOf(CustomAccessibilityAction("Return to message box") {
                engine.grab(); engine.drag(0f, scene.height); engine.release(0f, motion.reduced); pose.value = engine.pose; true
            })
        }
        .pointerInput(engine, motion.reduced) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var distance = androidx.compose.ui.geometry.Offset.Zero
                var grabbed = false
                var ended = false
                try {
                    // A tap tumbles; crossing touch slop grabs the blob instead of tapping it.
                    while (!ended) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) {
                            if (grabbed) engine.release(tracker.calculateVelocity().x, motion.reduced) else { engine.tap(motion.reduced); feedback.performHapticFeedback(HapticFeedbackType.LongPress) }
                            change.consume(); ended = true
                        } else {
                            val delta = change.positionChange()
                            distance += delta
                            if (!grabbed && distance.getDistance() > slop) { engine.grab(); engine.drag(distance.x, distance.y); grabbed = true }
                            else if (grabbed) engine.drag(delta.x, delta.y)
                            change.consume()
                        }
                        pose.value = engine.pose
                    }
                } finally {
                    if (grabbed && !ended) { engine.release(0f, motion.reduced); pose.value = engine.pose }
                }
            }
        }, pose = { pose.value })
}
