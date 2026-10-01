package com.omnidev.workspace.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniLinkOutcomeTest {
    @Test
    fun `remote rejection from launcher log is an error`() {
        val result = OmniLinkOutcome.parse(
            """{"type":"com.omnilink.sdk.ActionOutcome.Failure","error":{"code":"preference_not_allowed","message":"preference_not_allowed"}}"""
        )
        assertFalse(result.success)
        assertEquals("preference_not_allowed", result.code)
    }

    @Test
    fun `explicit success is successful`() {
        assertTrue(OmniLinkOutcome.parse(
            """{"type":"com.omnilink.sdk.ActionOutcome.Success","data":{"remoteControl":true}}"""
        ).success)
    }

    @Test
    fun `confirmation is not execution success`() {
        val result = OmniLinkOutcome.parse(
            """{"type":"com.omnilink.sdk.ActionOutcome.RequiresConfirmation","message":"Approve"}"""
        )
        assertFalse(result.success)
        assertEquals("confirmation_required", result.code)
    }

    @Test
    fun `missing malformed and transport error outcomes cannot become success`() {
        listOf("", "not json", "{}", "[]", "{\"error\":\"Disconnected\"}", "{\"ok\":true}")
            .forEach { assertFalse(OmniLinkOutcome.parse(it).success) }
    }
}
