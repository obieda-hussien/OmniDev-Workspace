package com.omnidev.workspace.data.chatmedia

import org.junit.Assert.*
import org.junit.Test

class MediaReferenceParserTest {
    @Test fun findsPhonePathsInBothMessagesAndDeduplicates() {
        assertEquals(listOf("/sdcard/Pictures/a.png", "/storage/emulated/0/Music/song.mp3"),
            MediaReferenceParser.references("/sdcard/Pictures/a.png, again `/sdcard/Pictures/a.png` and /storage/emulated/0/Music/song.mp3"))
    }
    @Test fun preservesSpacesAndArabicInQuotedAndMarkdownPaths() {
        assertEquals(listOf("/sdcard/Pictures/my image.png", "/sdcard/Download/صورة جديدة.jpg"),
            MediaReferenceParser.references("[Image](</sdcard/Pictures/my image.png>) and `/sdcard/Download/صورة جديدة.jpg`"))
    }
    @Test fun preservesSignedUrlQueryAndIgnoresNonFileSchemes() {
        assertEquals(listOf("https://cdn.example.org/photo.webp?expires=123&sig=abc"),
            MediaReferenceParser.references("![Photo](https://cdn.example.org/photo.webp?expires=123&sig=abc) javascript:alert(1) data:image/png;base64,abc"))
    }
    @Test fun stripsSentencePunctuationWithoutDamagingFileExtension() {
        assertEquals(listOf("/sdcard/Movies/video.mp4", "/sdcard/file.pdf"),
            MediaReferenceParser.references("Saved /sdcard/Movies/video.mp4. Then /sdcard/file.pdf،"))
    }
    @Test fun boundsUntrustedMessagesAndAttachments() {
        assertEquals(10, MediaReferenceParser.references((1..50).joinToString(" ") { "/sdcard/image$it.png" }).size)
        assertTrue(MediaReferenceParser.references("x".repeat(64_001) + " /sdcard/file.png").isEmpty())
        assertFalse(MediaReferenceParser.supported("/sdcard/a\u0000.png"))
    }
    @Test fun recognizesAllPlayableTypesIncludingSignedUrls() {
        assertEquals("audio/mpeg", MediaReferenceParser.mime("/sdcard/song.MP3"))
        assertEquals("video/mp4", MediaReferenceParser.mime("https://cdn.example.org/clip.mp4?signature=abc"))
        assertEquals("image/png", MediaReferenceParser.mime("/sdcard/Pictures/image.png"))
        assertEquals("application/octet-stream", MediaReferenceParser.mime("/sdcard/archive.bin"))
    }
}
