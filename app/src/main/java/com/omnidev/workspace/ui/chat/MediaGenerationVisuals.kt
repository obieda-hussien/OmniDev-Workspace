package com.omnidev.workspace.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.chatmedia.*
import com.omnidev.workspace.data.model.AttachmentMediaType
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import kotlin.math.sin

/** Soft gradients work on Android 11 without a full-size blur bitmap or RenderEffect. */
@Composable
internal fun MediaGenerationPreview(status: MediaCardStatus, type: AttachmentMediaType, modifier: Modifier = Modifier) {
    val motion = LocalOmniMotion.current
    val animate = status.animated && !motion.reduced && !motion.compact
    val drift = if (animate) {
        val transition = rememberInfiniteTransition(label = "creation-atmosphere")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4600, easing = LinearEasing), RepeatMode.Reverse), label = "creation-drift")
    } else remember { mutableFloatStateOf(.35f) }
    val failed = status.stage == MediaStage.FAILED
    val base = if (failed) Color(0xFF211D2A) else Color(0xFF15122C)
    val purple = if (failed) Color(0xFF735869) else Color(0xFF8E8DE5)
    val pink = if (failed) Color(0xFF835357) else Color(0xFFDE96CA)
    Box(modifier.background(base).testTag("media-creation-${status.stage.name.lowercase()}")
        .semantics { stateDescription = status.title; if (!status.terminal) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val t = drift.value
            val center = Offset(size.width * (.25f + .25f * t), size.height * (.36f + .08f * sin(t * 6.28f)))
            drawRect(Brush.radialGradient(listOf(purple.copy(alpha = .7f), purple.copy(alpha = 0f)), center, size.width * .85f))
            drawRect(Brush.radialGradient(listOf(pink.copy(alpha = .45f), pink.copy(alpha = 0f)), Offset(size.width * (.86f - .22f * t), size.height * .85f), size.width * .65f))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .45f))))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
            Box(Modifier.size(54.dp).clip(CircleShape).background(Color.White.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                Icon(when(status.stage) { MediaStage.FAILED -> Icons.Default.ErrorOutline; MediaStage.CANCELLED -> Icons.Default.Close
                    MediaStage.WAITING, MediaStage.QUEUED -> Icons.Default.Schedule
                    else -> when(type) { AttachmentMediaType.VIDEO -> Icons.Default.Movie; AttachmentMediaType.AUDIO -> Icons.Default.GraphicEq; else -> Icons.Default.AutoAwesome } },
                    null, Modifier.size(25.dp), tint = Color.White)
            }
            Text(status.title, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(when(status.stage) { MediaStage.GENERATING, MediaStage.LOADING -> "CREATIVE PREVIEW · IN PROGRESS"
                MediaStage.DOWNLOADING -> "PROVIDER FINISHED · SAVING FILE"
                MediaStage.QUEUED, MediaStage.WAITING -> "WAITING · NO NEW GENERATION"
                MediaStage.CANCELLED -> "STOPPED ON THIS DEVICE"
                else -> "NO COMPLETED OUTPUT" }, color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall)
            if (status.animated && !motion.reduced) LinearProgressIndicator(Modifier.width(116.dp).height(3.dp).clip(CircleShape), color = Color(0xFFCBCBFF), trackColor = Color.White.copy(alpha = .12f))
        }
    }
}
