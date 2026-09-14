package com.omnidev.workspace.data.network

import org.junit.Assert.*
import org.junit.Test

class ProviderEndpointTest {
    @Test fun normalizesCompletionUrl() {
        assertEquals("https://example.com/v1", ProviderEndpoint.normalize(" https://example.com/v1/chat/completions/ "))
    }
    @Test fun permitsExplicitLocalServer() {
        assertEquals("http://127.0.0.1:8080/v1", ProviderEndpoint.normalize("http://127.0.0.1:8080/v1"))
    }
    @Test fun rejectsAmbiguousOrCredentialBearingUrls() {
        listOf("file:///tmp/model", "example.com", "https://key@example.com", "https://example.com?q=key", "https://example.com/#v1").forEach {
            assertTrue(runCatching { ProviderEndpoint.normalize(it) }.isFailure)
        }
    }
}
