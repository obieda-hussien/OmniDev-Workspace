package com.omnidev.workspace.data.tools

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PrivilegedAccessSetupPlanTest {
    private val pkg = "com.omnidev.workspace.pro"
    private val component = "$pkg/com.omnidev.workspace.data.accessibility.OmniAccessibilityService"
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Invalid identity must not reach a shell") } catch (_: IllegalArgumentException) { }
    }
    @Test fun componentsMustBelongToOurOwnPackageAndCannotContainShellSyntax() {
        for (value in listOf("other.app/Service", "$pkg/Service;reboot", "$pkg/\$(id)", "$pkg/Service\nreboot")) {
            rejects { PrivilegedAccessSetupPlan.command("accessibility", pkg, 10, value) }
        }
        rejects { PrivilegedAccessSetupPlan.command("notification_listener", pkg, -1, component) }
        rejects { PrivilegedAccessSetupPlan.command("notification_policy", "pkg;id", 0) }
    }
    @Test fun listenerAndDndUseTheCurrentAndroidUser() {
        assertEquals("cmd notification allow_listener '$component' 10", PrivilegedAccessSetupPlan.command("notification_listener", pkg, 10, component))
        assertEquals("cmd notification allow_dnd '$pkg' 10", PrivilegedAccessSetupPlan.command("notification_policy", pkg, 10))
    }
    @Test fun accessibilityScriptPreservesOtherServicesAndIsIdempotent() {
        // Exercise the generated script against a fake settings binary, rather than its text.
        val dir = kotlin.io.path.createTempDirectory("omni-access").toFile()
        try {
            val state = File(dir, "state").apply { writeText("other.app/OtherService") }
            val settings = File(dir, "settings").apply {
                writeText("""#!/bin/sh
                    case "${'$'}5" in
                      enabled_accessibility_services)
                        if [ "${'$'}3" = get ]; then cat "${'$'}OMNI_STATE"; else printf '%s' "${'$'}6" > "${'$'}OMNI_STATE"; fi ;;
                      accessibility_enabled) exit 0 ;;
                      *) exit 1 ;;
                    esac
                """.trimIndent())
                setExecutable(true)
            }
            val command = PrivilegedAccessSetupPlan.command("accessibility", pkg, 10, component)
            repeat(2) {
                val process = ProcessBuilder("sh", "-c", command).apply {
                    environment()["PATH"] = "${dir.absolutePath}:${System.getenv("PATH")}"
                    environment()["OMNI_STATE"] = state.absolutePath
                }.start()
                assertEquals(process.errorStream.bufferedReader().readText(), 0, process.waitFor())
                assertEquals("other.app/OtherService:$component", state.readText())
            }
            state.writeText("null")
            val process = ProcessBuilder("sh", "-c", command).apply {
                environment()["PATH"] = "${dir.absolutePath}:${System.getenv("PATH")}"
                environment()["OMNI_STATE"] = state.absolutePath
            }.start()
            assertEquals(0, process.waitFor())
            assertEquals(component, state.readText())
        } finally { dir.deleteRecursively() }
    }
}
