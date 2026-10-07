package com.omnidev.workspace.data.chatmedia

import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.AttachmentMediaType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MediaToolResultTest {
    private fun audio(uri: String, name: String?) = AttachmentMeta(uri, "audio/mpeg", name ?: "song.mp3", 100, AttachmentMediaType.AUDIO)
    private val queued = """{"status":"queued","attachments":[{"uri":"omni-media-job:music-1","file_name":"Generated music"}]}"""
    @Test fun `shared handler displays worker music results even before generation completes`() = runTest {
        val message = MediaToolResult.message("media_generation", "[omni-outcome] status=PASS\n$queued", false, { emptySet() }, ::audio)!!
        assertEquals("Media generation", message.content)
        assertEquals("omni-media-job:music-1", message.attachments.single().uri)
        assertEquals(AttachmentMediaType.AUDIO, message.attachments.single().mediaType)
        assertFalse(message.content.contains("ready", true))
    }
    @Test fun `status and repeated worker output never duplicate the existing job card`() = runTest {
        var known = emptySet<String>()
        val first = MediaToolResult.message("media_generation", queued, false, { known }, ::audio)!!
        known = first.attachments.map { it.uri }.toSet()
        assertNull(MediaToolResult.message("media_generation", queued, false, { known }, ::audio))
    }
    @Test fun `deduplication checks current cards after asynchronous metadata resolution`() = runTest {
        var known = emptySet<String>()
        assertNull(MediaToolResult.message("media_generation", queued, false, { known }) { uri, name ->
            known = setOf(uri); audio(uri, name)
        })
    }
    @Test fun `failed other malformed and unavailable results cannot create media cards`() = runTest {
        var resolutions = 0
        val resolver: suspend (String, String?) -> AttachmentMeta? = { _, _ -> resolutions++; null }
        assertNull(MediaToolResult.message("media_generation", queued, true, { emptySet() }, resolver))
        assertNull(MediaToolResult.message("read_file", queued, false, { emptySet() }, resolver))
        assertNull(MediaToolResult.message("media_generation", "not JSON", false, { emptySet() }, resolver))
        assertEquals(0, resolutions)
        assertNull(MediaToolResult.message("media_generation", queued, false, { emptySet() }, resolver))
        assertEquals(1, resolutions)
    }
    @Test fun `file attachments preserve playable metadata and deduplicate within a worker result`() = runTest {
        val output = """{"status":"attached","attachments":[{"uri":"file:///song.mp3"},{"uri":"file:///song.mp3"}]}"""
        val result = MediaToolResult.message("media_generation", output, false, { emptySet() }, ::audio)!!
        assertEquals("File attached.", result.content); assertEquals(1, result.attachments.size)
        assertEquals("audio/mpeg", result.attachments.single().mimeType)
    }
}
