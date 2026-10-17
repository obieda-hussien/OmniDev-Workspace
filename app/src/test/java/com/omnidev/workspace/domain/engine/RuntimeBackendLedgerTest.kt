package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.SetupState
import com.omnidev.workspace.data.tools.ToolExecutionResult
import org.junit.Assert.*
import org.junit.Test

class RuntimeBackendLedgerTest {
    private fun shell(script: String, name: String = "agent_runtime") = ToolCall(
        id = "test", name = name, arguments = if (name == "agent_runtime")
            mapOf("action" to "shell_script", "script" to script) else mapOf("command" to script)
    )
    private fun failure(classification: String = "SHIZUKU_UNAVAILABLE", backend: String = "shizuku-user-service") =
        ToolExecutionResult("backend unavailable", true, classification = classification, backend = backend,
            persistentFailure = true)
    private fun probe(ledger: RuntimeBackendLedger, state: SetupState) = ledger.observe(
        ToolCall(id = "probe", name = "agent_runtime", arguments = mapOf("action" to "env_probe")),
        ToolExecutionResult("probe", backend = "runtime-probe", verification = state.probeEvidence())
    )

    @Test fun `reported log stops backend execution despite unrelated Termux success`() {
        val ledger = RuntimeBackendLedger()
        var executions = 0
        fun dispatch(call: ToolCall, result: ToolExecutionResult): ToolExecutionResult {
            ledger.before(call)?.let { return it }
            executions++
            ledger.observe(call, result)
            return result
        }
        dispatch(shell("pm list packages -f | grep -i omni"), failure())
        assertFalse(ledger.reportOnly) // Still permits a useful independent pivot.
        dispatch(shell("command -v pm dumpsys am"), ToolExecutionResult("paths", backend = "termux"))
        listOf("dumpsys package com.omni.dev.workspace", "export PATH=\$PATH:/system/bin && pm list packages",
            "pm path com.omni || pm list packages", "dumpsys package").forEach {
            val result = dispatch(shell(it), failure())
            assertEquals("RUNTIME_BACKEND_BLOCKED", result.classification)
            assertTrue(result.output.contains("NOT executed"))
        }
        assertEquals(2, executions) // One Android failure and one successful developer command.
        assertTrue(ledger.reportOnly)
        assertTrue(ledger.reportSummary().contains("not completed or verified"))
    }

    @Test fun `terminal aliases share Android capability circuit`() {
        val ledger = RuntimeBackendLedger()
        ledger.observe(shell("pm list packages"), failure())
        listOf("run_terminal", "advanced_terminal", "direct_terminal", "shizuku_command", "privileged_tool").forEach {
            assertNotNull(ledger.before(shell("pm path com.omnidev.workspace.admin", it)))
        }
    }

    @Test fun `rish cannot repair missing shared Shizuku binder`() {
        val ledger = RuntimeBackendLedger()
        ledger.observe(shell("pm list packages"), failure())
        assertNotNull(ledger.before(shell("rish -c 'pm list packages'")))
    }

    @Test fun `permission failure never opens same shell under another alias`() {
        val ledger = RuntimeBackendLedger()
        ledger.observe(shell("pm list packages"), failure("SHIZUKU_PERMISSION_REQUIRED"))
        assertNotNull(ledger.before(shell("settings get secure enabled_accessibility_services", "shizuku_command")))
        assertNull(ledger.before(shell("python -c 'print(1)'")))
    }

    @Test fun `healthy Termux probe does not heal Android and root cannot heal automatic route`() {
        val ledger = RuntimeBackendLedger()
        ledger.observe(shell("pm list packages"), failure())
        probe(ledger, SetupState(termuxPrefix = "/termux", privilegeBackend = "root"))
        assertTrue(ledger.hasBlocks())
        assertNotNull(ledger.before(shell("pm list packages")))
        assertNull(ledger.before(shell("su -c 'id'")))
    }

    @Test fun `authoritative fresh environment probe reopens only recovered capability`() {
        val ledger = RuntimeBackendLedger()
        ledger.observe(shell("pm list packages"), failure())
        ledger.before(shell("pm path com.omni"))
        probe(ledger, SetupState(privilegeBackend = "shizuku-user-service"))
        assertFalse(ledger.hasBlocks())
        assertFalse(ledger.reportOnly)
        assertNull(ledger.before(shell("pm list packages")))
    }

    @Test fun `stdout mentioning healthy Shizuku cannot reopen capability`() {
        val ledger = RuntimeBackendLedger()
        ledger.observe(shell("pm list packages"), failure())
        ledger.observe(shell("cat old-probe.log"), ToolExecutionResult("android-shell=ready Shizuku UserService", backend = "termux"))
        assertTrue(ledger.hasBlocks())
    }

    @Test fun `ordinary command failure is not a backend outage`() {
        val ledger = RuntimeBackendLedger()
        ledger.observe(shell("pm unsupported"), ToolExecutionResult("invalid argument", true, classification = "NON_ZERO_EXIT", backend = "shizuku-user-service"))
        assertFalse(ledger.hasBlocks())
        assertNull(ledger.before(shell("pm list packages")))
    }

    @Test fun `Termux outage leaves Android route and package APIs available`() {
        val ledger = RuntimeBackendLedger()
        ledger.observe(shell("python app.py"), failure("TERMUX_RUN_COMMAND_UNAVAILABLE", "termux"))
        assertNotNull(ledger.before(shell("git status")))
        assertNull(ledger.before(shell("pm list packages")))
        assertNull(ledger.before(ToolCall(id = "apps", name = "device_admin", arguments = mapOf("action" to "list_apps"))))
        assertNull(ledger.before(ToolCall(id = "env", name = "agent_runtime", arguments = mapOf("action" to "env_probe"))))
    }

    @Test fun `new runs have no inherited infrastructure block`() {
        val old = RuntimeBackendLedger()
        old.observe(shell("pm list packages"), failure())
        assertNull(RuntimeBackendLedger().before(shell("pm list packages")))
    }

    @Test fun `rish loader failure does not poison healthy UserService route`() {
        val ledger = RuntimeBackendLedger()
        ledger.observe(shell("rish -c 'id'"), failure("RISH_NATIVE_LOADER_FAILURE", "rish"))
        assertNotNull(ledger.before(shell("rish -c 'pm list packages'")))
        assertNull(ledger.before(shell("pm list packages")))
    }

    @Test fun `probe metadata never advertises root as automatic Android shell`() {
        assertFalse(SetupState(privilegeBackend = "root").androidShellReady)
        assertTrue(SetupState(privilegeBackend = "shizuku-user-service+rish").androidShellReady)
        assertTrue(SetupState(termuxPrefix = "/termux").probeEvidence().contains("android-shell=unavailable;termux=ready"))
    }
}
