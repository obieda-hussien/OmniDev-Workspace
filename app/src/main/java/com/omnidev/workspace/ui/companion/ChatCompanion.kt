package com.omnidev.workspace.ui.companion

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
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
    var onTouch: ((Offset) -> Unit)? = null
}
private val LocalCompanionAnchors = staticCompositionLocalOf<AnchorRegistry?> { null }

/** Stable keys follow LazyColumn items. User bubbles share the existing 24dp item gap as headroom. */
@Composable
internal fun Modifier.companionAnchor(kind: CompanionAnchor, id: String? = null): Modifier {
    val registry = LocalCompanionAnchors.current
    val policy = LocalOmniMotion.current
    val headroom by animateDpAsState(if (registry == null) 0.dp else if (kind == CompanionAnchor.MESSAGE) 36.dp else 60.dp,
        tween(if (policy.reduced) 0 else policy.responseMillis), label = "companion headroom")
    val token = remember(kind, id) { Any() }
    val stableId = id ?: if (kind == CompanionAnchor.COMPOSER) "composer" else "${kind.name}-${System.identityHashCode(token)}"
    DisposableEffect(registry, token) { onDispose { registry?.positions?.remove(token) } }
    return padding(top = headroom).onGloballyPositioned {
        if (registry != null && it.isAttached) registry.positions[token] = AnchorPosition(kind, it.unclippedRootRect(), stableId)
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
    var temporarilyHidden by remember(sessionKey) { mutableStateOf(false) }
    LaunchedEffect(temporarilyHidden) {
        if (temporarilyHidden) { delay(5 * 60_000L); temporarilyHidden = false }
    }
    LaunchedEffect(preferences.enabled) { if (preferences.enabled) temporarilyHidden = false }
    var parent by remember { mutableStateOf<Rect?>(null) }
    val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    SideEffect { registry.keyboard = parent?.takeIf { imeHeight > 0 }?.let { Offset(it.width / 2, it.height + imeHeight * .45f) } }
    Box(modifier.onGloballyPositioned { parent = it.unclippedRootRect() }.pointerInput(registry) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.firstOrNull { it.pressed }?.let { registry.look = LookTarget(it.position, System.nanoTime()) }
                event.changes.firstOrNull { it.pressed && !it.previousPressed }?.let { registry.onTouch?.invoke(it.position) }
                // Observe touches for gaze; scrolling, selection and controls keep their own gestures.
            }
        }
    }) {
        CompositionLocalProvider(LocalCompanionAnchors provides if (preferences.enabled && visible && !temporarilyHidden) registry else null) { content() }
        if (preferences.enabled && visible && !temporarilyHidden) {
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
                        CompanionOverlay(sessionKey, base.copy(console = console, messages = others), working, preferences.roaming, activity, registry, area.topLeft,
                            onHidden = { temporarilyHidden = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.CompanionOverlay(sessionKey: Any?, scene: CompanionScene, working: Boolean, roaming: Boolean,
    activity: CompanionActivity, registry: AnchorRegistry, origin: Offset, onHidden: () -> Unit) {
    val motion = LocalOmniMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val engine = remember(sessionKey) { CompanionMotion() }
    val pose = remember(engine) { mutableStateOf(CompanionPose()) }
    var menuOpen by remember { mutableStateOf(false) }
    var animateExit by remember { mutableStateOf(false) }
    var presence by remember { mutableStateOf(CompanionPresence.VISIBLE) }
    val menuBounds = remember { arrayOfNulls<Rect>(1) }
    fun syncPose() {
        pose.value = engine.pose; presence = engine.presence
        if (engine.presence == CompanionPresence.HIDDEN) onHidden()
        if (motion.reduced && engine.presence != CompanionPresence.LEAVING) animateExit = false
    }
    fun openMenu() {
        engine.release(0f, true)
        menuOpen = true; syncPose()
    }
    fun hideForNow() {
        menuOpen = false; engine.hideTemporarily(motion.reduced); animateExit = true; syncPose()
    }
    val currentWorking by rememberUpdatedState(working)
    val currentRoaming by rememberUpdatedState(roaming)
    val currentActivity by rememberUpdatedState(activity)
    val currentScene by rememberUpdatedState(scene)
    val currentOrigin by rememberUpdatedState(origin)
    val currentMenuOpen by rememberUpdatedState(menuOpen)
    val currentOnHidden by rememberUpdatedState(onHidden)
    SideEffect {
        engine.configure(scene, motion.reduced)
        if (!menuOpen) engine.step(0f, working, roaming, motion.reduced, activity)
        syncPose()
        registry.onTouch = { point ->
            val p = engine.pose
            val sprite = Rect(p.x, p.y, p.x + scene.size, p.y + scene.size)
            if (menuOpen && !sprite.contains(point) && menuBounds[0]?.contains(point) != true) menuOpen = false
        }
    }
    DisposableEffect(registry) { onDispose { registry.onTouch = null } }
    LaunchedEffect(engine, lifecycle, motion, animateExit) {
        if (!motion.reduced || animateExit) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = System.nanoTime()
            var observedLook = 0L
            while (true) {
                delay(if (motion.compact) 34L else 17L)
                val now = System.nanoTime()
                if (currentMenuOpen) { previous = now; continue }
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
                pose.value = engine.step((now - previous) / 1_000_000_000f, currentWorking, currentRoaming, motion.reduced, currentActivity)
                presence = engine.presence
                if (engine.presence == CompanionPresence.HIDDEN) currentOnHidden()
                previous = now
            }
        }
    }
    val slop = LocalViewConfiguration.current.touchSlop
    val longPressTime = LocalViewConfiguration.current.longPressTimeoutMillis
    val feedback = LocalHapticFeedback.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // Physical coordinates everywhere, independent of the application's layout direction.
    Box(Modifier.matchParentSize().clipToBounds()) {
    CompanionArtwork(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { IntOffset(pose.value.x.roundToInt(), pose.value.y.roundToInt()) }
        .onGloballyPositioned { coordinates[0] = it }
        .size(60.dp).testTag("omni-companion")
        .semantics {
            contentDescription = "Omni companion"
            stateDescription = when {
                presence == CompanionPresence.LEAVING -> "Leaving for a little break"
                presence == CompanionPresence.RETURNING -> "Happy you called me back"
                else -> when (activity) {
                CompanionActivity.WAITING -> "Waiting for your decision"
                CompanionActivity.LISTENING -> "Listening with you"
                CompanionActivity.ERROR -> "Something needs attention"
                else -> if (working) "Working alongside you" else "Your chat companion"
                }
            }
            onClick("Play with Omni") { engine.tap(motion.reduced); syncPose(); true }
            onLongClick("Companion controls") { openMenu(); true }
            customActions = listOf(
                CustomAccessibilityAction("Return to message box") { engine.returnToComposer(motion.reduced); syncPose(); true },
                CustomAccessibilityAction("Hide for 5 minutes") { hideForNow(); true })
        }
        .pointerInput(engine, motion.reduced) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tracker = VelocityTracker()
                fun worldPoint(point: Offset) = coordinates[0]?.takeIf { it.isAttached }?.localToRoot(point)
                    ?: (point + Offset(engine.pose.x, engine.pose.y) + currentOrigin)
                tracker.addPosition(down.uptimeMillis, worldPoint(down.position))
                var previous = worldPoint(down.position)
                var previousEventTime = down.uptimeMillis
                var distance = Offset.Zero
                var grabbed = false
                var longPressed = false
                var ended = false
                menuOpen = false; engine.grab(); syncPose(); down.consume()
                val deadline = down.uptimeMillis + longPressTime
                try {
                    while (!ended) {
                        val event = if (!grabbed && !longPressed) withTimeoutOrNull((deadline - previousEventTime).coerceAtLeast(1L)) { awaitPointerEvent() }
                            else awaitPointerEvent()
                        if (event == null) { longPressed = true; openMenu(); feedback.performHapticFeedback(HapticFeedbackType.LongPress); continue }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val point = worldPoint(change.position)
                        tracker.addPosition(change.uptimeMillis, point)
                        if (!grabbed && !longPressed && change.uptimeMillis >= deadline &&
                            (distance + point - previous).getDistance() <= slop) {
                            longPressed = true; openMenu(); feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        if (!change.pressed) {
                            if (longPressed) { /* The controls stay open after the finger is released. */ }
                            else if (grabbed) {
                                val velocity = tracker.calculateVelocity()
                                engine.release(velocity.x, motion.reduced, velocity.y)
                            } else { engine.tap(motion.reduced); feedback.performHapticFeedback(HapticFeedbackType.LongPress) }
                            change.consume(); ended = true
                        } else {
                            val delta = point - previous
                            distance += delta
                            if (!longPressed && !grabbed && distance.getDistance() > slop) { engine.drag(distance.x, distance.y); grabbed = true }
                            else if (!longPressed && grabbed) engine.drag(delta.x, delta.y)
                            change.consume()
                        }
                        previous = point
                        previousEventTime = change.uptimeMillis
                        syncPose()
                    }
                } finally {
                    if (!ended && !longPressed) { engine.release(0f, motion.reduced); syncPose() }
                }
            }
        }, pose = { pose.value })
    if (menuOpen) {
        val density = LocalDensity.current
        val width = with(density) { minOf(224.dp.toPx(), (scene.width - 24.dp.toPx()).coerceAtLeast(1f)).toDp() }
        val menuWidth = with(density) { width.toPx() }
        val menuHeight = with(density) { 168.dp.toPx() }
        Surface(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset {
            IntOffset((engine.pose.x - menuWidth / 2 + scene.size / 2).coerceIn(0f, (scene.width - menuWidth).coerceAtLeast(0f)).roundToInt(),
                (engine.pose.y - menuHeight - 8.dp.toPx()).coerceIn(0f, (scene.height - menuHeight).coerceAtLeast(0f)).roundToInt())
        }.width(width).onGloballyPositioned { menuBounds[0] = it.unclippedRootRect().translate(-origin) }.testTag("companion-controls"),
            shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
            Column(Modifier.padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Little Omni", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { menuOpen = false }) { Text("Close") }
                }
                TextButton(onClick = { menuOpen = false; engine.returnToComposer(motion.reduced); syncPose() }, Modifier.fillMaxWidth()) { Text("Back to message box") }
                TextButton(onClick = ::hideForNow, Modifier.fillMaxWidth()) { Text("Hide for 5 minutes") }
            }
        }
    }
    }
}

