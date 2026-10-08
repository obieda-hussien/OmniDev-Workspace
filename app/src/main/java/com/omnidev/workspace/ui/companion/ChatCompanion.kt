package com.omnidev.workspace.ui.companion

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal enum class CompanionAnchor { COMPOSER, CONSOLE, MESSAGE }
private data class AnchorPosition(val kind: CompanionAnchor, val rect: Rect, val id: String)
private data class LookTarget(val point: Offset, val time: Long)
private class AnchorRegistry {
    val positions = mutableStateMapOf<Any, AnchorPosition>()
    var viewport by mutableStateOf<Rect?>(null)
    // Pointer/focus updates are read by the sprite ticker, without recomposing the transcript.
    var look: LookTarget? = null
    var editorFocused = false
    var caret: Offset? = null
    var keyboard: Offset? = null
    var editedAt = 0L
}
private val LocalCompanionAnchors = staticCompositionLocalOf<AnchorRegistry?> { null }

/** Stable keys follow LazyColumn items. User bubbles share the existing 24dp item gap as headroom. */
@Composable
internal fun Modifier.companionAnchor(kind: CompanionAnchor, id: String? = null): Modifier {
    val registry = LocalCompanionAnchors.current ?: return this
    val token = remember(kind, id) { Any() }
    val stableId = id ?: if (kind == CompanionAnchor.COMPOSER) "composer" else "${kind.name}-${System.identityHashCode(token)}"
    DisposableEffect(registry, token) { onDispose { registry.positions.remove(token) } }
    return padding(top = if (kind == CompanionAnchor.MESSAGE) 36.dp else 60.dp).onGloballyPositioned {
        if (it.isAttached) registry.positions[token] = AnchorPosition(kind, it.unclippedRootRect(), stableId)
    }
}

@Composable
internal fun Modifier.companionViewport(): Modifier {
    val registry = LocalCompanionAnchors.current ?: return this
    DisposableEffect(registry) { onDispose { registry.viewport = null } }
    return onGloballyPositioned { registry.viewport = it.boundsInRoot() }
}

@Composable
internal fun rememberCompanionEditorFocus(): (Boolean) -> Unit {
    val registry = LocalCompanionAnchors.current
    return remember(registry) { { focused -> registry?.editorFocused = focused } }
}

/** The inner text origin and shaped cursor rect handle RTL, mixed text and line wrapping. */
internal class CompanionEditorGaze(private val report: (Offset?, Boolean) -> Unit) {
    var coordinates: LayoutCoordinates? = null
    var layout: TextLayoutResult? = null
    var value: TextFieldValue = TextFieldValue()
    fun publish(edited: Boolean = false) {
        if (edited) report(null, true)
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return
        val layout = layout ?: return
        // Wait for layout after text changes; offsets from the old paragraph would be wrong.
        if (layout.layoutInput.text.text != value.text) return
        val cursor = layout.getCursorRect(value.selection.end.coerceIn(0, value.text.length))
        val bounds = coordinates.boundsInRoot()
        val point = coordinates.localToRoot(cursor.center)
        report(Offset(point.x.coerceIn(bounds.left, bounds.right), point.y.coerceIn(bounds.top, bounds.bottom)), false)
    }
    fun clear() { report(null, false); coordinates = null; layout = null }
}

@Composable
internal fun rememberCompanionEditorGaze(): CompanionEditorGaze {
    val registry = LocalCompanionAnchors.current
    val gaze = remember(registry) { CompanionEditorGaze { point, edited ->
        registry?.caret = point
        if (edited) registry?.editedAt = System.nanoTime()
    } }
    DisposableEffect(gaze) { onDispose { gaze.clear() } }
    return gaze
}

private fun LayoutCoordinates.unclippedRootRect(): Rect {
    val p = localToRoot(Offset.Zero)
    return Rect(p.x, p.y, p.x + size.width, p.y + size.height)
}

