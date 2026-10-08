package com.omnidev.workspace.ui.chat

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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.isActive
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
import kotlin.math.cos
import kotlin.math.abs

/** Soft gradients work on Android 11 without a full-size blur bitmap or RenderEffect. */
@Composable
internal fun MediaGenerationPreview(status: MediaCardStatus, type: AttachmentMediaType, modifier: Modifier = Modifier) {
    val motion = LocalOmniMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val animate = status.ambientAnimated && !motion.reduced
    // Compact phones keep a smaller, 30fps canvas instead of losing all motion.
    // The lifecycle stops the frame loop when the app/card leaves the foreground.
    val drift by produceState(.15f, animate, lifecycle, motion.compact) {
        if (animate) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var origin = 0L
            var last = 0L
            while (isActive) withFrameNanos { frame ->
                if (origin == 0L) origin = frame
                if (frame - last >= if (motion.compact) 33_000_000L else 16_000_000L) {
                    value = ((frame - origin) % 8_000_000_000L) / 8_000_000_000f
                    last = frame
                }
            }
        }
    }
    val failed = status.stage == MediaStage.FAILED
    val base = if (failed) Color(0xFF211D2A) else Color(0xFF15122C)
    val purple = if (failed) Color(0xFF735869) else Color(0xFF8E8DE5)
    val pink = if (failed) Color(0xFF835357) else Color(0xFFDE96CA)
    Box(modifier.background(base).testTag("media-creation-${status.stage.name.lowercase()}")
        .semantics { stateDescription = status.title; if (!status.terminal) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().testTag("media-atmosphere-${type.name.lowercase()}")) {
            val t = drift * 6.283185f
            val center = Offset(size.width * (.5f + .28f * sin(t)), size.height * (.46f + .19f * cos(t)))
            drawRect(Brush.radialGradient(listOf(purple.copy(alpha = .7f), purple.copy(alpha = 0f)), center, size.width * .85f))
            drawRect(Brush.radialGradient(listOf(pink.copy(alpha = .45f), pink.copy(alpha = 0f)), Offset(size.width * (.5f + .35f * cos(t)), size.height * (.6f + .2f * sin(t))), size.width * .65f))
            if (!status.terminal) when (type) {
                AttachmentMediaType.AUDIO -> musicAtmosphere(t, motion.compact, purple, pink)
                AttachmentMediaType.VIDEO -> videoAtmosphere(drift, purple, pink)
                else -> imageAtmosphere(t, purple, pink)
            }
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
            Text(when(status.stage) { MediaStage.GENERATING, MediaStage.LOADING -> when (type) { AttachmentMediaType.AUDIO -> "COMPOSING YOUR SOUND"; AttachmentMediaType.VIDEO -> "CREATING YOUR SCENE"; else -> "BRINGING YOUR IMAGE TO LIFE" }
                MediaStage.DOWNLOADING -> "PROVIDER FINISHED · SAVING FILE"
                MediaStage.QUEUED, MediaStage.WAITING -> if (status.stage == MediaStage.QUEUED) "YOUR REQUEST IS IN THE QUEUE" else "WAITING TO CONTINUE"
                MediaStage.CANCELLED -> "STOPPED ON THIS DEVICE"
                else -> "NO COMPLETED OUTPUT" }, color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall)
            if (status.animated && !motion.reduced) LinearProgressIndicator(Modifier.width(116.dp).height(3.dp).clip(CircleShape), color = Color(0xFFCBCBFF), trackColor = Color.White.copy(alpha = .12f))
        }
    }
}


/** Image: translucent sheets and a moving soft-light reveal. */
private fun DrawScope.imageAtmosphere(t: Float, purple: Color, pink: Color) {
    repeat(3) { index ->
        val wave = t + index * 1.8f
        val width = size.width * (.44f + index * .07f)
        val height = size.height * .52f
        val left = size.width * (.5f + .12f * sin(wave)) - width / 2
        val top = size.height * (.45f + .1f * cos(wave)) - height / 2
        drawRoundRect(Brush.linearGradient(listOf(purple.copy(alpha = .18f), pink.copy(alpha = .06f)),
            Offset(left, top), Offset(left + width, top + height)), Offset(left, top), Size(width, height), CornerRadius(28.dp.toPx()))
        drawRoundRect(Color.White.copy(alpha = .12f), Offset(left, top), Size(width, height), CornerRadius(28.dp.toPx()), style = Stroke(1.dp.toPx()))
    }
    val glow = Offset(size.width * (.5f + .4f * sin(t)), size.height * (.5f + .25f * cos(t)))
    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .2f), Color.Transparent), glow, size.width * .45f))
}

/** Video: an endlessly travelling film strip, with a soft scan across the frames. */
private fun DrawScope.videoAtmosphere(progress: Float, purple: Color, pink: Color) {
    val width = size.width * .48f
    val gap = size.width * .045f
    val pitch = width + gap
    val offset = progress * pitch * 3f % pitch
    repeat(4) { index ->
        val left = index * pitch - offset - pitch
        val top = size.height * .2f
        val frame = Size(width, size.height * .58f)
        drawRoundRect(Brush.verticalGradient(listOf(purple.copy(alpha = .24f), pink.copy(alpha = .1f))), Offset(left, top), frame, CornerRadius(16.dp.toPx()))
        drawRoundRect(Color.White.copy(alpha = .16f), Offset(left, top), frame, CornerRadius(16.dp.toPx()), style = Stroke(1.dp.toPx()))
        repeat(4) { hole ->
            val x = left + width * (.15f + hole * .2f)
            for (y in listOf(top - 9.dp.toPx(), top + frame.height + 5.dp.toPx()))
                drawRoundRect(Color.White.copy(alpha = .2f), Offset(x, y), Size(7.dp.toPx(), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        }
    }
    val scan = Offset(size.width * progress, size.height * .5f)
    drawRect(Brush.radialGradient(listOf(pink.copy(alpha = .23f), Color.Transparent), scan, size.width * .55f))
}

/** Music: a breathing equalizer and expanding sound rings; never a fake waveform of the output. */
private fun DrawScope.musicAtmosphere(t: Float, compact: Boolean, purple: Color, pink: Color) {
    val bars = if (compact) 18 else 30
    val gap = size.width * .84f / bars
    repeat(bars) { index ->
        val wave = abs(sin(t * 2f + index * .57f) * cos(t + index * .23f))
        val height = size.height * (.08f + .44f * wave)
        val x = size.width * .08f + index * gap
        drawRoundRect(Brush.verticalGradient(listOf(pink.copy(alpha = .35f), purple.copy(alpha = .16f))),
            Offset(x, (size.height - height) / 2), Size(gap * .45f, height), CornerRadius(gap))
    }
    repeat(2) { index ->
        val phase = (t / 6.283185f + index * .5f) % 1f
        drawCircle(purple.copy(alpha = (1f - phase) * .18f), size.minDimension * (.16f + phase * .42f),
            Offset(size.width / 2, size.height / 2), style = Stroke(2.dp.toPx()))
    }
}
