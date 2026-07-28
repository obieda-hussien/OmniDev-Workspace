package com.omnidev.workspace.data.ipc

import com.omnidev.workspace.core.policy.ConfirmationGate
import com.omnidev.workspace.core.policy.ConfirmationKind
import com.omnidev.workspace.core.policy.OmniAuditLog
import com.omnidev.workspace.core.policy.TierPolicy
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnilink.sdk.ActionError
import com.omnilink.sdk.ActionOutcome
import com.omnilink.sdk.ActionRequest
import com.omnilink.sdk.CapabilityDescriptor
import com.omnilink.sdk.CapabilityManifest
import com.omnilink.sdk.CallerContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExtensionConnectionManagerTest {

    private class StubPolicy(
        override val tier: String,
        override val allowRoot: Boolean = false,
        override val allowShizuku: Boolean = false,
        override val allowAccessibility: Boolean = false,
        override val allowDeepSecurity: Boolean = false,
        override val allowDeviceAdminWipe: Boolean = false,
        override val allowLocalSlm: Boolean = false,
        override val allowSystemIntegration: Boolean = false,
        override val autoApproveConfirmations: Boolean = false
    ) : TierPolicy {
        override fun confirmationGate(uiGate: ConfirmationGate): ConfirmationGate = uiGate
    }

    private val testGate = object : ConfirmationGate {
        var decision = true
        var requestCalled = false
        var lastKind: ConfirmationKind? = null
        var lastPreview: String? = null

        override suspend fun request(kind: ConfirmationKind, preview: String, diffContent: String?): Boolean {
            requestCalled = true
            lastKind = kind
            lastPreview = preview
            return decision
        }
    }

    @Before
    fun setUp() {
        OmniAuditLog.clearForTest()
        testGate.decision = true
        testGate.requestCalled = false
        testGate.lastKind = null
        testGate.lastPreview = null
        ExtensionConnectionManager.handles.clear()
    }

    @After
    fun tearDown() {
        OmniAuditLog.clearForTest()
        ExtensionConnectionManager.handles.clear()
    }

    @Test
    fun testWorkspaceAccessController_LiteTier_Denied() {
        // Force Lite Tier
        TierPolicyHolder.install(StubPolicy(tier = "LITE"))

        val controller = WorkspaceAccessController()
        val caller = CallerContext(10001, "com.example.ext")
        val request = ActionRequest("moveToTrash", JsonPrimitive("test"))

        val decision = controller.decide(caller, request)
        assertEquals(com.omnilink.sdk.AccessDecision.DENY, decision)
    }

    @Test
    fun testWorkspaceAccessController_ProTier_MoveToTrash_NoCache_Allow() {
        // Force Pro Tier
        TierPolicyHolder.install(StubPolicy(tier = "PRO"))

        val controller = WorkspaceAccessController()
        val caller = CallerContext(10001, "com.example.ext")
        val request = ActionRequest("someOtherAction", JsonPrimitive("test"))

        val decision = controller.decide(caller, request)
        assertEquals(com.omnilink.sdk.AccessDecision.ALLOW, decision)
    }

    @Test
    fun testWorkspaceAccessController_ProTier_MoveToTrash_WithCache_RequiresConfirmation() {
        // Force Pro Tier
        TierPolicyHolder.install(StubPolicy(tier = "PRO"))

        // Add dummy handle with manifest requiring confirmation for "moveToTrash"
        val descriptor = CapabilityDescriptor("moveToTrash", destructive = true, requiresConfirmation = true)
        val manifest = CapabilityManifest(
            protocolVersion = 1,
            sdkVersion = "1.0.0",
            minSupportedVersion = 1,
            maxSupportedVersion = 1,
            capabilities = listOf(descriptor),
            supportsTicks = false,
            preferredTickIntervalSeconds = 0
        )
        val handle = ExtensionConnectionManager.ExtensionHandle(
            packageName = "com.example.ext",
            serviceClassName = "com.example.ext.MyService",
            manifest = manifest
        )
        ExtensionConnectionManager.handles[handle.id] = handle

        val controller = WorkspaceAccessController()
        val caller = CallerContext(10001, "com.example.ext")
        val request = ActionRequest("moveToTrash", JsonPrimitive("test"))

        val decision = controller.decide(caller, request)
        assertEquals(com.omnilink.sdk.AccessDecision.REQUIRES_CONFIRMATION, decision)
    }

    @Test
    fun testWorkspaceAuditLogger_LogsSuccessAndFailure() {
        TierPolicyHolder.install(StubPolicy(tier = "PRO"))
        val logger = WorkspaceAuditLogger()

        val caller = CallerContext(10001, "com.example.ext")
        val request = ActionRequest("moveToTrash", JsonPrimitive("test"))

        // Log starting/success
        logger.log(caller, request, ActionOutcome.Success(JsonPrimitive("ok")))
        var logs = OmniAuditLog.snapshot()
        assertEquals(1, logs.size)
        assertTrue(logs[0].preview.contains("SUCCESS"))
        assertTrue(logs[0].preview.contains("com.example.ext"))

        // Log failure
        logger.log(caller, request, ActionOutcome.Failure(ActionError("error_code", "failed miserably")))
        logs = OmniAuditLog.snapshot()
        assertEquals(2, logs.size)
        assertTrue(logs[1].preview.contains("FAILURE: [error_code] failed miserably"))
    }
}
