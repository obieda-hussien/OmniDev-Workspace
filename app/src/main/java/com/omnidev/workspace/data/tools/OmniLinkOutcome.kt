package com.omnidev.workspace.data.tools

import org.json.JSONObject

/** Transport delivery is not proof that the connected app completed the action. */
internal data class OmniLinkOutcome(val success: Boolean, val code: String) {
    companion object {
        fun parse(result: String): OmniLinkOutcome {
            val body = runCatching { JSONObject(result) }.getOrNull()
                ?: return OmniLinkOutcome(false, "invalid_action_outcome")
            return when (body.optString("type").substringAfterLast('.')) {
                "Success" -> OmniLinkOutcome(true, "success")
                "Failure" -> OmniLinkOutcome(
                    false,
                    body.optJSONObject("error")?.optString("code")
                        ?.takeIf { it.isNotBlank() } ?: "remote_action_failed"
                )
                "RequiresConfirmation" -> OmniLinkOutcome(false, "confirmation_required")
                else -> OmniLinkOutcome(false, "invalid_action_outcome")
            }
        }
    }
}
