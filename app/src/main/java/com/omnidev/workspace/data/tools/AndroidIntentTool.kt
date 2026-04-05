package com.omnidev.workspace.data.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

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
 * - Share text/data: provide `extras` map with Intent keys (e.g., `android.intent.extra.TEXT`)
 */
object AndroidIntentTool {

    /**
     * Builds and fires an Intent safely.
     *
     * @param context Android context (typically Application context).
     * @param action Intent action string (e.g. `android.intent.action.VIEW`).
     * @param packageName Optional target package name for explicit intents.
     * @param activityClass Optional fully-qualified activity class name for explicit intents.
     * @param extraUri Optional URI string appended as the Intent's data URI.
     * @param extras Optional key-value map for Intent extras.
     * @return [IntentResult] describing what happened.
     */
    fun fire(
        context: Context,
        action: String,
        packageName: String? = null,
        activityClass: String? = null,
        extraUri: String? = null,
        extras: Map<String, String>? = null
    ): IntentResult {
        val primaryResult = runCatching {
            val intent = Intent(action).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                
                // Set explicit component if provided
                if (packageName != null && activityClass != null) {
                    component = ComponentName(packageName, activityClass)
                } else if (packageName != null) {
                    setPackage(packageName)
                }
                
                // Smart URI parsing & LLM error correction
                if (extraUri != null) {
                    if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS && !extraUri.startsWith("package:")) {
                        // Fix common LLM mistake: passing just the package name instead of 'package:com.xxx'
                        data = Uri.parse("package:$extraUri")
                    } else {
                        data = Uri.parse(extraUri)
                    }
                }

                // Append any extra payloads
                extras?.forEach { (key, value) ->
                    putExtra(key, value)
                }
            }
            context.startActivity(intent)
            IntentResult.Success("Intent fired successfully: action=$action package=${packageName ?: "implicit"}")
        }

        val primaryError = primaryResult.exceptionOrNull()
        if (primaryError == null) {
            return primaryResult.getOrThrow()
        }

        // Fallback strategy: If explicit intent failed, try a simplified VIEW intent
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
     * Builds a human-readable Markdown summary of the intent that will be shown in the
     * [com.omnidev.workspace.ui.chat.ConfirmationGate] dialog before execution.
     */
    fun preview(
        action: String,
        packageName: String?,
        activityClass: String?,
        extraUri: String?,
        extras: Map<String, String>? = null
    ): String = buildString {
        appendLine("**Action:** `$action`")
        if (packageName != null) appendLine("**Package:** `$packageName`")
        if (activityClass != null) appendLine("**Activity:** `$activityClass`")
        if (extraUri != null) appendLine("**URI:** `$extraUri`")
        if (!extras.isNullOrEmpty()) {
            appendLine("**Extras:**")
            extras.forEach { (key, value) ->
                // Truncate long values so the dialog doesn't look messy
                val safeValue = if (value.length > 100) value.take(100) + "..." else value
                appendLine("  - `$key`: `$safeValue`")
            }
        }
    }.trim()
}

/**
 * Standardized result wrapper for Intent executions.
 */
sealed class IntentResult {
    data class Success(val message: String) : IntentResult()
    data class Failure(val reason: String) : IntentResult()

    fun toDisplayString(): String = when (this) {
        is Success -> "✅ $message"
        is Failure -> "❌ $reason"
    }
}
