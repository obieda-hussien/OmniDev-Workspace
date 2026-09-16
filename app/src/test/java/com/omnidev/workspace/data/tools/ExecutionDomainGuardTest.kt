package com.omnidev.workspace.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
}
