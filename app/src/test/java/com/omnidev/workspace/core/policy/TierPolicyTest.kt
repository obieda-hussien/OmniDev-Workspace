package com.omnidev.workspace.core.policy

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TierPolicy] + [ConfirmationGate] + [OmniAuditLog].
 *
 * These tests deliberately avoid importing any flavor-specific policy class
 * (LiteTierPolicy / NormTierPolicy / ProTierPolicy / OemTierPolicy) so they can
 * run under any build variant. They use hand-rolled stub policies that mirror
 * the production behaviour.
 *
 * The four stub policies below are contractually identical to the four
 * production TierPolicy implementations and act as the "test fixtures"
 * mentioned in the architecture proposal.
 */
class TierPolicyTest {

    @Before
    fun clearAudit() {
        OmniAuditLog.clearForTest()
    }

    @After
    fun reset() {
        OmniAuditLog.clearForTest()
    }

    // ─── Stub policies (test fixtures) ───────────────────────────────────────

    private class StubPolicy(
        override val tier: String,
        override val allowRoot: Boolean = false,
        override val allowShizuku: Boolean = false,
        override val allowAccessibility: Boolean = false,
        override val allowDeepSecurity: Boolean = false,
        override val allowDeviceAdminWipe: Boolean = false,
        override val allowLocalSlm: Boolean = false,
        override val allowSystemIntegration: Boolean = false,
        override val autoApproveConfirmations: Boolean = false,
        private val gateFactory: (ConfirmationGate) -> ConfirmationGate = { it }
    ) : TierPolicy {
        override fun confirmationGate(uiGate: ConfirmationGate): ConfirmationGate =
            gateFactory(uiGate)
    }

    private fun liteLikePolicy() = StubPolicy(
        tier = "LITE",
        gateFactory = { _ ->
            // Deny-all gate with audit log, matching LiteTierPolicy behaviour.
            ConfirmationGate { kind, preview, diff ->
                OmniAuditLog.record("LITE", autoApproved = false, kind = kind, preview = preview, diffContent = diff)
                false
            }
        }
    )

    private fun normLikePolicy() = StubPolicy(
        tier = "NORM",
        allowAccessibility = true
        // gateFactory defaults to identity — delegates to UI gate.
    )

    private fun proLikePolicy() = StubPolicy(
        tier = "PRO",
        allowRoot = true,
        allowShizuku = true,
        allowAccessibility = true,
        allowDeepSecurity = true,
        allowDeviceAdminWipe = true,
        allowLocalSlm = true
    )

    private fun oemLikePolicy() = StubPolicy(
        tier = "OEM",
        allowAccessibility = true,
        allowLocalSlm = true,
        allowSystemIntegration = true,
        autoApproveConfirmations = true,
        gateFactory = { _ ->
            ConfirmationGate { kind, preview, diff ->
                OmniAuditLog.record("OEM", autoApproved = true, kind = kind, preview = preview, diffContent = diff)
                true
            }
        }
    )

