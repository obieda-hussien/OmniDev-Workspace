package com.omnidev.workspace.data.routines

import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.AttachmentMediaType
import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlin.io.encoding.Base64

/** Keeps image bytes out of text context, the console and learning logs. */
object RoutineVideoObservation {
    private const val MAX_ENCODED_CHARS = 600_000
    private val frame = Regex("\\[SCREENSHOT_BASE64]\\s*data:image/jpeg;base64,([A-Za-z0-9+/=\\r\\n]+)\\s*\\[/SCREENSHOT_BASE64]")
    private val frameBlock = Regex("\\[SCREENSHOT_BASE64][\\s\\S]*?\\[/SCREENSHOT_BASE64]")

    data class Sample(val result: ToolExecutionResult, val image: AttachmentMeta?)

    fun extract(result: ToolExecutionResult, supportsVision: Boolean): Sample {
        if (result.isError) return Sample(result, null)
        val encoded = frame.find(result.output)?.groupValues?.get(1)?.filterNot(Char::isWhitespace)
        val bytes = if (encoded.isNullOrEmpty() || encoded.length > MAX_ENCODED_CHARS) null
        else try {
            Base64.decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (bytes == null || !hasJpegMarkers(bytes)) {
            return Sample(result.copy(
                output = "No usable video frame was available within the sample size limit.",
                isError = true,
                classification = "ATTACHMENT_CONTENT_UNAVAILABLE",
                retryable = false
            ), null)
        }
        if (!supportsVision) {
            return Sample(result.copy(
                output = "Selected model cannot view images. Use a vision model or review samples locally.",
                isError = true,
                classification = "MODEL_VISION_UNAVAILABLE",
                retryable = false
            ), null)
        }
        val text = frameBlock.replace(result.output, "[One sparse video frame attached for model inspection]")
        return Sample(result.copy(output = text), AttachmentMeta(
            uri = "routine-video:sample",
            mimeType = "image/jpeg",
            fileName = "video-sample.jpg",
            sizeBytes = bytes.size.toLong(),
            mediaType = AttachmentMediaType.IMAGE,
            base64Data = encoded
        ))
    }

    private fun hasJpegMarkers(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()
}
