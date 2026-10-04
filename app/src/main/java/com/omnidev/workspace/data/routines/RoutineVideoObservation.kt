package com.omnidev.workspace.data.routines

import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.AttachmentMediaType

/** Keeps image bytes out of text context, the console and learning logs. */
object RoutineVideoObservation {
    data class Sample(val observation: String, val image: AttachmentMeta?)
    fun extract(output: String, supportsVision: Boolean): Sample {
        val raw = Regex("\\[SCREENSHOT_BASE64]\\s*data:image/jpeg;base64,([A-Za-z0-9+/=\\r\\n]+)\\s*\\[/SCREENSHOT_BASE64]").find(output)
        val text = output.replace(Regex("\\[SCREENSHOT_BASE64][\\s\\S]*?\\[/SCREENSHOT_BASE64]"), "[Video sample image]")
        if (!supportsVision) return Sample("$text\nSelected model cannot view images. Use a vision model or review samples locally.", null)
        val encoded = raw?.groupValues?.get(1)?.filterNot(Char::isWhitespace)
        if (encoded.isNullOrBlank() || encoded.length > 600_000) return Sample("Video sample could not be decoded within the size limit.", null)
        return Sample(text, AttachmentMeta(uri = "routine-video:sample", mimeType = "image/jpeg", fileName = "video-sample.jpg",
            sizeBytes = encoded.length.toLong() * 3 / 4, mediaType = AttachmentMediaType.IMAGE, base64Data = encoded))
    }
}
