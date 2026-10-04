package com.omnidev.workspace.data.routines

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Bounded one-time local sampling. Pixels are evidence, never an executable action trace. */
object RoutineVideoImporter {
    suspend fun import(context: Context, uri: Uri, name: String): LearnedRoutine = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Select a video with the system picker" }
        val retriever = MediaMetadataRetriever()
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "routine-videos/$id").apply { mkdirs() }
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("Invalid video")
            require(duration in 1..300_000) { "Use a clip of 5 minutes or less" }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 640
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 640
            require(width in 1..16_384 && height in 1..16_384) { "Invalid video dimensions" }
            if (Build.VERSION.SDK_INT < 27) require(width.toLong() * height <= 2_073_600) { "Use a video of 1080p or less on this Android version" }
            val scale = 640f / maxOf(width, height).coerceAtLeast(640)
            val steps = (0 until 8).mapNotNull { index ->
                coroutineContext.ensureActive()
                val timestamp = duration * index / 8
                val frame = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(timestamp * 1_000,
                    MediaMetadataRetriever.OPTION_CLOSEST, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1)) else retriever.getFrameAtTime(timestamp * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)
                frame ?: return@mapNotNull null
                try {
                    val ratio = 640f / maxOf(frame.width, frame.height).coerceAtLeast(640)
                    val thumbnail = if (ratio < 1) Bitmap.createScaledBitmap(frame, (frame.width * ratio).toInt().coerceAtLeast(1),
                        (frame.height * ratio).toInt().coerceAtLeast(1), true) else frame
                    try { File(directory, "frame-$index.jpg").outputStream().use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
                    finally { if (thumbnail !== frame) thumbnail.recycle() }
                } finally { frame.recycle() }
                RoutineStep(RoutineStepKind.DECISION, "Video sample $index at ${timestamp / 1_000}s: review and teach this step", value = index.toString())
            }
            require(steps.isNotEmpty()) { "No readable frames" }
            LearnedRoutine(id, name.take(120), listOf(name.take(120)), steps, source = "video:$id").also {
                RoutineLearningHub.get(context).store.save(it); RoutineLearningHub.get(context).changed()
            }
        } catch (error: Exception) { directory.deleteRecursively(); throw error }
        finally { retriever.release() }
    }
    fun frameFile(context: Context, recipe: LearnedRoutine, index: Int): File? {
        if (recipe.source != "video:${recipe.id}" || index !in 0..7) return null
        return File(context.filesDir, "routine-videos/${recipe.id}/frame-$index.jpg").takeIf { it.isFile }
    }
    fun deleteFrames(context: Context, recipe: LearnedRoutine) {
        if (recipe.source == "video:${recipe.id}") File(context.filesDir, "routine-videos/${recipe.id}").deleteRecursively()
    }
}
