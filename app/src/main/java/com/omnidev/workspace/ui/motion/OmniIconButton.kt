package com.omnidev.workspace.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Material ripple, accessibility and click timing, with a restrained physical press response. */
@Composable
fun OmniIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    IconButton(onClick = onClick, modifier = modifier,
        enabled = enabled, colors = colors, interactionSource = source) {
        // Animate the visual content, keeping the ripple, hit target and semantics bounds fixed.
        Box(modifier = Modifier.pressResponse(source), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun Modifier.pressResponse(source: MutableInteractionSource): Modifier {
    val policy = LocalOmniMotion.current
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed && !policy.reduced) 0.94f else 1f,
        animationSpec = tween(policy.responseMillis, easing = OmniEasing), label = "press"
    )
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
