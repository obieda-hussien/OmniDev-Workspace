package com.omnidev.workspace.data.tools

import org.junit.Assert.*
import org.junit.Test

class AndroidShellCommandCompatibilityTest {
    @Test fun legacyMediaVolumeIsRewrittenOnAndroid11AndNewer() {
        val input = "media volume --stream 3 --set 15 && dumpsys audio | grep -A 10 'Stream music'"
        val result = AndroidShellCommandCompatibility.adapt(input, 30)
        assertEquals("cmd media_session volume --stream 3 --set 15 && dumpsys audio | grep -A 10 'Stream music'", result.command)
        assertNotNull(result.note)
    }

    @Test fun legacyMediaCommandIsPreservedOnOlderAndroid() {
        val input = "media volume --stream 3 --set 15"
        assertEquals(input, AndroidShellCommandCompatibility.adapt(input, 29).command)
    }

    @Test fun alreadyModernCommandIsNotRewrittenTwice() {
        val input = "cmd media_session volume --stream 3 --set 15"
        val result = AndroidShellCommandCompatibility.adapt(input, 35)
        assertEquals(input, result.command)
        assertNull(result.note)
    }

    @Test fun unrelatedMediaCommandsAndQuotedTextAreNotRewritten() {
        val inputs = listOf(
            "media dispatch play",
            "echo 'media volume --stream 3 --set 15'",
            "dumpsys audio | grep media volume"
        )
        inputs.forEach { input ->
            assertEquals(input, AndroidShellCommandCompatibility.adapt(input, 35).command)
        }
    }

    @Test fun mediaVolumeAfterShellSeparatorIsRewritten() {
        val input = "echo before && media volume --stream 3 --get"
        assertEquals(
            "echo before && cmd media_session volume --stream 3 --get",
            AndroidShellCommandCompatibility.adapt(input, 30).command
        )
    }
}
