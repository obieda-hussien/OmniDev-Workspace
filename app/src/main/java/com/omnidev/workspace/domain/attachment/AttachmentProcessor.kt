package com.omnidev.workspace.domain.attachment

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.AttachmentMediaType
import com.omnidev.workspace.data.model.AttachmentMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Processes multi-modal file attachments (images, PDFs, text files, videos) for
 * inclusion in AI completion requests.
 *
 * ### God Mode — Unrestricted File Picker
 * When [godModeEnabled] is `true`, MIME-type and extension filtering is completely
 * bypassed in [processAttachments]. **Any** file type the user selects is accepted
 * regardless of its MIME classification. This is the intentional behaviour so that
 * a power user with God Mode ON can upload arbitrary binaries, APKs, raw config
 * files, etc. directly into the chat for the agent to reason about.
 *
 * Enforces safety limits (count / total size) even in God Mode:
 * - Maximum 5 files per request
 * - Maximum 15 MB total payload size
 * - Maximum 10 MB per single file
 *
 * The processor resolves URIs using [ContentResolver], classifies media types, validates
 * constraints, and produces a structured list of [AttachmentMeta] ready for API payload
 * construction.
 */
class AttachmentProcessor(
    private val contentResolver: ContentResolver,
    /**
     * When true, MIME-type restrictions and video-model compatibility checks are
     * bypassed. The agent can then receive and reason over any file type.
     *
     * Updated at runtime by ChatViewModel when the user toggles God Mode.
     */
    @Volatile var godModeEnabled: Boolean = false
) {

    companion object {
        /** Maximum number of attachments allowed in a single request. */
        const val MAX_ATTACHMENT_COUNT = 5

        /** Maximum cumulative size of all attachments in bytes (15 MB). */
        const val MAX_TOTAL_SIZE_BYTES = 15L * 1024L * 1024L

        /** Maximum single file size in bytes (10 MB). */
        const val MAX_SINGLE_FILE_SIZE_BYTES = 10L * 1024L * 1024L

        /**
         * Maximum number of characters read from a text/code attachment before truncating.
         *
         * At ~4 chars per token this caps text attachments at roughly 12 000 tokens each,
         * preventing accidental context-window exhaustion when large source files are attached.
         */
        const val MAX_TEXT_CONTENT_CHARS = 48_000

        /** MIME type prefixes for classification. */
        private const val MIME_IMAGE_PREFIX = "image/"
        private const val MIME_VIDEO_PREFIX = "video/"
        private const val MIME_TEXT_PREFIX = "text/"
        private const val MIME_PDF = "application/pdf"
    }

    /**
     * Processes a list of URIs into validated [AttachmentMeta] objects.
     *
     * @param uris The user-selected content URIs.
     * @param targetModel The AI model that will receive these attachments, used to
     *                    determine video handling strategy.
     * @return A [ProcessingResult] containing either the processed attachments or
     *         detailed error/warning information.
     */
    suspend fun processAttachments(
        uris: List<Uri>,
        targetModel: AIModel
    ): ProcessingResult = withContext(Dispatchers.IO) {

        // ── Count Limit ──
        if (uris.size > MAX_ATTACHMENT_COUNT) {
            return@withContext ProcessingResult.Error(
                "Too many attachments: ${uris.size}. Maximum is $MAX_ATTACHMENT_COUNT."
            )
        }

        if (uris.isEmpty()) {
            return@withContext ProcessingResult.Success(emptyList())
        }

        val processed = mutableListOf<AttachmentMeta>()
        val warnings = mutableListOf<String>()
        var totalSize = 0L

        for (uri in uris) {
            val meta = resolveAttachmentMeta(uri)
                ?: return@withContext ProcessingResult.Error(
                    "Unable to resolve file metadata for URI: $uri"
                )

            // ── Single-file size limit ──
            if (meta.sizeBytes > MAX_SINGLE_FILE_SIZE_BYTES) {
                return@withContext ProcessingResult.Error(
                    "File '${meta.fileName}' exceeds the ${MAX_SINGLE_FILE_SIZE_BYTES / (1024 * 1024)}MB " +
                        "single-file limit (${meta.sizeBytes / (1024 * 1024)}MB)."
                )
            }

            // ── Cumulative size limit ──
            totalSize += meta.sizeBytes
            if (totalSize > MAX_TOTAL_SIZE_BYTES) {
                return@withContext ProcessingResult.Error(
                    "Total attachment size exceeds ${MAX_TOTAL_SIZE_BYTES / (1024 * 1024)}MB limit. " +
                        "Remove some files and try again."
                )
            }

            // ── God Mode: skip all MIME / video / vision checks ─────────────────
            // In God Mode, we accept ANY file type unconditionally. The agent is
            // responsible for deciding how to handle unknown binary payloads.
            if (godModeEnabled) {
                processed.add(meta)
                continue
            }
            // ─────────────────────────────────────────────────────────────────

            // ── Video Handling ──
            if (meta.mediaType == AttachmentMediaType.VIDEO) {
                if (targetModel.supportsVideo) {
                    // Model natively supports video URIs — pass through directly
                    processed.add(meta)
                } else {
                    // Model does not support video — reject gracefully
                    warnings.add(
                        "Video '${meta.fileName}' skipped: ${targetModel.displayName} does not " +
                            "support native video input. Consider using a Gemini model for video support."
                    )
                    // Don't add to processed list — graceful rejection
                }
                continue
            }

            // ── Vision check for images ──
            if (meta.mediaType == AttachmentMediaType.IMAGE && !targetModel.supportsVision) {
                warnings.add(
                    "Image '${meta.fileName}' attached but ${targetModel.displayName} does not " +
                        "support vision. The image will be ignored by the model."
                )
            }

            processed.add(meta)
        }

        ProcessingResult.Success(attachments = processed, warnings = warnings)
    }

    /**
     * Reads a text or source-code attachment and returns its content as a [String].
     *
     * Large files are truncated at [maxChars] to prevent token exhaustion when sending
     * code or document attachments to LLM APIs. A truncation notice is appended so the
     * model is aware that content was cut.
     *
     * @param uri The content URI of the text/code file.
     * @param maxChars Maximum characters to return. Defaults to [MAX_TEXT_CONTENT_CHARS].
     * @return The (possibly truncated) file content, or `null` if the file cannot be read.
     */
    suspend fun readTextWithTruncation(
        uri: Uri,
        maxChars: Int = MAX_TEXT_CONTENT_CHARS
    ): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use(InputStream::readBytes)
                ?: return@withContext null
            val text = bytes.decodeToString()
            if (text.length <= maxChars) {
                text
            } else {
                text.take(maxChars) +
                    "\n\n[... file truncated at $maxChars characters to prevent token exhaustion ...]"
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads the raw bytes of an attachment for API payload construction.
     * Callers should use this selectively — prefer streaming for large files.
     *
     * @param uri The content URI to read.
     * @return Byte array of the file content, or null if unreadable.
     */
    suspend fun readAttachmentBytes(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use(InputStream::readBytes)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns a specific, non-wildcard MIME type for the file at [uri].
     *
     * Falls back to extension-based detection when Android's [ContentResolver] returns
     * null or a wildcard type such as "application/star" (where star is the literal
     * asterisk), which providers (Gemini, Anthropic) reject with HTTP 400.
     * Explicit overrides are applied for common source-code extensions whose MIME
     * types are not reliably detected by Android's MIME type map.
     */
    fun getMimeType(uri: Uri): String {
        val resolved = contentResolver.getType(uri)
        // Use resolved type only if it's specific (no wildcards)
        if (!resolved.isNullOrBlank() && !resolved.contains("*")) {
            return resolved
        }

        // Fall back to extension-based detection with explicit code-file overrides
        val fileName = uri.lastPathSegment ?: ""
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts"          -> "text/plain"
            "java"               -> "text/plain"
            "py"                 -> "text/plain"
            "js", "ts", "jsx", "tsx" -> "text/plain"
            "xml"                -> "text/xml"
            "html", "htm"        -> "text/html"
            "css"                -> "text/css"
            "json"               -> "application/json"
            "md", "txt", "csv"   -> "text/plain"
            "gradle", "toml", "yaml", "yml", "properties", "env" -> "text/plain"
            "sh", "bash"         -> "text/plain"
            "c", "cpp", "h", "hpp", "rs", "go", "swift", "rb", "php" -> "text/plain"
            "pdf"                -> "application/pdf"
            "zip"                -> "application/zip"
            "png"                -> "image/png"
            "jpg", "jpeg"        -> "image/jpeg"
            "gif"                -> "image/gif"
            "webp"               -> "image/webp"
            else -> {
                // Try the system MimeTypeMap as a last resort
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    ?: "application/octet-stream"
            }
        }
    }

    /**
     * Reads an image attachment and returns it as a Base64-encoded string suitable
     * for inclusion in a vision API request.
     *
     * @param uri The content URI of the image.
     * @return Base64-encoded image string, or null if the file cannot be read.
     */
    suspend fun readImageAsBase64(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use(InputStream::readBytes)
                ?: return@withContext null
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    // ──────────────────────────────────────────────
    //  Internal: URI Resolution
    // ──────────────────────────────────────────────

    /**
     * Resolves a content URI into an [AttachmentMeta] using [ContentResolver] queries.
     *
     * Uses [getMimeType] (extension-aware fallback) instead of the raw [ContentResolver.getType]
     * to ensure source-code files (`.kt`, `.py`, `.js`, etc.) are correctly classified as
     * `text/plain` rather than `application/octet-stream`, which APIs reject.
     */
    private fun resolveAttachmentMeta(uri: Uri): AttachmentMeta? {
        // Use the extension-aware getMimeType() for accurate MIME classification of code files.
        val mimeType = getMimeType(uri)
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return null

        return cursor.use {
            if (!it.moveToFirst()) return null

            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)

            val fileName = if (nameIndex >= 0) it.getString(nameIndex) else uri.lastPathSegment ?: "unknown"
            val sizeBytes = if (sizeIndex >= 0) it.getLong(sizeIndex) else 0L

            AttachmentMeta(
                uri = uri.toString(),
                mimeType = mimeType,
                fileName = fileName,
                sizeBytes = sizeBytes,
                mediaType = classifyMediaType(mimeType)
            )
        }
    }

    /**
     * Classifies a MIME type into our internal [AttachmentMediaType] enum.
     */
    private fun classifyMediaType(mimeType: String): AttachmentMediaType = when {
        mimeType.startsWith(MIME_IMAGE_PREFIX) -> AttachmentMediaType.IMAGE
        mimeType.startsWith(MIME_VIDEO_PREFIX) -> AttachmentMediaType.VIDEO
        mimeType.startsWith(MIME_TEXT_PREFIX) -> AttachmentMediaType.TEXT
        mimeType == MIME_PDF -> AttachmentMediaType.PDF
        else -> AttachmentMediaType.UNKNOWN
    }

    /**
     * Fallback MIME type guess from the URI's file extension.
     */
    private fun guessMimeType(uri: Uri): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    // ──────────────────────────────────────────────
    //  Result Types
    // ──────────────────────────────────────────────

    sealed class ProcessingResult {
        /** Successfully processed attachments, possibly with non-fatal warnings. */
        data class Success(
            val attachments: List<AttachmentMeta>,
            val warnings: List<String> = emptyList()
        ) : ProcessingResult()

        /** Fatal processing error — the entire batch is rejected. */
        data class Error(val message: String) : ProcessingResult()
    }
}
