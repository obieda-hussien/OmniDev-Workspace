package com.omnidev.system

import org.junit.Assert.*
import org.junit.Test

class SystemActionPolicyTest {
    @Test fun rejectsGenericCommandsAndUnexpectedArguments() {
        assertNotNull(SystemActionPolicy.validate("shell", mapOf("command" to "id")))
        assertNotNull(SystemActionPolicy.validate("status", mapOf("command" to "id")))
        assertNotNull(SystemActionPolicy.validate("settings_write", mapOf("key" to "screen_brightness; reboot", "value" to 100)))
    }
    @Test fun rejectsCredentialAndGlobalSettingsAccess() {
        assertNotNull(SystemActionPolicy.validate("settings_read", mapOf("key" to "lock_screen_owner_info")))
        assertNotNull(SystemActionPolicy.validate("settings_write", mapOf("key" to "adb_enabled", "value" to 1)))
    }
    @Test fun checksTypesAndSafeRanges() {
        assertNotNull(SystemActionPolicy.validate("settings_write", mapOf("key" to "screen_brightness", "value" to "255")))
        assertNotNull(SystemActionPolicy.validate("settings_write", mapOf("key" to "screen_brightness", "value" to 256)))
        assertNotNull(SystemActionPolicy.validate("volume_set", mapOf("stream" to 0, "value" to 1)))
        assertNotNull(SystemActionPolicy.validate("wifi_set", mapOf("enabled" to "true")))
    }
    @Test fun acceptsOnlyTheExplicitContract() {
        assertNull(SystemActionPolicy.validate("status", emptyMap()))
        assertNull(SystemActionPolicy.validate("settings_write", mapOf("key" to "screen_brightness", "value" to 120)))
        assertNull(SystemActionPolicy.validate("volume_read", mapOf("stream" to 3)))
        assertTrue(SystemActionPolicy.mutation("wifi_set"))
        assertFalse(SystemActionPolicy.mutation("settings_read"))
    }
}
