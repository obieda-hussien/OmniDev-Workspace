package com.omnidev.workspace.data.voice

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceModelDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Connection(private val status: Int, private val body: String, private val range: String? = null) : HttpsURLConnection(URL("https://example.org/model.zip")) {
        var disconnected = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getHeaderField(name: String) = if (name == "Content-Range") range else null
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    @Test fun resumesFromRetainedBytesWithRangeRequest() = runBlocking {
        val archive = temporary.newFile().apply { writeText("first") }
        val connection = Connection(206, "last", "bytes 5-8/9")
        VoiceModelDownload.fetch("unused", archive, {}, { connection })
        assertEquals("bytes=5-", connection.getRequestProperty("Range"))
        assertEquals("firstlast", archive.readText()); assertTrue(connection.disconnected)
    }
    @Test fun rangeIgnoredRestartsWithoutDuplicatingPartialBytes() = runBlocking {
        val archive = temporary.newFile().apply { writeText("stale") }
        VoiceModelDownload.fetch("unused", archive, {}, { Connection(200, "complete") })
        assertEquals("complete", archive.readText())
    }
    @Test fun wrongRangeCannotCorruptTheRetainedArchive() = runBlocking {
        val archive = temporary.newFile().apply { writeText("first") }
        try { VoiceModelDownload.fetch("unused", archive, {}, { Connection(206, "bad", "bytes 0-2/3") }); fail() }
        catch (_: IllegalArgumentException) {}
        assertEquals("first", archive.readText())
    }
    @Test fun completeRetainedArchiveCanProceedToIntegrityVerification() = runBlocking {
        val archive = temporary.newFile().apply { writeText("complete") }
        VoiceModelDownload.fetch("unused", archive, {}, { Connection(416, "", "bytes */8") })
        assertEquals("complete", archive.readText())
    }
    @Test fun interruptedDownloadRetainsBytesForNextAttempt() = runBlocking {
        val archive = temporary.newFile()
        try { VoiceModelDownload.fetch("unused", archive, { if (it > 0) throw IOException("network lost") }, { Connection(200, "first") }); fail() }
        catch (_: IOException) {}
        assertEquals("first", archive.readText())
        VoiceModelDownload.fetch("unused", archive, {}, { Connection(206, "last", "bytes 5-8/9") })
        assertEquals("firstlast", archive.readText())
    }
    @Test fun cancelledDownloadRetainsBytesAndReleasesConnection() = runBlocking {
        val archive = temporary.newFile(); val connection = Connection(200, "partial")
        try { VoiceModelDownload.fetch("unused", archive, { if (it > 0) throw CancellationException("paused") }, { connection }); fail() }
        catch (_: CancellationException) {}
        assertEquals("partial", archive.readText()); assertTrue(connection.disconnected)
    }
}
