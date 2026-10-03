package com.omnidev.workspace.data.assistant

import com.omnidev.workspace.core.policy.ConfirmationGate
import com.omnidev.workspace.core.policy.TierPolicy
import com.omnidev.workspace.domain.engine.IntentClassifier.ToolDomain

/** Derive assistant UX and tool hints from the same policy used by the full agent. */
class AssistantFlavorPolicy(private val policy: TierPolicy) {
    fun confirmationGate(uiGate: ConfirmationGate): ConfirmationGate = policy.confirmationGate(uiGate)
    val tier get() = policy.tier
    val allowScreenActions get() = policy.allowAccessibility
    // Lite strips overlay/FGS permissions as well as Accessibility; no unsupported setup CTA.
    val allowBubble get() = policy.allowAccessibility
    val toolDomains: Set<ToolDomain> get() = buildSet {
        add(ToolDomain.CORE)
        add(ToolDomain.WEB_SEARCH)
        add(ToolDomain.GENERAL)
        add(ToolDomain.CODE_TERMINAL) // e.g. read_file; the tier tool filter remains authoritative.
        if (allowScreenActions) add(ToolDomain.DEVICE_CONTROL)
    }
    val preferredToolNames: Set<String> get() = if (allowScreenActions)
        setOf("semantic_ui", "autofill_assist", "ui_automation") else emptySet()
    val promptContext: String get() = buildString {
        append("Build: $tier. ")
        if (allowScreenActions) append("Live app inspection and ordinary field actions are supported with Android permission. ")
        else append("Research, memory and user-attached screens/files only; live app control and floating bubbles are unavailable. ")
        append(if (policy.allowShizuku) "Shizuku requires its runtime grant. " else "Shizuku is unavailable. ")
        append(if (policy.allowRoot) "Root requires an available authorized backend. " else "Root is unavailable. ")
        if (policy.allowSystemIntegration) append("System integration requires the corresponding device entitlement. ")
        append(if (policy.autoApproveConfirmations) "Flavor approval is automatic and audited."
            else "Proposed actions follow this flavor's approval gate.")
    }
}
