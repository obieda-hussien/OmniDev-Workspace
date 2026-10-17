package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.domain.engine.RuntimeBackendLedger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimePrivilegeProbeTest {
    @Test fun `binder lost after a successful rish check cannot publish ready evidence`() = runBlocking {
        val backend = probePrivilegeBackend(
            refreshRish = { true }, shizukuReady = { false }, cachedRootReady = { false }
        )
        assertEquals("none", backend)
        assertFalse(SetupState(privilegeBackend = backend).androidShellReady)
    }

    @Test fun `previous rish success cannot reopen a blocked Android domain after binder loss`() = runBlocking {
        var live = true
        var probes = 0
        suspend fun snapshot() = SetupState(privilegeBackend = probePrivilegeBackend(
            refreshRish = { probes++; live }, shizukuReady = { live }, cachedRootReady = { false }
        ))
        assertTrue(snapshot().androidShellReady)
        val ledger = RuntimeBackendLedger()
        ledger.observe(ToolCall("fail", "agent_runtime", mapOf("action" to "shell_script", "script" to "pm list packages")),
            ToolExecutionResult("Binder lost", true, classification = "SHIZUKU_UNAVAILABLE", backend = "shizuku-user-service"))
        live = false
        val state = snapshot()
        ledger.observe(ToolCall("probe", "agent_runtime", mapOf("action" to "env_probe")),
            ToolExecutionResult("Fresh probe", backend = "runtime-probe", verification = state.probeEvidence()))
        assertEquals(2, probes)
        assertEquals("none", state.privilegeBackend)
        assertTrue(ledger.hasBlocks())
    }

    @Test fun `slow rish probe cannot hide an available UserService or advertise rish recovery`() = runBlocking {
        val backend = probePrivilegeBackend(
            refreshRish = { CompletableDeferred<Boolean>().await() },
            shizukuReady = { true }, cachedRootReady = { false }, rishTimeoutMs = 10L
        )
        assertEquals("shizuku-user-service", backend)
        assertFalse(SetupState(privilegeBackend = backend).probeEvidence().contains("rish=ready"))
    }

    @Test fun `failed live rish check never falls back to stale ready evidence`() = runBlocking {
        val backend = probePrivilegeBackend(
            refreshRish = { throw IllegalStateException("Transport failed") },
            shizukuReady = { false }, cachedRootReady = { true }
        )
        assertEquals("root", backend)
        assertFalse(SetupState(privilegeBackend = backend).androidShellReady)
    }

    @Test fun `cancelled health check remains cancellation instead of recovery evidence`() = runBlocking {
        try {
            probePrivilegeBackend(
                refreshRish = { throw CancellationException("Cancelled") },
                shizukuReady = { true }, cachedRootReady = { true }
            )
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) {
            // No ready state can be published by a cancelled probe.
        }
    }
}
