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
 * Autofill assistant tool:
 * - Stores user profile fields in app-local DataStore (via [SettingsRepository])
 * - Fills the currently focused field using Accessibility or IME
 * - Can open Android Autofill settings so the user can choose Google/system provider
 */
object AutofillAssistTool {
    private const val DEFAULT_BACKEND = "accessibility"

    // Includes self-mappings for canonical keys so callers can pass either
    // canonical names ("full_name") or aliases ("name") through one lookup.
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
            description = "Assist with autofill from multiple sources. " +
                "Actions: " +
                "'save_profile' (save user profile fields in app database/DataStore), " +
                "'get_profile' (read saved profile fields), " +
                "'fill_focused' (fill currently focused field from source profile|clipboard|custom), " +
                "'status' (show available fill backends), " +
                "'open_autofill_settings' (open Android autofill provider settings e.g. Google Autofill).",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "One of: save_profile, get_profile, fill_focused, status, open_autofill_settings",
                    required = true
                ),
                ToolParameter(
                    name = "source",
                    type = "string",
                    description = "For fill_focused: profile | clipboard | custom (default: profile)",
                    required = false
                ),
                ToolParameter(
                    name = "field",
                    type = "string",
                    description = "For source=profile: full_name (or name) | email | phone (or phone_number) | address",
                    required = false
                ),
                ToolParameter(
                    name = "text",
                    type = "string",
                    description = "For source=custom: text to fill",
                    required = false
                ),
                ToolParameter(
                    name = "backend",
                    type = "string",
                    description = "Fill backend: accessibility | ime (default: accessibility, chosen for reliability even when OmniDev IME is inactive)",
                    required = false
                ),
                ToolParameter(
                    name = "full_name",
                    type = "string",
                    description = "Profile full name for save_profile",
                    required = false
                ),
                ToolParameter(
                    name = "email",
                    type = "string",
                    description = "Profile email for save_profile",
                    required = false
                ),
                ToolParameter(
                    name = "phone",
                    type = "string",
                    description = "Profile phone for save_profile",
                    required = false
                ),
                ToolParameter(
                    name = "address",
                    type = "string",
                    description = "Profile address for save_profile",
                    required = false
                )
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
            else -> ToolExecutionResult(
                    "Unknown autofill_assist action '$action'. " +
                    "Valid actions: save_profile, get_profile, fill_focused, status, open_autofill_settings",
                isError = true
            )
        }
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

        return ToolExecutionResult("✅ Autofill profile updated.")
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

        val text = when (source) {
            "profile" -> {
                val field = args["field"]?.lowercase()?.trim()
                    ?: return ToolExecutionResult(
                        "Missing 'field' for source=profile. Use one of: full_name (or name), email, phone (or phone_number), address.",
                        isError = true
                    )
                when (normalizeProfileField(field)) {
                    "full_name" -> settingsRepository.observeUserName().first()
                    "email" -> settingsRepository.observeUserEmail().first()
                    "phone" -> settingsRepository.observeUserPhone().first()
                    "address" -> settingsRepository.observeUserAddress().first()
                    else -> return ToolExecutionResult(
                        "Unknown profile field. Use one of: full_name (or name), email, phone (or phone_number), address.",
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

        if (text.isNullOrBlank()) {
            return ToolExecutionResult(
                "No text available to fill. Save profile values first or provide source=custom with text.",
                isError = true
            )
        }

        return when (backend) {
            "accessibility" -> {
                val service = OmniAccessibilityService.instance
                    ?: return ToolExecutionResult(
                        "Accessibility backend unavailable. Enable OmniDev Accessibility Service first.",
                        isError = true
                    )
                if (service.typeIntoFocusedNode(text)) {
                    ToolExecutionResult("✅ Filled focused field via Accessibility from '$source' (${text.length} chars).")
                } else {
                    ToolExecutionResult(
                        "Failed to fill focused field via Accessibility. Focus an editable field and try again.",
                        isError = true
                    )
                }
            }
            "ime" -> {
                if (OmniInputMethodService.commitText(text)) {
                    ToolExecutionResult("✅ Filled focused field via IME from '$source' (${text.length} chars).")
                } else {
                    ToolExecutionResult(
                        "IME backend unavailable. Switch keyboard to OmniDev IME first.",
                        isError = true
                    )
                }
            }
            else -> ToolExecutionResult(
                "Unknown backend '$backend'. Use: accessibility, ime.",
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
        } else {
            false
        }
        return ToolExecutionResult(
            buildString {
                appendLine("Autofill Assist status:")
                appendLine("• Accessibility backend: ${if (hasAccessibility) "✅ ready" else "❌ not connected"}")
                appendLine("• IME backend: ${if (imeActive) "✅ active" else "❌ inactive"}")
                appendLine("• App profile source: ✅ available (DataStore)")
                appendLine("• System/Google provider: ${if (systemAutofillEnabled) "✅ enabled" else "⚠️ not enabled"}")
                appendLine("  Use action=open_autofill_settings to configure provider")
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
