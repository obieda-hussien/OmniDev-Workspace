package com.omnidev.workspace.ui.companion

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform

private class CompanionArt {
    val fill = Brush.linearGradient(listOf(Color(0xFFF1ECFF), Color(0xFFB8AAF4), Color(0xFF8E8DE5), Color(0xFF7063C4)), Offset(20f, 12f), Offset(48f, 63f))
    val body = Path().apply {
        moveTo(12f, 40f); cubicTo(12f, 29f, 17f, 22f, 21f, 20f)
        cubicTo(26f, 17f, 32f, 16f, 36f, 18f); cubicTo(44f, 20f, 53f, 32f, 52f, 47f)
        cubicTo(52f, 56f, 44f, 61f, 32f, 61f); cubicTo(19f, 61f, 11f, 56f, 12f, 40f); close()
    }
    val leftEar = Path().apply {
        moveTo(21f, 23f); cubicTo(17f, 12f, 18f, 3f, 24f, 5f)
        cubicTo(28f, 6f, 29f, 16f, 30f, 22f); close()
    }
    val rightEar = Path().apply {
        moveTo(35f, 22f); cubicTo(35f, 10f, 40f, 3f, 44f, 6f)
        cubicTo(48f, 9f, 46f, 18f, 43f, 25f); close()
    }
    val smile = Path().apply { moveTo(29f, 47.5f); quadraticTo(32f, 51f, 35f, 47.5f) }
    val frown = Path().apply { moveTo(29f, 50f); quadraticTo(32f, 46.5f, 35f, 50f) }
    val happyEye = Path().apply { moveTo(-2.6f, 1f); quadraticTo(0f, -3f, 2.6f, 1f) }
}

/** Cache the vector paths; only small transforms, expressions and pupils change during animation. */
@Composable
internal fun CompanionArtwork(modifier: Modifier = Modifier, pose: () -> CompanionPose = { CompanionPose() }) {
    val art = remember { CompanionArt() }
    Canvas(modifier) { drawCompanion(pose(), art) }
}

