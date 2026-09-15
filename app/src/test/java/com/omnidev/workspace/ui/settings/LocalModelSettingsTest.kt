package com.omnidev.workspace.ui.settings

import org.junit.Assert.*
import org.junit.Test

class LocalModelSettingsTest {
    @Test fun automaticSettingsAreAllowed() {
        assertNull(validateLocalSettings("", "", 8))
    }
    @Test fun invalidMemoryAndThreadRequestsAreRejected() {
        for (value in listOf("0", "-1", "32769", "999999999999", "abc")) {
            assertNotNull(validateLocalSettings(value, "", 8))
        }
        assertNotNull(validateLocalSettings("2048", "5", 4))
        assertNotNull(validateLocalSettings("2048", "0", 8))
        assertNull(validateLocalSettings("2048", "2", 4))
    }
}
