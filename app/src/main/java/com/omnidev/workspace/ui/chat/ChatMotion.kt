package com.omnidev.workspace.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import com.omnidev.workspace.ui.motion.OmniEasing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/** Small control changes crossfade; transcript geometry never animates on every token. */
@Composable
internal fun <T> ChatControlTransition(target: T, label: String, content: @Composable (T) -> Unit) {
    val motion = LocalOmniMotion.current
    if (motion.reduced) content(target)
    else AnimatedContent(targetState = target, label = label, transitionSpec = {
        fadeIn(tween(motion.responseMillis, easing = OmniEasing)) togetherWith
            fadeOut(tween(motion.responseMillis / 2, easing = OmniEasing))
    }) { content(it) }
}

/** Bound rich-text parsing during bursts. Conflation always delivers the latest complete chunk. */
@Composable
internal fun rememberStreamedText(content: String): String {
    val latest = rememberUpdatedState(content)
    val compact = LocalOmniMotion.current.compact
    var displayed by remember { mutableStateOf(content) }
    LaunchedEffect(compact) {
        snapshotFlow { latest.value }.conflate().collect { text ->
            displayed = text
            delay(if (compact) 64L else 32L)
        }
    }
    return displayed
}
