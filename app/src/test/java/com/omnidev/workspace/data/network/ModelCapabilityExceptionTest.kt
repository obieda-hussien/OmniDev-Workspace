package com.omnidev.workspace.data.network

import org.junit.Assert.*
import org.junit.Test

class ModelCapabilityExceptionTest {
    @Test fun geminiError400IsNotATemporaryNetworkFailure() {
        val body = """{"error":{"code":400,"message":"Function calling is not enabled for models/antigravity-preview-05-2026","status":"INVALID_ARGUMENT"}}"""
        assertTrue(ModelCapabilityException.fromHttp(400, body))
        assertTrue(AutoRoutingCompletion.recoverable(ModelCapabilityException()))
        assertFalse(ModelCapabilityException.fromHttp(500, body))
        assertFalse(ModelCapabilityException.fromHttp(400, """{"message":"invalid temperature"}"""))
    }
}
