package com.omnidev.workspace.data.chatmedia

import org.junit.Assert.*
import org.junit.Test

class MediaHttpTest {
    @Test fun rejectsCredentialsAndNonHttpsMediaTargets() {
        listOf("http://example.org/a.mp4", "https://user:password@example.org/a.png", "https://example.org:8443/a.png", "file:///sdcard/a.png").forEach {
            assertTrue(runCatching { MediaHttp.url(it) }.isFailure)
        }
        assertEquals("example.org", MediaHttp.url("https://example.org/a.png").host)
    }
    @Test fun doesNotAutomaticallyForwardCredentialHeadersOnRedirects() {
        assertFalse(MediaHttp.client.followRedirects)
        assertFalse(MediaHttp.client.followSslRedirects)
    }
    @Test fun validatesOpaqueProviderOperationInsteadOfAcceptingEndpointEscapes() {
        assertTrue(MediaGenerationClient.validOperation("models/veo-3.1-fast-generate-preview/operations/abc_123"))
        assertTrue(MediaGenerationClient.validOperation("operations/abc-123"))
        listOf("https://evil.org/operations/1", "operations/../files/key", "operations/x?key=secret", "/operations/123").forEach {
            assertFalse(MediaGenerationClient.validOperation(it))
        }
    }
}
