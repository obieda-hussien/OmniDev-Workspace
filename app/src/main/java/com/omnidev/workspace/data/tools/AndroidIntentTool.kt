package com.omnidev.workspace.data.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Launches Android Intents on behalf of the agent.
 *
 * **Safety contract**: Just like [ShizukuCommandTool], every call from the agent pipeline
 * goes through [com.omnidev.workspace.ui.chat.ConfirmationGate] — the user sees a full
 * preview of the intent before it fires. No Intent is dispatched without explicit approval.
 *
 * Use cases:
 * - Open app settings: `action = android.settings.APPLICATION_DETAILS_SETTINGS`
 * - Launch another app: provide `packageName` + `activityClass`
 * - Open a URL/file: provide `action = android.intent.action.VIEW` + `extraUri`
 */
object AndroidIntentTool {

    /**
     * Builds and fires an Intent.
     *
     * @param context Android context (typically Application context).
     * @param action Intent action string (e.g. `Intent.ACTION_VIEW`).
     * @param packageName Optional target package name for explicit intents.
     * @param activityClass Optional fully-qualified activity class name for explicit intents.
     * @param extraUri Optional URI string appended as the Intent's data URI.
     * @return [IntentResult] describing what happened.
     */
    fun fire(
        context: Context,
        action: String,
        packageName: String? = null,
        activityClass: String? = null,
        extraUri: String? = null
    ): IntentResult {
        val primaryResult = runCatching {
            val intent = Intent(action).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (packageName != null && activityClass != null) {
                    component = ComponentName(packageName, activityClass)
                } else if (packageName != null) {
                    setPackage(packageName)
                }
                if (extraUri != null) {
                    data = Uri.parse(extraUri)
                }
            }
            context.startActivity(intent)
            IntentResult.Success("Intent fired: action=$action package=$packageName")
        }

        val primaryError = primaryResult.exceptionOrNull()
        if (primaryError == null) {
            return primaryResult.getOrThrow()
        }

        if (action == Intent.ACTION_VIEW && !extraUri.isNullOrBlank()) {
            return runCatching {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(extraUri)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
                IntentResult.Success("Intent fallback fired: action=${Intent.ACTION_VIEW} uri=$extraUri")
            }.getOrElse { fallbackError ->
                IntentResult.Failure(
                    "Failed to fire intent (primary: ${primaryError.message}, fallback: ${fallbackError.message})"
                )
            }
        }

        return IntentResult.Failure("Failed to fire intent: ${primaryError.message}")
    }

    /**
     * Builds a human-readable summary of the intent that will be shown in the
     * [com.omnidev.workspace.ui.chat.ConfirmationGate] dialog before execution.
     */
    fun preview(
        action: String,
        packageName: String?,
        activityClass: String?,
        extraUri: String?
    ): String = buildString {
        appendLine("**Action:** `$action`")
        if (packageName != null) appendLine("**Package:** `$packageName`")
        if (activityClass != null) appendLine("**Activity:** `$activityClass`")
        if (extraUri != null) appendLine("**URI:** `$extraUri`")
    }.trim()
}

sealed class IntentResult {
    data class Success(val message: String) : IntentResult()
    data class Failure(val reason: String) : IntentResult()

    fun toDisplayString(): String = when (this) {
        is Success -> "✅ $message"
        is Failure -> "❌ $reason"
    }
}
