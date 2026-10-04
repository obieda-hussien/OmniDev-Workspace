package com.omnidev.workspace.data.voice

import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Partial archives are untrusted; the installer hashes the entire result before extraction. */
internal object VoiceModelDownload {
    private const val MAX_DOWNLOAD = 400L * 1024 * 1024
    suspend fun fetch(url: String, archive: File, progress: (Long) -> Unit,
        connect: (String) -> HttpsURLConnection = { URL(it).openConnection() as HttpsURLConnection }) {
        var offset = archive.length()
        if (offset > MAX_DOWNLOAD) { check(archive.delete()); offset = 0 }
        val connection = connect(url)
        connection.connectTimeout = 20_000; connection.readTimeout = 20_000; connection.instanceFollowRedirects = false
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            val response = connection.responseCode
            if (response == 416 && offset > 0) {
                require(connection.getHeaderField("Content-Range") == "bytes */$offset") { "Download changed. Retry later." }
                return // Already complete; SHA-256 still decides whether it is trusted.
            }
            require(response == 200 || response == 206) { "Download unavailable (HTTP $response). Retry to resume." }
            val append = response == 206
            if (append) require(connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) { "The server returned an unexpected download range." }
            var total = if (append) offset else 0L
            progress(total)
            connection.inputStream.use { input -> FileOutputStream(archive, append).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    total += count; require(total <= MAX_DOWNLOAD) { "Model download is too large." }
                    output.write(buffer, 0, count); progress(total)
                }
            } }
        } finally { connection.disconnect() }
    }
}
