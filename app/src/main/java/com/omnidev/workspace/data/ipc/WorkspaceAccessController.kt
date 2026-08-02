package com.omnidev.workspace.data.ipc

import com.omnilink.sdk.AccessController
import com.omnilink.sdk.AccessDecision
import com.omnilink.sdk.ActionRequest
import com.omnilink.sdk.CallerContext
import com.omnidev.workspace.core.policy.TierPolicyHolder

/**
 * Workspace's client-side pre-flight access controller.
 *
 * Checks if the active product flavor permits extension binding and execution (pro/oem/admin only),
 * and decides if user confirmation is required based on the extension action and active tier policy.
 */
class WorkspaceAccessController : AccessController {

    override fun decide(caller: CallerContext, request: ActionRequest): AccessDecision {
        val policy = TierPolicyHolder.current
        val tier = policy.tier

        // Extension control sits alongside Shizuku/root as pro/oem/admin-only.
        if (tier == "LITE" || tier == "NORM") {
            return AccessDecision.DENY
        }

        // Check if the requested action requires confirmation in the extension's manifest cache.
        // Background periodic ticks never require user confirmation prompts!
        val requiresConfirmation = if (request.name == "_tick") {
            false
        } else {
            ExtensionConnectionManager.isActionConfirmationRequired(
                packageName = caller.callingPackage,
                actionName = request.name
            )
        }

        return if (requiresConfirmation && !policy.autoApproveConfirmations) {
            AccessDecision.REQUIRES_CONFIRMATION
        } else {
            AccessDecision.ALLOW
        }
    }
}
