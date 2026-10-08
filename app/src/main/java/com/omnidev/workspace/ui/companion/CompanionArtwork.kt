package com.omnidev.workspace.ui.companion

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
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

/** Resolution-independent artwork: soft lilac glass, tiny ears, expressive ink eyes, no legs. */
@Composable
internal fun CompanionArtwork(modifier: Modifier = Modifier, pose: () -> CompanionPose = { CompanionPose() }) {
    Canvas(modifier) { drawCompanion(pose()) }
}

private fun DrawScope.drawCompanion(pose: CompanionPose) {
    val unit = size.width / 64f
    scale(unit, unit, Offset.Zero) {
        val shadow = (1f - pose.lift / (size.width.coerceAtLeast(1f))).coerceIn(.25f, 1f)
        drawOval(Color(0xFF7974B7).copy(alpha = .16f * shadow), Offset(15f, 60f), Size(34f, 4f))
        rotate(pose.rotation, Offset(32f, 34f)) {
            scale(1f / pose.stretch, pose.stretch, Offset(32f, 61f)) {
                val body = Path().apply {
                    moveTo(12f, 40f)
                    cubicTo(12f, 29f, 17f, 22f, 21f, 20f)
                    cubicTo(17f, 10f, 19f, 3f, 24f, 5f)
                    cubicTo(28f, 6f, 29f, 15f, 30f, 18f)
                    cubicTo(32f, 17f, 34f, 17f, 36f, 18f)
                    cubicTo(36f, 9f, 40f, 3f, 44f, 6f)
                    cubicTo(48f, 9f, 46f, 18f, 44f, 23f)
                    cubicTo(50f, 29f, 53f, 39f, 52f, 47f)
                    cubicTo(52f, 56f, 44f, 61f, 32f, 61f)
                    cubicTo(19f, 61f, 11f, 56f, 12f, 40f)
                    close()
                }
                drawPath(body, Brush.linearGradient(listOf(Color(0xFFF1ECFF), Color(0xFFB8AAF4), Color(0xFF8E8DE5), Color(0xFF7063C4)), Offset(20f, 12f), Offset(48f, 63f)))
                drawPath(body, Color(0xFFB7ADF2).copy(alpha = .8f), style = Stroke(.8f))
                // Subtle surface highlights stay readable at 48–64dp.
                drawOval(Color.White.copy(alpha = .45f), Offset(19f, 24f), Size(15f, 6f))
                drawLine(Color.White.copy(alpha = .52f), Offset(22f, 11f), Offset(24f, 17f), 2f, StrokeCap.Round)
                drawLine(Color.White.copy(alpha = .42f), Offset(41f, 11f), Offset(40f, 18f), 1.8f, StrokeCap.Round)
                drawOval(Color(0xFFEFC7F3).copy(alpha = .6f), Offset(17f, 45f), Size(7f, 3.5f))
                drawOval(Color(0xFFEFC7F3).copy(alpha = .6f), Offset(41f, 45f), Size(7f, 3.5f))
                val ink = Color(0xFF302950)
                val sleepy = pose.mood == CompanionMood.SLEEPY || pose.blink
                for (x in listOf(25f, 39f)) {
                    if (sleepy) drawLine(ink, Offset(x - 2.5f, 40f), Offset(x + 2.5f, 40f), 2f, StrokeCap.Round)
                    else {
                        val h = if (pose.mood == CompanionMood.SURPRISED) 8f else 6.5f
                        drawOval(ink, Offset(x - 2.4f, 37f), Size(4.8f, h))
                        drawCircle(Color.White.copy(alpha = .9f), 1f, Offset(x - .4f, 38.7f))
                    }
                }
                if (pose.mood == CompanionMood.SURPRISED) drawOval(ink, Offset(30f, 47f), Size(4f, 5f))
                else {
                    val smile = Path().apply { moveTo(29f, 47.5f); quadraticTo(32f, 51f, 35f, 47.5f) }
                    drawPath(smile, ink.copy(alpha = .85f), style = Stroke(1.5f, cap = StrokeCap.Round))
                }
                if (pose.mood == CompanionMood.WORKING) {
                    drawCircle(Color(0xFF60E1D8), 2.4f, Offset(47f, 28f))
                    drawCircle(Color.White.copy(alpha = .75f), .8f, Offset(46.5f, 27.4f))
                }
            }
        }
    }
}
