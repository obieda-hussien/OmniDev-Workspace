package com.omnidev.workspace.data.ipc

import com.omnilink.sdk.AuditLogger
import com.omnilink.sdk.CallerContext
import com.omnilink.sdk.ActionRequest
import com.omnilink.sdk.ActionOutcome
import com.omnidev.workspace.core.policy.OmniAuditLog
import com.omnidev.workspace.core.policy.ConfirmationKind
import com.omnidev.workspace.core.policy.TierPolicyHolder

/**
 * Workspace's client-side audit logger for Omni-Link extensions.
 *
 * Forwards every logged extension action execution attempt and outcome to the unified,
 * user-visible forensic [OmniAuditLog].
 */
class WorkspaceAuditLogger : AuditLogger {

    override fun log(caller: CallerContext, request: ActionRequest, result: ActionOutcome) {
        val policy = TierPolicyHolder.current
        val autoApproved = policy.autoApproveConfirmations

        val outcomeStr = when (result) {
            is ActionOutcome.Success -> "SUCCESS"
            is ActionOutcome.Failure -> "FAILURE: [${result.error.code}] ${result.error.message}"
            else -> "UNKNOWN"
        }

        val preview = "[ext_${caller.callingPackage}] ${request.name} -> $outcomeStr (payload: ${request.payload})"

        OmniAuditLog.record(
            tier = policy.tier,
            autoApproved = autoApproved,
            kind = ConfirmationKind.ANDROID_INTENT,
            preview = preview
        )
    }
}
