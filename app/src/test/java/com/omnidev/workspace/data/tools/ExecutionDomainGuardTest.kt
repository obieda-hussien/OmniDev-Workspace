package com.omnidev.workspace.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionDomainGuardTest {

    @Test
    fun `direct privileged command is rejected from Termux domain`() {
        val violation = ExecutionDomainGuard.findViolation(
            "settings put global window_animation_scale 0.3\necho done"
        )

        assertNotNull(violation)
        assertEquals("settings", violation?.commandFamily)
        assertEquals(1, violation?.lineNumber)
    }

    @Test
    fun `rish command substitution is rejected`() {
        val violation = ExecutionDomainGuard.findViolation(
            "BRAND=\$(rish -c 'getprop ro.product.brand')\necho \"\$BRAND\""
        )

        assertNotNull(violation)
        assertEquals("rish", violation?.commandFamily)
    }

    @Test
    fun `heredoc payload may contain rish without executing it`() {
        val script = """
            cat << 'EOF' > dashboard.sh
            #!/system/bin/sh
            rish -c 'dumpsys battery'
            settings get system screen_brightness
            EOF
            chmod +x dashboard.sh
        """.trimIndent()

        assertNull(ExecutionDomainGuard.findViolation(script))
    }

    @Test
    fun `commented privileged command is ignored`() {
        assertNull(
            ExecutionDomainGuard.findViolation(
                "# rish -c id\nprintf '%s\\n' 'rish -c id'"
            )
        )
    }

    @Test
    fun `normal developer commands remain in Termux domain`() {
        assertNull(
            ExecutionDomainGuard.findViolation(
                "pkg install python\npython -c 'print(42)'\ngit status"
            )
        )
    }
    @Test
    fun `content provider command is recognized as Android privileged work`() {
        val violation = ExecutionDomainGuard.findViolation(
            "content query --uri content://sms --projection body,address,date"
        )

        assertNotNull(violation)
        assertEquals("content", violation?.commandFamily)
    }

    @Test
    fun `content query limit is implemented by compatibility adapter`() {
        val prepared = ExecutionDomainGuard.preparePrivilegedCommand(
            "content query --uri content://sms --sort \"date DESC\" --limit 10"
        )

        assertEquals(10, prepared.contentQueryRowLimit)
        assertFalse(prepared.command.contains("--limit"))
        assertTrue(prepared.command.contains("--sort \"date DESC\""))
    }

    @Test
    fun `content row limiter preserves multiline rows`() {
        val prepared = ExecutionDomainGuard.preparePrivilegedCommand(
            "content query --uri content://sms --limit 2"
        )
        val output = """
            Row: 0 body=first line
            continuation of first
            Row: 1 body=second line
            continuation of second
            Row: 2 body=third line
        """.trimIndent()

        val limited = ExecutionDomainGuard.applyOutputCompatibility(output, prepared)

        assertTrue(limited.contains("Row: 0"))
        assertTrue(limited.contains("continuation of first"))
        assertTrue(limited.contains("Row: 1"))
        assertFalse(limited.contains("Row: 2"))
        assertTrue(limited.contains("limited content query"))
    }

    @Test
    fun `simple su wrapper is removed before Shizuku execution`() {
        val prepared = ExecutionDomainGuard.preparePrivilegedCommand(
            "su -c \"content query --uri content://sms --projection body\""
        )

        assertFalse(prepared.command.startsWith("su "))
        assertTrue(prepared.command.startsWith("content query"))
    }


    @Test
    fun `read only privileged classifier recognizes content query but not pm grant`() {
        assertTrue(
            ExecutionDomainGuard.isReadOnlyPrivilegedCommand(
                "content query --uri content://sms --projection body,address,date"
            )
        )
        assertFalse(
            ExecutionDomainGuard.isReadOnlyPrivilegedCommand(
                "pm grant com.omnidev.workspace android.permission.READ_SMS"
            )
        )
    }

    @Test
    fun `limit removal preserves quoted whitespace`() {
        val prepared = ExecutionDomainGuard.preparePrivilegedCommand(
            "content query --uri content://sms --where \"body LIKE '%Orange  Cash%'\" --limit 5"
        )

        assertEquals(5, prepared.contentQueryRowLimit)
        assertTrue(prepared.command.contains("Orange  Cash"))
        assertFalse(prepared.command.contains("--limit"))
    }


    @Test
    fun `su wrapper restores escaped where quotes before Shizuku routing`() {
        val prepared = ExecutionDomainGuard.preparePrivilegedCommand(
            "su -c \"content query --uri content://sms --where \\\"address LIKE '%Orange%' OR body LIKE '%Cash%'\\\"\""
        )

        assertTrue(
            prepared.command.contains(
                "--where \"address LIKE '%Orange%' OR body LIKE '%Cash%'\""
            )
        )
        assertFalse(prepared.command.contains("\\\"address"))
    }


}
