package com.omnidev.workspace.data.chatmedia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ProfilePhotoSlot(val label: String, val hint: String) {
    FACE("Face photo", "A clear front-facing photo in good lighting."),
    BODY("Full-body photo", "Keep your head and feet inside the frame.")
}
data class ProfileReferences(val face: String? = null, val body: String? = null, val allowed: Boolean = false) {
    fun photo(slot: ProfilePhotoSlot) = if (slot == ProfilePhotoSlot.FACE) face else body
    fun files() = listOfNotNull(face, body)
}

/** Private normalized images and consent never enter backup, chat attachments or ordinary prompts. */
class ProfileReferenceStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.noBackupFilesDir, "profile-references").apply { mkdirs() }
    private val index = AtomicFile(File(directory, "index.json"))
    fun get(): ProfileReferences = synchronized(gate) {
        runCatching {
            val bytes = index.openRead().use { it.readBytesBounded(4096) }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            fun photo(key: String) = json.optString(key).takeIf { ProfileReferencePolicy.validFile(it) && File(directory, it).isFile }
            ProfileReferences(photo("face"), photo("body"), json.optBoolean("allowed", false))
        }.getOrDefault(ProfileReferences())
    }
    private fun save(value: ProfileReferences) {
        val bytes = JSONObject().put("face", value.face).put("body", value.body).put("allowed", value.allowed).toString().toByteArray()
        val stream = index.startWrite()
        try { stream.write(bytes); index.finishWrite(stream) }
        catch (failure: Throwable) { index.failWrite(stream); throw failure }
    }
    suspend fun allow(value: Boolean) = withContext(Dispatchers.IO) {
        synchronized(gate) {
            val current = get()
            require(!value || current.files().isNotEmpty()) { "Add a reference photo first." }
            save(current.copy(allowed = value))
        }
    }
    suspend fun remove(slot: ProfilePhotoSlot) = withContext(Dispatchers.IO) {
        synchronized(gate) {
            val previous = get()
            val next = if (slot == ProfilePhotoSlot.FACE) previous.copy(face = null) else previous.copy(body = null)
            save(next.copy(allowed = next.allowed && next.files().isNotEmpty()))
            previous.photo(slot)?.let { File(directory, it).delete() }
        }
    }
    suspend fun import(slot: ProfilePhotoSlot, uri: Uri) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Choose a photo with the system picker." }
        val input = File.createTempFile("import-", ".part", directory)
        val output = File(directory, "${slot.name.lowercase()}-${UUID.randomUUID()}.jpg")
        var published = false
        try {
            app.contentResolver.openInputStream(uri)?.use { source -> input.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024); var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer); if (read < 0) break
                    total += read; require(total <= 12 * 1024 * 1024) { "Choose a photo under 12 MB." }
                    target.write(buffer, 0, read)
                }
                require(total > 0) { "The selected photo is empty." }
            } } ?: error("Could not read this photo. Choose it again.")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(input.path, options)
            require(options.outWidth in 1..30_000 && options.outHeight in 1..30_000) { "Choose a supported still image." }
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeFile(input.path, options) ?: error("Could not decode this photo.")
            try {
                val orientation = runCatching { ExifInterface(input.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
                val matrix = Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                        5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                        7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
                    }
                }
                val normalized = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                try { output.outputStream().use { check(normalized.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
                finally { if (normalized !== bitmap) normalized.recycle() }
            } finally { bitmap.recycle() }
            require(output.length() in 1..4L * 1024 * 1024) { "This photo is too large after processing." }
            currentCoroutineContext().ensureActive()
            synchronized(gate) {
                val previous = get()
                save(if (slot == ProfilePhotoSlot.FACE) previous.copy(face = output.name) else previous.copy(body = output.name))
                published = true
                previous.photo(slot)?.let { File(directory, it).delete() }
            }
        } finally { input.delete(); if (!published) output.delete() }
    }
    suspend fun preview(slot: ProfilePhotoSlot): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(gate) {
            get().photo(slot)?.let { BitmapFactory.decodeFile(File(directory, it).path,
                BitmapFactory.Options().apply { inSampleSize = 4 }) }
        }
    }
    /** Revalidate current selection and consent immediately before building the provider request. */
    internal fun read(selection: List<String>): List<ByteArray> = synchronized(gate) {
        val current = get()
        ProfileReferencePolicy.validateSelection(selection, current.allowed, current.files().toSet())
        selection.map { File(directory, it).inputStream().use { stream -> stream.readBytesBounded(4 * 1024 * 1024) } }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer); if (read < 0) break
            require(output.size() + read <= limit) { "Stored reference exceeded its size limit." }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
    companion object { private val gate = Any() }
}