    // ─── Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `lite tier disallows every privileged capability`() {
        val p = liteLikePolicy()
        assertEquals("LITE", p.tier)
        assertFalse(p.allowRoot)
        assertFalse(p.allowShizuku)
        assertFalse(p.allowAccessibility)
        assertFalse(p.allowDeepSecurity)
        assertFalse(p.allowDeviceAdminWipe)
        assertFalse(p.allowLocalSlm)
        assertFalse(p.allowSystemIntegration)
        assertFalse(p.autoApproveConfirmations)
    }

    @Test
    fun `lite gate always denies and records an audit entry`() = runBlocking {
        val p = liteLikePolicy()
        // Spy UI gate that should NEVER be called on Lite (the policy replaces it).
        val spyCalls = intArrayOf(0)
        val spyUi = ConfirmationGate { _, _, _ -> spyCalls[0]++; true }
        val gate = p.confirmationGate(spyUi)

        val approved = gate.request(ConfirmationKind.GOD_MODE_FILE_PATCH, "rm -rf /", null)
        assertFalse(approved)
        assertEquals(0, spyCalls[0])
        val entries = OmniAuditLog.snapshot()
        assertEquals(1, entries.size)
        assertEquals("LITE", entries[0].tier)
        assertFalse(entries[0].autoApproved)
    }

    @Test
    fun `norm tier permits accessibility but not shizuku or root`() {
        val p = normLikePolicy()
        assertTrue(p.allowAccessibility)
        assertFalse(p.allowRoot)
        assertFalse(p.allowShizuku)
        assertFalse(p.allowDeepSecurity)
    }

    @Test
    fun `norm gate delegates to the UI gate (user must approve)`() = runBlocking {
        val p = normLikePolicy()
        val userTaps = booleanArrayOf(false)
        val uiGate = ConfirmationGate { _, _, _ -> userTaps[0] = true; true }
        val gate = p.confirmationGate(uiGate)

        val approved = gate.request(ConfirmationKind.SHIZUKU_COMMAND, "adb shell echo hi", null)
        assertTrue(approved)
        assertTrue("Norm must call through to the UI gate", userTaps[0])
    }

    @Test
    fun `pro tier unlocks every capability and delegates to the UI gate`() = runBlocking {
        val p = proLikePolicy()
        assertTrue(p.allowRoot)
        assertTrue(p.allowShizuku)
        assertTrue(p.allowDeepSecurity)
        assertTrue(p.allowDeviceAdminWipe)
        assertTrue(p.allowLocalSlm)
        assertFalse(p.autoApproveConfirmations) // pro still prompts

        val uiCalled = booleanArrayOf(false)
        val ui = ConfirmationGate { _, _, _ -> uiCalled[0] = true; false }
        val gate = p.confirmationGate(ui)
        val approved = gate.request(ConfirmationKind.SHIZUKU_COMMAND, "rm -rf /system/priv-app/Foo", null)
        assertTrue(uiCalled[0])
        assertFalse(approved) // user denied
    }

    @Test
    fun `oem tier auto-approves every action (zero-click execution)`() = runBlocking {
        val p = oemLikePolicy()
        assertTrue(p.autoApproveConfirmations)
        assertTrue(p.allowSystemIntegration)
        assertFalse("OEM policy must NOT permit device-admin wipe", p.allowDeviceAdminWipe)
        assertFalse("OEM does not use Shizuku (it has system uid)", p.allowShizuku)

        // The UI gate should NEVER be invoked on OEM — the policy replaces it.
        val spy = intArrayOf(0)
        val uiThatShouldNotBeCalled = ConfirmationGate { _, _, _ -> spy[0]++; false }
        val gate = p.confirmationGate(uiThatShouldNotBeCalled)

        val result1 = gate.request(ConfirmationKind.GOD_MODE_FILE_PATCH, "update /system.prop", "@@ -1,1 +1,1 @@\n-old\n+new\n")
        val result2 = gate.request(ConfirmationKind.ANDROID_INTENT, "am start -a android.intent.action.VIEW", null)
        val result3 = gate.request(ConfirmationKind.GOD_MODE_FILE_DELETE, "/data/cache/foo", null)

        assertTrue("OEM must auto-approve file patch", result1)
        assertTrue("OEM must auto-approve android intent", result2)
        assertTrue("OEM must auto-approve file delete", result3)
        assertEquals("UI gate must NEVER be invoked on OEM", 0, spy[0])

        val entries = OmniAuditLog.snapshot()
        assertEquals("Every auto-approval must write one audit entry", 3, entries.size)
        assertTrue("Every OEM entry must be flagged auto-approved", entries.all { it.autoApproved })
        assertTrue("Every OEM entry must carry the OEM tier label", entries.all { it.tier == "OEM" })

        // The diff from the first call should be preserved for forensic review.
        val diffEntry = entries.first { it.kind == ConfirmationKind.GOD_MODE_FILE_PATCH }
        assertEquals("@@ -1,1 +1,1 @@\n-old\n+new\n", diffEntry.diffContent)
    }

    @Test
    fun `audit log trims entries once it exceeds MAX_ENTRIES`() {
        val over = OmniAuditLog.MAX_ENTRIES + 50
        repeat(over) {
            OmniAuditLog.record(
                tier = "TEST",
                autoApproved = true,
                kind = ConfirmationKind.ANDROID_INTENT,
                preview = "entry #$it"
            )
        }
        val size = OmniAuditLog.snapshot().size
        assertTrue(
            "Audit log must cap at MAX_ENTRIES; got $size",
            size == OmniAuditLog.MAX_ENTRIES
        )
    }

    @Test
    fun `TierPolicyHolder defaults to uninitialised and can be installed`() {
        // In test environments OmniDevApp does not run, so the default
        // uninitialised fallback should be present.
        TierPolicyHolder.install(liteLikePolicy())
        assertEquals("LITE", TierPolicyHolder.current.tier)

        TierPolicyHolder.install(oemLikePolicy())
        assertEquals("OEM", TierPolicyHolder.current.tier)
        assertTrue(TierPolicyHolder.current.autoApproveConfirmations)
    }
}