private fun DrawScope.drawCompanion(pose: CompanionPose, art: CompanionArt) {
    val unit = size.width / 64f
    val ink = Color(0xFF302950)
    val line = Stroke(1.5f, cap = StrokeCap.Round)
    scale(unit, unit, Offset.Zero) {
        val shadow = (1f - pose.lift / size.width.coerceAtLeast(1f)).coerceIn(.25f, 1f)
        drawOval(Color(0xFF7974B7).copy(alpha = .16f * shadow), Offset(15f, 60f), Size(34f, 4f))
        if (pose.mood == CompanionMood.SLEEPY) {
            drawRoundRect(Color(0xFFE7DDFB), Offset(9f, 57f), Size(46f, 6f), androidx.compose.ui.geometry.CornerRadius(3f))
            drawLine(Color(0xFFB6A5E3), Offset(13f, 62f), Offset(51f, 62f), 1.2f, StrokeCap.Round)
        }
        if (pose.sparkle > 0f) for (point in listOf(Offset(8f, 26f), Offset(54f, 16f), Offset(57f, 50f))) {
            val radius = pose.sparkle * 2.5f
            val color = Color(0xFFF0D391).copy(alpha = pose.sparkle)
            drawLine(color, point - Offset(radius, 0f), point + Offset(radius, 0f), 1.3f, StrokeCap.Round)
            drawLine(color, point - Offset(0f, radius), point + Offset(0f, radius), 1.3f, StrokeCap.Round)
        }
        rotate(pose.rotation + pose.bodyTilt, Offset(32f, 34f)) {
            scale(1f / pose.stretch, pose.stretch, Offset(32f, 61f)) {
                rotate(pose.earTilt, Offset(25f, 22f)) {
                    drawPath(art.leftEar, art.fill); drawPath(art.leftEar, Color(0xFFB7ADF2), style = Stroke(.8f))
                    drawLine(Color.White.copy(alpha = .5f), Offset(22f, 11f), Offset(24f, 17f), 2f, StrokeCap.Round)
                }
                rotate(-pose.earTilt * .8f, Offset(39f, 22f)) {
                    drawPath(art.rightEar, art.fill); drawPath(art.rightEar, Color(0xFFB7ADF2), style = Stroke(.8f))
                    drawLine(Color.White.copy(alpha = .42f), Offset(41f, 11f), Offset(40f, 18f), 1.8f, StrokeCap.Round)
                }
                drawPath(art.body, art.fill); drawPath(art.body, Color(0xFFB7ADF2).copy(alpha = .8f), style = Stroke(.8f))
                drawOval(Color.White.copy(alpha = .45f), Offset(19f, 24f), Size(15f, 6f))
                drawOval(Color(0xFFEFC7F3).copy(alpha = .6f), Offset(17f, 45f), Size(7f, 3.5f))
                drawOval(Color(0xFFEFC7F3).copy(alpha = .6f), Offset(41f, 45f), Size(7f, 3.5f))
                val sleepy = pose.mood == CompanionMood.SLEEPY || pose.blink
                for (eye in listOf(25f, 39f)) {
                    val x = eye + pose.lookX * 2.2f
                    val y = 40f + pose.lookY * 1.8f
                    if (sleepy) drawLine(ink, Offset(x - 2.5f, y), Offset(x + 2.5f, y), 2f, StrokeCap.Round)
                    else if (pose.mood == CompanionMood.HAPPY) {
                        withTransform({ translate(x, y) }) { drawPath(art.happyEye, ink, style = line) }
                    } else {
                        val h = when (pose.mood) { CompanionMood.SURPRISED, CompanionMood.WAITING -> 8f; CompanionMood.FOCUSED -> 5.5f;
                            CompanionMood.GUARDED -> 4.8f; CompanionMood.ANNOYED -> 3.5f; else -> 6.5f }
                        drawOval(ink, Offset(x - 2.4f, y - 3f), Size(4.8f, h))
                        drawCircle(Color.White.copy(alpha = .9f), 1f, Offset(x - .4f + pose.lookX * .5f, y - 1.3f + pose.lookY * .3f))
                    }
                }
                if (pose.mood == CompanionMood.ANNOYED) {
                    drawLine(ink, Offset(22f, 33f), Offset(28f, 36f), 1.3f, StrokeCap.Round)
                    drawLine(ink, Offset(36f, 36f), Offset(42f, 33f), 1.3f, StrokeCap.Round)
                }
                if (pose.mood in listOf(CompanionMood.FOCUSED, CompanionMood.CONCERNED, CompanionMood.WAITING, CompanionMood.SAD)) {
                    val worried = pose.mood == CompanionMood.CONCERNED || pose.mood == CompanionMood.SAD
                    drawLine(ink.copy(alpha = .7f), Offset(23f, if (worried) 34f else 33f), Offset(27f, if (worried) 32f else 34f), 1.1f, StrokeCap.Round)
                    drawLine(ink.copy(alpha = .7f), Offset(37f, if (worried) 32f else 34f), Offset(41f, if (worried) 34f else 33f), 1.1f, StrokeCap.Round)
                }
                if (pose.mouthOpen > .05f) drawOval(ink, Offset(29f, 47f), Size(6f, 2f + pose.mouthOpen * 6f))
                else when (pose.mood) {
                    CompanionMood.SURPRISED -> drawOval(ink, Offset(30f, 47f), Size(4f, 5f))
                    CompanionMood.CONCERNED, CompanionMood.SAD -> drawPath(art.frown, ink, style = line)
                    CompanionMood.ANNOYED, CompanionMood.GUARDED -> drawLine(ink, Offset(29f, 49f), Offset(35f, 49f), 1.4f, StrokeCap.Round)
                    else -> drawPath(art.smile, ink.copy(alpha = .85f), style = line)
                }
                val badge = when (pose.mood) {
                    CompanionMood.WORKING, CompanionMood.FOCUSED, CompanionMood.LISTENING -> Color(0xFF60E1D8)
                    CompanionMood.WAITING -> Color(0xFFF0D391)
                    CompanionMood.CONCERNED -> Color(0xFFF2A5B0)
                    else -> null
                }
                if (badge != null) {
                    drawCircle(badge, 2.4f, Offset(47f, 28f))
                    drawCircle(Color.White.copy(alpha = .75f), .8f, Offset(46.5f, 27.4f))
                }
            }
        }
    }
}
