package com.omnidev.workspace.data.chatmedia

import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.omnidev.workspace.data.model.*
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Durable imports/generated outputs; downloads and exports stream through bounded temporary files. */
object ChatMediaStore {
    const val MAX_BYTES = 512L * 1024 * 1024
    private const val JOB_PREFIX = "omni-media-job:"
    fun directory(context: Context) = File(context.filesDir, "chat-media").apply { mkdirs() }
    fun type(mime: String) = when {
        mime.startsWith("image/") -> AttachmentMediaType.IMAGE
        mime.startsWith("video/") -> AttachmentMediaType.VIDEO
        mime.startsWith("audio/") -> AttachmentMediaType.AUDIO
        mime == "application/pdf" -> AttachmentMediaType.PDF
        mime.startsWith("text/") -> AttachmentMediaType.TEXT
        else -> AttachmentMediaType.UNKNOWN
    }
    fun safeName(value: String) = value.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(100).ifBlank { "attachment" }
    private fun localFile(context: Context, uri: Uri): File {
        val file = File(uri.path ?: error("Missing file path.")).canonicalFile
        val roots = listOf(Environment.getExternalStorageDirectory().canonicalFile, directory(context).canonicalFile, File(context.filesDir, "assistant_workspace/attachments").canonicalFile) +
            context.getExternalFilesDirs(null).filterNotNull().map { it.canonicalFile }
        require(roots.any { file.path.startsWith(it.path + File.separator) }) { "Select this private file through the file picker to attach it." }
        require(file.isFile && file.canRead()) { "File unavailable. Select it with Attach file to grant access." }
        return file
    }
    suspend fun metadata(context: Context, value: String, name: String? = null): AttachmentMeta? = withContext(Dispatchers.IO) {
        try {
            if (value.startsWith(JOB_PREFIX)) {
                val job = MediaJobStore(context).get(value.removePrefix(JOB_PREFIX)) ?: return@withContext null
                return@withContext AttachmentMeta(value, if (job.kind == "video") "video/mp4" else "image/png",
                    if (job.kind == "video") "Generated video" else "Generated image", 0, if (job.kind == "video") AttachmentMediaType.VIDEO else AttachmentMediaType.IMAGE)
            }
            val uri = if (value.startsWith('/')) Uri.fromFile(File(value)) else Uri.parse(value)
            var mime = MediaReferenceParser.mime(value)
            var size = 0L
            var display = name ?: uri.lastPathSegment ?: "Attachment"
            when (uri.scheme) {
                "file" -> { val file = localFile(context, uri); size = file.length(); display = name ?: file.name }
                "content" -> {
                    mime = context.contentResolver.getType(uri)?.takeIf { '*' !in it } ?: mime
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                        if (it.moveToFirst()) { display = name ?: it.getString(0) ?: display; size = if (it.isNull(1)) 0 else it.getLong(1) }
                    }
                }
                "https" -> { MediaHttp.url(value); if (mime == "application/octet-stream") return@withContext null }
                else -> return@withContext null
            }
            AttachmentMeta(uri.toString(), mime, display, size, type(mime))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    suspend fun references(context: Context, text: String) = MediaReferenceParser.references(text).mapNotNull { metadata(context, it) }
    private suspend fun copy(input: InputStream, file: File, limit: Long = MAX_BYTES) {
        var total = 0L
        file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer); if (read < 0) break
                total += read; require(total <= limit) { "File exceeds the media size limit." }
                output.write(buffer, 0, read)
            }
        }
        require(total > 0) { "The media file is empty." }
    }
    suspend fun import(context: Context, uri: Uri, displayName: String): AttachmentMeta = withContext(Dispatchers.IO) {
        val meta = metadata(context, uri.toString(), displayName) ?: error("Cannot read selected file.")
        require(meta.sizeBytes <= MAX_BYTES) { "File exceeds 512 MB." }
        val target = File(directory(context), "${UUID.randomUUID()}-${safeName(displayName)}")
        val temp = File(target.path + ".part")
        try {
            open(context, meta).use { copy(it, temp) }
            check(temp.renameTo(target)) { "Could not save attachment." }
            meta.copy(uri = Uri.fromFile(target).toString(), sizeBytes = target.length())
        } finally { temp.delete() }
    }
    private fun open(context: Context, meta: AttachmentMeta): InputStream {
        val uri = Uri.parse(meta.uri)
        return if (uri.scheme == "file") localFile(context, uri).inputStream()
            else context.contentResolver.openInputStream(uri) ?: error("File permission unavailable.")
    }
    suspend fun materialize(context: Context, meta: AttachmentMeta): AttachmentMeta = withContext(Dispatchers.IO) {
        if (meta.uri.startsWith(JOB_PREFIX)) {
            val job = MediaJobStore(context).get(meta.uri.removePrefix(JOB_PREFIX)) ?: error("Generation job unavailable.")
            require(job.state == "completed" && job.path != null) { job.error ?: "Media is still generating." }
            return@withContext metadata(context, job.path) ?: error("Generated file unavailable.")
        }
        if (!meta.uri.startsWith("https://")) return@withContext meta
        val digest = MessageDigest.getInstance("SHA-256").digest(meta.uri.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(context.cacheDir, "chat-media/$digest-${safeName(meta.fileName)}").apply { parentFile?.mkdirs() }
        if (!file.isFile) {
            val temp = File.createTempFile("download-", ".part", file.parentFile)
            try {
                MediaHttp.downloadResponse(meta.uri).use { response ->
                    require(response.isSuccessful) { "Media download failed (${response.code})." }
                    val body = response.body ?: error("Empty media response.")
                    require(body.contentLength() <= MAX_BYTES) { "Media exceeds 512 MB." }
                    body.byteStream().use { copy(it, temp) }
                }
                check(temp.renameTo(file)) { "Could not save media download." }
            } finally { temp.delete() }
        }
        meta.copy(uri = Uri.fromFile(file).toString(), sizeBytes = file.length())
    }
    private fun readableFile(context: Context, meta: AttachmentMeta): File? {
        if (!meta.uri.startsWith("file:")) return null
        val file = File(Uri.parse(meta.uri).path ?: return null).canonicalFile
        return if (file.path.startsWith(File(context.cacheDir, "chat-media").canonicalPath + "/")) file else localFile(context, Uri.parse(meta.uri))
    }
    suspend fun bitmap(context: Context, meta: AttachmentMeta, maxDimension: Int = 1400): Bitmap? = withContext(Dispatchers.IO) {
        val local = materialize(context, meta)
        fun stream() = readableFile(context, local)?.inputStream() ?: open(context, local)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream().use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth in 1..100_000 && options.outHeight in 1..100_000) { "Unsupported image." }
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxDimension) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        stream().use { BitmapFactory.decodeStream(it, null, options) }
    }
    suspend fun shareUri(context: Context, meta: AttachmentMeta): Uri = withContext(Dispatchers.IO) {
        val local = materialize(context, meta)
        if (local.uri.startsWith("content://")) return@withContext Uri.parse(local.uri)
        val source = readableFile(context, local) ?: error("Cannot open media.")
        if (source.path.startsWith(directory(context).canonicalPath + "/") || source.path.startsWith(File(context.cacheDir, "chat-media").canonicalPath + "/"))
            return@withContext FileProvider.getUriForFile(context, "${context.packageName}.chatmedia", source)
        val target = File(context.cacheDir, "chat-share/${UUID.randomUUID()}-${safeName(local.fileName)}").apply { parentFile?.mkdirs() }
        try { source.inputStream().use { copy(it, target) } }
        catch (error: Exception) { target.delete(); throw error }
        FileProvider.getUriForFile(context, "${context.packageName}.chatmedia", target)
    }
    suspend fun save(context: Context, meta: AttachmentMeta): Uri = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= 29) { "Use Save as to choose a folder on this Android version." }
        val local = materialize(context, meta)
        val collection = when (local.mediaType) {
            AttachmentMediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            AttachmentMediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            AttachmentMediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val folder = when (local.mediaType) {
            AttachmentMediaType.IMAGE -> "Pictures/Omni"; AttachmentMediaType.VIDEO -> "Movies/Omni"
            AttachmentMediaType.AUDIO -> "Music/Omni"; else -> "Download/Omni"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName(local.fileName)); put(MediaStore.MediaColumns.MIME_TYPE, local.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values) ?: error("Could not create the saved file.")
        try {
            export(context, local, uri)
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(context.contentResolver.update(uri, values, null, null) > 0) { "Could not finish saving media." }
            uri
        } catch (error: Exception) { context.contentResolver.delete(uri, null, null); throw error }
    }
    suspend fun export(context: Context, meta: AttachmentMeta, target: Uri) = withContext(Dispatchers.IO) {
        val local = materialize(context, meta)
        val source = readableFile(context, local)?.inputStream() ?: open(context, local)
        source.use { input -> context.contentResolver.openOutputStream(target, "w")?.use { output ->
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) { currentCoroutineContext().ensureActive(); val read = input.read(buffer); if (read < 0) break
                total += read; require(total <= MAX_BYTES); output.write(buffer, 0, read) }
        } ?: error("Could not open the destination.") }
    }
}
