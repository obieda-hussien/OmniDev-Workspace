package com.omnidev.workspace.ui.assistant

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Maps a drag in a letterboxed preview to exact source pixels, independent of RTL. */
object ScreenSelection {
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

    fun crop(
        imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float,
        startX: Float, startY: Float, endX: Float, endY: Float
    ): Crop? {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0 ||
            listOf(viewWidth, viewHeight, startX, startY, endX, endY).any { !it.isFinite() }) return null
        val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
        val offsetX = (viewWidth - imageWidth * scale) / 2
        val offsetY = (viewHeight - imageHeight * scale) / 2
        val left = floor((min(startX, endX) - offsetX) / scale).toInt().coerceIn(0, imageWidth)
        val top = floor((min(startY, endY) - offsetY) / scale).toInt().coerceIn(0, imageHeight)
        val right = ceil((max(startX, endX) - offsetX) / scale).toInt().coerceIn(0, imageWidth)
        val bottom = ceil((max(startY, endY) - offsetY) / scale).toInt().coerceIn(0, imageHeight)
        return if (right - left < 4 || bottom - top < 4) null
        else Crop(left, top, right - left, bottom - top)
    }
}