/** One transparent sprite over both chat hosts, with no extra windows or full-screen touch consumer. */
@Composable
internal fun ChatCompanionHost(
    sessionKey: Any?, working: Boolean, visible: Boolean = true, modifier: Modifier = Modifier,
    activity: CompanionActivity = if (working) CompanionActivity.THINKING else CompanionActivity.IDLE,
    content: @Composable () -> Unit
) {
    val preferences by rememberCompanionPreferences()
    val registry = remember(sessionKey) { AnchorRegistry() }
    var parent by remember { mutableStateOf<Rect?>(null) }
    val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    SideEffect { registry.keyboard = parent?.takeIf { imeHeight > 0 }?.let { Offset(it.width / 2, it.height + imeHeight * .45f) } }
    Box(modifier.onGloballyPositioned { parent = it.unclippedRootRect() }.pointerInput(registry) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.firstOrNull { it.pressed }?.let { registry.look = LookTarget(it.position, System.nanoTime()) }
                // Observe touches for gaze; scrolling, selection and controls keep their own gestures.
            }
        }
    }) {
        CompositionLocalProvider(LocalCompanionAnchors provides if (preferences.enabled && visible) registry else null) { content() }
        if (preferences.enabled && visible) {
            val size = with(LocalDensity.current) { 60.dp.toPx() }
            val area = parent
            val anchors = registry.positions.values.toList()
            if (area != null) {
                fun perch(anchor: AnchorPosition) = CompanionPerch((anchor.rect.left - area.left).coerceAtLeast(0f),
                    (anchor.rect.right - area.left).coerceAtMost(area.width), anchor.rect.top - area.top, anchor.id)
                val editor = anchors.lastOrNull { it.kind == CompanionAnchor.COMPOSER }
                if (editor != null) {
                    val viewport = registry.viewport
                    val base = CompanionScene(area.width, area.height, size, perch(editor),
                        viewportTop = (viewport?.top?.minus(area.top) ?: 0f).coerceAtLeast(0f),
                        viewportBottom = (viewport?.bottom?.minus(area.top) ?: perch(editor).top).coerceAtMost(perch(editor).top))
                    if (base.valid(base.composer)) {
                        val consoles = anchors.filter { it.kind == CompanionAnchor.CONSOLE }.map(::perch)
                        val console = consoles.firstOrNull { it.id == "live-console" }
                            ?: consoles.filter(base::valid).maxByOrNull { it.top }
                        val others = anchors.filter { it.kind == CompanionAnchor.MESSAGE }.map(::perch) + consoles.filter { it.id != console?.id }
                        CompanionOverlay(sessionKey, base.copy(console = console, messages = others), working, preferences.roaming, activity, registry, area.topLeft)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.CompanionOverlay(sessionKey: Any?, scene: CompanionScene, working: Boolean, roaming: Boolean,
    activity: CompanionActivity, registry: AnchorRegistry, origin: Offset) {
    val motion = LocalOmniMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val engine = remember(sessionKey) { CompanionMotion() }
    val pose = remember(engine) { mutableStateOf(CompanionPose()) }
    val currentWorking by rememberUpdatedState(working)
    val currentRoaming by rememberUpdatedState(roaming)
    val currentActivity by rememberUpdatedState(activity)
    val currentScene by rememberUpdatedState(scene)
    val currentOrigin by rememberUpdatedState(origin)
    SideEffect { engine.configure(scene, motion.reduced); pose.value = engine.step(0f, working, roaming, motion.reduced, activity) }
    LaunchedEffect(engine, lifecycle, motion) {
        if (!motion.reduced) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = System.nanoTime()
            var observedLook = 0L
            while (true) {
                delay(if (motion.compact) 34L else 17L)
                val now = System.nanoTime()
                val look = registry.look
                val caret = registry.caret?.minus(currentOrigin)
                if (registry.editorFocused && caret != null && now - registry.editedAt < 900_000_000L) {
                    engine.lookAt(caret.x, caret.y, wake = true)
                } else if (look != null && look.time != observedLook && now - look.time < 2_000_000_000L) {
                    engine.lookAt(look.point.x, look.point.y, wake = true); observedLook = look.time
                } else if (look == null || now - look.time > 2_000_000_000L) {
                    if (registry.editorFocused) {
                        val target = registry.keyboard ?: caret ?: Offset((currentScene.composer.left + currentScene.composer.right) / 2, currentScene.composer.top)
                        engine.lookAt(target.x, target.y)
                    } else currentScene.console?.takeIf { currentWorking }?.let {
                        engine.lookAt((it.left + it.right) / 2, it.top + currentScene.size * .7f)
                    }
                }
                pose.value = engine.step((now - previous) / 1_000_000_000f, currentWorking, currentRoaming, false, currentActivity)
                previous = now
            }
        }
    }
    val slop = LocalViewConfiguration.current.touchSlop
    val feedback = LocalHapticFeedback.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // Physical coordinates everywhere, independent of the application's layout direction.
    CompanionArtwork(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { IntOffset(pose.value.x.roundToInt(), pose.value.y.roundToInt()) }
        .onGloballyPositioned { coordinates[0] = it }
        .size(60.dp).testTag("omni-companion")
        .semantics {
            contentDescription = "Omni companion"
            stateDescription = when (activity) {
                CompanionActivity.WAITING -> "Waiting for your decision"
                CompanionActivity.LISTENING -> "Listening with you"
                CompanionActivity.ERROR -> "Something needs attention"
                else -> if (working) "Working alongside you" else "Your chat companion"
            }
            onClick("Play with Omni") { engine.tap(motion.reduced); pose.value = engine.pose; true }
            customActions = listOf(CustomAccessibilityAction("Return to message box") {
                engine.returnToComposer(motion.reduced); pose.value = engine.pose; true
            })
        }
        .pointerInput(engine, motion.reduced) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tracker = VelocityTracker()
                fun worldPoint(point: Offset) = coordinates[0]?.takeIf { it.isAttached }?.localToRoot(point)
                    ?: (point + Offset(engine.pose.x, engine.pose.y) + currentOrigin)
                tracker.addPosition(down.uptimeMillis, worldPoint(down.position))
                var previous = worldPoint(down.position)
                var distance = Offset.Zero
                var grabbed = false
                var ended = false
                engine.grab(); pose.value = engine.pose; down.consume()
                try {
                    while (!ended) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val point = worldPoint(change.position)
                        tracker.addPosition(change.uptimeMillis, point)
                        if (!change.pressed) {
                            if (grabbed) {
                                val velocity = tracker.calculateVelocity()
                                engine.release(velocity.x, motion.reduced, velocity.y)
                            } else { engine.tap(motion.reduced); feedback.performHapticFeedback(HapticFeedbackType.LongPress) }
                            change.consume(); ended = true
                        } else {
                            val delta = point - previous
                            distance += delta
                            if (!grabbed && distance.getDistance() > slop) { engine.drag(distance.x, distance.y); grabbed = true }
                            else if (grabbed) engine.drag(delta.x, delta.y)
                            change.consume()
                        }
                        previous = point
                        pose.value = engine.pose
                    }
                } finally {
                    if (!ended) { engine.release(0f, motion.reduced); pose.value = engine.pose }
                }
            }
        }, pose = { pose.value })
}

