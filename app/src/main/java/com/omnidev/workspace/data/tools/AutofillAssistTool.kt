package com.omnidev.workspace.data.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.autofill.AutofillManager
import com.omnidev.workspace.data.accessibility.OmniAccessibilityService
import com.omnidev.workspace.data.input.OmniInputMethodService
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Autofill + human-takeover assistant.
 *
 * Ordinary non-secret profile fields may be filled by the agent. Passwords,
 * passkeys, OTP/recovery codes, CAPTCHA, biometric prompts and account-consent
 * challenges are a hard human boundary: the agent should call
 * request_user_handoff and stop interacting with that sensitive step.
 */
object AutofillAssistTool {

    private const val DEFAULT_BACKEND = "accessibility"

    private val PROFILE_FIELD_ALIASES = mapOf(
        "full_name" to "full_name",
        "name" to "full_name",
        "email" to "email",
        "phone" to "phone",
        "phone_number" to "phone",
        "address" to "address"
    )

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "autofill_assist",
            description = """
Autofill and secure human-handoff assistant.

Actions:
- save_profile: save NON-SECRET profile fields only (name/email/phone/address).
- get_profile: read saved non-secret profile fields.
- fill_focused: fill a focused NON-SENSITIVE field from profile|clipboard|custom.
- status: report Accessibility/IME/system Autofill availability.
- open_autofill_settings: open Android Autofill provider settings.
- request_user_handoff: notify the user and switch to Browser Viewer for a password, passkey, OTP/recovery code, CAPTCHA, biometric prompt, account consent, payment confirmation, or any other secret/high-trust browser step.

CRITICAL BROWSER RULE: never ask the user to send a password/OTP to the model and never place it in tool arguments, logs, memory, clipboard automation, or notifications. When a sensitive input is encountered, call request_user_handoff, tell the user to enter it directly in Browser Viewer/system UI, then STOP automation until the user confirms completion.
""".trimIndent(),
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "save_profile | get_profile | fill_focused | status | open_autofill_settings | request_user_handoff",
                    required = true
                ),
                ToolParameter("source", "string", "fill_focused: profile | clipboard | custom", false),
                ToolParameter("field", "string", "profile field: full_name | email | phone | address", false),
                ToolParameter("text", "string", "fill_focused custom text. Never use for secrets.", false),
                ToolParameter("backend", "string", "accessibility | ime", false),
                ToolParameter("full_name", "string", "save_profile full name", false),
                ToolParameter("email", "string", "save_profile email", false),
                ToolParameter("phone", "string", "save_profile phone", false),
                ToolParameter("address", "string", "save_profile address", false),
                ToolParameter("reason", "string", "request_user_handoff: safe reason, e.g. password/OTP/CAPTCHA; do not include the secret", false),
                ToolParameter("url", "string", "request_user_handoff: current page URL for context; do not include tokens/query secrets", false)
            )
        )
    )

    suspend fun execute(
        context: Context,
        settingsRepository: SettingsRepository,
        action: String,
        args: Map<String, String>
    ): ToolExecutionResult {
        return when (action.lowercase()) {
            "save_profile" -> saveProfile(settingsRepository, args)
            "get_profile" -> getProfile(settingsRepository)
            "fill_focused" -> fillFocused(context, settingsRepository, args)
            "status" -> status(context)
            "open_autofill_settings" -> openAutofillSettings(context)
            "request_user_handoff" -> requestUserHandoff(context, args)
            else -> ToolExecutionResult(
                "Unknown autofill_assist action '$action'. Valid actions: save_profile, get_profile, " +
                    "fill_focused, status, open_autofill_settings, request_user_handoff",
                isError = true
            )
        }
    }

    private suspend fun requestUserHandoff(context: Context, args: Map<String, String>): ToolExecutionResult {
        NotificationCaptureTool.initialize(context)
        val reason = args["reason"]?.trim().orEmpty().ifBlank { "sensitive sign-in step" }
        val sanitizedUrl = sanitizeUrlForDisplay(args["url"])
        val body = buildString {
            append("Omni reached a human-only step ($reason). Open OmniDev and complete it directly in Browser Viewer.")
            if (sanitizedUrl != null) append("\n$sanitizedUrl")
            append("\nDo not send the password, OTP, recovery code, or other secret to the agent.")
        }

        // If the app is already alive, route straight to Browser Viewer. If it is
        // backgrounded, this state is consumed when navigation becomes active;
        // the high-priority notification below remains the explicit user signal.
        com.omnidev.workspace.MainActivity.pendingBrowserHandoff.value = true

        val posted = NotificationCaptureTool.executeTool(
            "read_notifications",
            mapOf(
                "operation" to "post",
                "category" to "handoff",
                "title" to "Omni needs your input",
                "text" to body
            )
        )

        val suffix = if (posted.isError) {
            " Notification could not be posted: ${posted.output}"
        } else {
            " ${posted.output}"
        }
        return ToolExecutionResult(
            "USER_ACTION_REQUIRED: $reason. Browser Viewer takeover requested. STOP browser automation until the user confirms the sensitive step is complete.$suffix"
        )
    }

    private fun sanitizeUrlForDisplay(raw: String?): String? {
        val uri = raw?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            ?: return null
        return uri.buildUpon().clearQuery().fragment(null).build().toString()
    }

    private suspend fun saveProfile(
        settingsRepository: SettingsRepository,
        args: Map<String, String>
    ): ToolExecutionResult {
        val fullName = args["full_name"]
        val email = args["email"]
        val phone = args["phone"]
        val address = args["address"]

        if (fullName == null && email == null && phone == null && address == null) {
            return ToolExecutionResult(
                "Nothing to save. Provide at least one of: full_name, email, phone, address.",
                isError = true
            )
        }

        if (fullName != null) settingsRepository.setUserName(fullName)
        if (email != null) settingsRepository.setUserEmail(email)
        if (phone != null) settingsRepository.setUserPhone(phone)
        if (address != null) settingsRepository.setUserAddress(address)

        return ToolExecutionResult("✅ Autofill profile updated successfully.")
    }

    private suspend fun getProfile(settingsRepository: SettingsRepository): ToolExecutionResult {
        val fullName = settingsRepository.observeUserName().first().orEmpty()
        val email = settingsRepository.observeUserEmail().first().orEmpty()
        val phone = settingsRepository.observeUserPhone().first().orEmpty()
        val address = settingsRepository.observeUserAddress().first().orEmpty()

        return ToolExecutionResult(
            buildString {
                appendLine("Autofill profile:")
                appendLine("• full_name: ${if (fullName.isBlank()) "(empty)" else fullName}")
                appendLine("• email: ${if (email.isBlank()) "(empty)" else email}")
                appendLine("• phone: ${if (phone.isBlank()) "(empty)" else phone}")
                appendLine("• address: ${if (address.isBlank()) "(empty)" else address}")
            }.trimEnd()
        )
    }

    private suspend fun fillFocused(
        context: Context,
        settingsRepository: SettingsRepository,
        args: Map<String, String>
    ): ToolExecutionResult {
        val source = args["source"]?.lowercase()?.trim().orEmpty().ifBlank { "profile" }
        val backend = args["backend"]?.lowercase()?.trim().orEmpty().ifBlank { DEFAULT_BACKEND }

        val textToFill = when (source) {
            "profile" -> {
                val field = args["field"]?.lowercase()?.trim()
                    ?: return ToolExecutionResult(
                        "Missing 'field' parameter for source=profile. Use one of: full_name, email, phone, address.",
                        isError = true
                    )
                when (normalizeProfileField(field)) {
                    "full_name" -> settingsRepository.observeUserName().first()
                    "email" -> settingsRepository.observeUserEmail().first()
                    "phone" -> settingsRepository.observeUserPhone().first()
                    "address" -> settingsRepository.observeUserAddress().first()
                    else -> return ToolExecutionResult(
                        "Unknown profile field: '$field'. Use one of: full_name, email, phone, address.",
                        isError = true
                    )
                }
            }
            "clipboard" -> getClipboardText(context)
            "custom" -> args["text"]
            else -> return ToolExecutionResult(
                "Unknown source '$source'. Use: profile, clipboard, custom.",
                isError = true
            )
        }?.trim()

        if (textToFill.isNullOrBlank()) {
            return ToolExecutionResult(
                "No text available to fill. Ensure the selected source has valid data.",
                isError = true
            )
        }

        return executeFillBackend(backend, textToFill, source)
    }

    private fun executeFillBackend(backend: String, text: String, source: String): ToolExecutionResult {
        return when (backend) {
            "accessibility" -> {
                val service = OmniAccessibilityService.instance
                    ?: return ToolExecutionResult(
                        "Accessibility backend unavailable. Ensure OmniDev Accessibility Service is running.",
                        isError = true
                    )
                if (service.typeIntoFocusedNode(text)) {
                    ToolExecutionResult("✅ Successfully filled focused field via Accessibility from '$source' (${text.length} chars).")
                } else {
                    ToolExecutionResult(
                        "Failed to fill focused field via Accessibility. Ensure an editable field is focused.",
                        isError = true
                    )
                }
            }
            "ime" -> {
                if (OmniInputMethodService.commitText(text)) {
                    ToolExecutionResult("✅ Successfully filled focused field via IME from '$source' (${text.length} chars).")
                } else {
                    ToolExecutionResult(
                        "IME backend unavailable. Switch current keyboard to OmniDev IME.",
                        isError = true
                    )
                }
            }
            else -> ToolExecutionResult(
                "Unknown backend '$backend'. Use: accessibility or ime.",
                isError = true
            )
        }
    }

    private fun status(context: Context): ToolExecutionResult {
        val hasAccessibility = OmniAccessibilityService.instance != null
        val imeActive = OmniInputMethodService.isActive.value
        val systemAutofillEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(AutofillManager::class.java)
            manager?.hasEnabledAutofillServices() == true
        } else false

        return ToolExecutionResult(
            buildString {
                appendLine("Autofill Assist Status:")
                appendLine("• Accessibility backend: ${if (hasAccessibility) "✅ ready" else "❌ not connected"}")
                appendLine("• IME backend: ${if (imeActive) "✅ active" else "❌ inactive"}")
                appendLine("• Profile source: ✅ ready (non-secret DataStore fields)")
                appendLine("• System/Google provider: ${if (systemAutofillEnabled) "✅ enabled" else "⚠️ not enabled"}")
                appendLine("• Sensitive credential policy: 👤 HUMAN TAKEOVER REQUIRED")
                appendLine("  (Use request_user_handoff for passwords/OTP/passkeys/CAPTCHA.)")
            }.trimEnd()
        )
    }

    private suspend fun openAutofillSettings(context: Context): ToolExecutionResult =
        withContext(Dispatchers.Main) {
            val result = AndroidIntentTool.fire(
                context = context,
                action = "android.settings.AUTOFILL_SETTINGS"
            )
            ToolExecutionResult(result.toDisplayString(), isError = result is IntentResult.Failure)
        }

    private fun normalizeProfileField(field: String): String? = PROFILE_FIELD_ALIASES[field]

    private suspend fun getClipboardText(context: Context): String? = withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData = clipboard.primaryClip ?: return@withContext null
        if (clip.itemCount <= 0) return@withContext null
        clip.getItemAt(0).coerceToText(context)?.toString()
    }
}
