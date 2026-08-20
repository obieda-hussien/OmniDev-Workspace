package com.omnidev.workspace.data.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.omnidev.workspace.data.debug.DebugLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * OmniDeviceAdminReceiver — System awareness note System awareness note System awareness note
 *
 * System awareness note System awareness note: System awareness note System awareness note System awareness note System awareness note
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **System awareness note System awareness note System awareness note (Dynamic Threat Scoring)**:
 *    System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
 *    System awareness note System awareness note System awareness note System awareness note → System awareness note System awareness note System awareness note System awareness note.
 *
 * 2. **System awareness note System awareness note System awareness note (Audit Log)**:
 *    System awareness note System awareness note System awareness note (System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note)
 *    System awareness note System awareness note System awareness note System awareness note — System awareness note System awareness note System awareness note System awareness note.
 *
 * 3. **System awareness note System awareness note System awareness note (Auto-Response Policies)**:
 *    - 3 System awareness note System awareness note → System awareness note System awareness note
 *    - 10 System awareness note System awareness note → System awareness note System awareness note System awareness note
 *    - 15 System awareness note System awareness note → System awareness note System awareness note System awareness note System awareness note
 *
 * 4. **System awareness note System awareness note System awareness note (Device Health Monitoring)**:
 *    System awareness note: System awareness note Admin System awareness note/System awareness note Device OwnerSystem awareness note System awareness note System awareness note/System awareness note.
 *
 * 5. **System awareness note System awareness note (Extended Actions)**:
 *    - setPasswordExpiry: System awareness note System awareness note System awareness note System awareness note
 *    - setKeyguardFeatures: System awareness note System awareness note System awareness note
 *    - enableNetworkLogging: System awareness note System awareness note System awareness note (Device Owner System awareness note)
 */
class OmniDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "OmniDeviceAdmin"
        private const val MAX_AUDIT_LOG_SIZE = 200
        private const val THREAT_SCORE_PER_FAILURE = 10

        // System awareness note System awareness note
        private const val THREAT_LOCK_THRESHOLD = 30    // 3 System awareness note → System awareness note
        private const val THREAT_CAMERA_THRESHOLD = 100 // 10 System awareness note → System awareness note System awareness note
        private const val THREAT_ALERT_THRESHOLD = 150  // 15 System awareness note → System awareness note System awareness note

        // ── State ────────────────────────────────────────────────────────────
        private val _deviceAdminState = MutableStateFlow(DeviceAdminState())
        val deviceAdminState: StateFlow<DeviceAdminState> = _deviceAdminState.asStateFlow()

        private val _threatScore = MutableStateFlow(0)
        val threatScore: StateFlow<Int> = _threatScore.asStateFlow()

        /** System awareness note System awareness note: System awareness note MAX_AUDIT_LOG_SIZE System awareness note */
        private val auditLog = ConcurrentLinkedDeque<AuditEntry>()

        // ── Core Helpers ──────────────────────────────────────────────────────

        fun getComponentName(context: Context): ComponentName =
            ComponentName(context, OmniDeviceAdminReceiver::class.java)

        fun isAdminActive(context: Context): Boolean {
            val dpm = getDpm(context) ?: return false
            return dpm.isAdminActive(getComponentName(context))
        }

        fun isDeviceOwner(context: Context): Boolean =
            getDpm(context)?.isDeviceOwnerApp(context.packageName) == true

        fun requestAdminActivation(context: Context, explanation: String? = null) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getComponentName(context))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    explanation ?: "OmniDev System awareness note Device Admin System awareness note System awareness note System awareness note System awareness note."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(intent) }
            catch (e: Exception) { Log.e(TAG, "System awareness note System awareness note Device Admin activation", e) }
        }

        // ── Security Actions ──────────────────────────────────────────────────

        /**
         * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
         */
        fun lockScreen(context: Context, reason: String = "Agent command"): Boolean {
            val dpm = getDpm(context) ?: return false
            if (!dpm.isAdminActive(getComponentName(context))) {
                Log.w(TAG, "System awareness note System awareness note: Admin System awareness note System awareness note")
                return false
            }
            return try {
                dpm.lockNow()
                addAuditEntry(AuditEntry("LOCK_SCREEN", reason, success = true))
                Log.i(TAG, "✅ System awareness note System awareness note: $reason")
                true
            } catch (e: Exception) {
                addAuditEntry(AuditEntry("LOCK_SCREEN", reason, success = false, error = e.message))
                false
            }
        }

        /**
         * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
         */
        fun setCameraDisabled(context: Context, disabled: Boolean, reason: String = "Agent policy"): Boolean {
            val dpm = getDpm(context) ?: return false
            if (!dpm.isAdminActive(getComponentName(context))) return false
            return try {
                dpm.setCameraDisabled(getComponentName(context), disabled)
                addAuditEntry(AuditEntry(
                    if (disabled) "CAMERA_DISABLED" else "CAMERA_ENABLED", reason, success = true
                ))
                updateDeviceState(context)
                Log.i(TAG, "${if (disabled) "System awareness note" else "System awareness note"} System awareness note: $reason")
                true
            } catch (e: Exception) {
                addAuditEntry(AuditEntry("CAMERA_STATE_CHANGE", reason, success = false, error = e.message))
                false
            }
        }

        /**
         * [System awareness note] System awareness note System awareness note System awareness note System awareness note System awareness note.
         * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note X System awareness note.
         */
        fun setPasswordExpiry(context: Context, daysFromNow: Int): Boolean {
            if (!isDeviceOwner(context)) return false
            val dpm = getDpm(context) ?: return false
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val expiryMs = System.currentTimeMillis() + daysFromNow * 24 * 60 * 60 * 1000L
                    @Suppress("DEPRECATION")
                    dpm.setPasswordExpirationTimeout(getComponentName(context), expiryMs)
                    addAuditEntry(AuditEntry("SET_PASSWORD_EXPIRY", "System awareness note System awareness note $daysFromNow System awareness note", success = true))
                    true
                } else false
            } catch (e: Exception) { false }
        }

        /**
         * [System awareness note] System awareness note System awareness note System awareness note System awareness note (Keyguard Features).
         */
        fun setKeyguardFeatures(context: Context, features: Int): Boolean {
            val dpm = getDpm(context) ?: return false
            if (!dpm.isAdminActive(getComponentName(context))) return false
            return try {
                dpm.setKeyguardDisabledFeatures(getComponentName(context), features)
                addAuditEntry(AuditEntry("SET_KEYGUARD_FEATURES", "features=$features", success = true))
                true
            } catch (e: Exception) { false }
        }

        /**
         * [System awareness note] System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
         */
        @Suppress("DEPRECATION")
        fun setMinPasswordLength(context: Context, minLength: Int): Boolean {
            val dpm = getDpm(context) ?: return false
            if (!dpm.isAdminActive(getComponentName(context))) return false
            return try {
                if (isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    dpm.setPasswordMinimumLength(getComponentName(context), minLength)
                    addAuditEntry(AuditEntry("SET_MIN_PASSWORD", "min=$minLength", success = true))
                    true
                } else {
                    Log.w(TAG, "Device Owner System awareness note System awareness note setPasswordMinimumLength System awareness note Android 11+")
                    false
                }
            } catch (e: Exception) { false }
        }

        fun setMaxScreenLockTimeout(context: Context, timeoutMs: Long): Boolean {
            val dpm = getDpm(context) ?: return false
            if (!dpm.isAdminActive(getComponentName(context))) return false
            return try {
                dpm.setMaximumTimeToLock(getComponentName(context), timeoutMs)
                addAuditEntry(AuditEntry("SET_LOCK_TIMEOUT", "timeout=${timeoutMs}ms", success = true))
                true
            } catch (e: Exception) { false }
        }

        /**
         * ⚠️ System awareness note System awareness note: System awareness note System awareness note System awareness note System awareness note (Factory Reset).
         * System awareness note System awareness note System awareness note.
         */
        fun wipeDeviceData(context: Context, confirmationToken: String): Boolean {
            // ─── TIER POLICY GUARD ───────────────────────────────────────────
            // Lite / Norm / OEM disallow factory reset at the tier level, even
            // though the DeviceAdminReceiver may be registered in the manifest.
            // OEM in particular has system-uid privilege but OEM partner policy
            // forbids destructive actions.
            val policy = com.omnidev.workspace.core.policy.TierPolicyHolder.current
            if (!policy.allowDeviceAdminWipe) {
                Log.e(TAG, "⛔ wipeDeviceData blocked by tier policy (tier=${policy.tier}).")
                addAuditEntry(
                    AuditEntry(
                        "WIPE_REJECTED",
                        "System awareness note System awareness note System awareness note System awareness note System awareness note (tier=${policy.tier})",
                        success = false
                    )
                )
                return false
            }
            // ────────────────────────────────────────────────────────────────

            if (confirmationToken != "CONFIRMED_WIPE_ALL_DATA") {
                Log.e(TAG, "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note!")
                addAuditEntry(AuditEntry("WIPE_REJECTED", "System awareness note System awareness note System awareness note", success = false))
                return false
            }
            val dpm = getDpm(context) ?: return false
            if (!dpm.isAdminActive(getComponentName(context))) return false
            return try {
                addAuditEntry(AuditEntry("DEVICE_WIPE", "System awareness note System awareness note System awareness note System awareness note", success = true))
                Log.e(TAG, "⚠️ System awareness note System awareness note System awareness note System awareness note!")
                dpm.wipeData(0)
                true
            } catch (e: Exception) {
                addAuditEntry(AuditEntry("DEVICE_WIPE", "System awareness note", success = false, error = e.message))
                false
            }
        }

        // ── Audit & Threat System ─────────────────────────────────────────────

        /**
         * System awareness note System awareness note System awareness note System awareness note System awareness note.
         */
        fun getAuditLog(limit: Int = 50): String = buildString {
            append("📋 System awareness note System awareness note Device Admin (System awareness note $limit):\n")
            append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            val entries = auditLog.toList().takeLast(limit)
            if (entries.isEmpty()) { append("(System awareness note)"); return@buildString }
            entries.reversed().forEach { entry ->
                val status = if (entry.success) "✅" else "❌"
                append("$status [${entry.formattedTime}] ${entry.action}: ${entry.reason}\n")
                if (!entry.error.isNullOrEmpty()) append("   ⚠️ ${entry.error}\n")
            }
        }

        /**
         * System awareness note System awareness note System awareness note System awareness note.
         */
        fun getSecurityReport(context: Context): String = buildString {
            val state = _deviceAdminState.value
            val score = _threatScore.value

            append("🔐 System awareness note System awareness note System awareness note\n")
            append("━━━━━━━━━━━━━━━━━━━━━\n")
            append("Device Admin System awareness note: ${isAdminActive(context)}\n")
            append("Device Owner: ${isDeviceOwner(context)}\n")
            append("System awareness note System awareness note: ${state.isCameraDisabled}\n")
            append("System awareness note System awareness note: $score\n")
            append("System awareness note System awareness note: ${getThreatLevel(score).name}\n")
            append("System awareness note System awareness note System awareness note: ${state.failedPasswordAttempts}\n")
            append("\n")
            append(getAuditLog(10))
        }

        private fun getThreatLevel(score: Int): ThreatLevel = when {
            score >= THREAT_ALERT_THRESHOLD -> ThreatLevel.CRITICAL
            score >= THREAT_CAMERA_THRESHOLD -> ThreatLevel.HIGH
            score >= THREAT_LOCK_THRESHOLD -> ThreatLevel.MEDIUM
            score > 0 -> ThreatLevel.LOW
            else -> ThreatLevel.NONE
        }

        private fun addAuditEntry(entry: AuditEntry) {
            if (auditLog.size >= MAX_AUDIT_LOG_SIZE) auditLog.pollFirst()
            auditLog.addLast(entry)
            DebugLogManager.appendInfo(TAG, "Audit: ${entry.action} — ${entry.reason}")
        }

        private fun updateDeviceState(context: Context) {
            val dpm = getDpm(context) ?: return
            val comp = getComponentName(context)
            if (!dpm.isAdminActive(comp)) return
            _deviceAdminState.value = DeviceAdminState(
                isAdminActive = true,
                isCameraDisabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    dpm.getCameraDisabled(comp) else false,
                isDeviceOwner = isDeviceOwner(context)
            )
        }

        private fun getDpm(context: Context): DevicePolicyManager? =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    }

    // ── Receiver Callbacks ────────────────────────────────────────────────────

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        updateDeviceState(context)
        addAuditEntry(AuditEntry("ADMIN_ENABLED", "System awareness note System awareness note System awareness note", success = true))
        Toast.makeText(context, "✅ OmniDev Device Admin System awareness note", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "✅ Device Admin System awareness note System awareness note")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        addAuditEntry(AuditEntry("ADMIN_DISABLED", "System awareness note System awareness note System awareness note System awareness note", success = true))
        _deviceAdminState.value = DeviceAdminState()
        Toast.makeText(context, "⚠️ OmniDev Device Admin System awareness note", Toast.LENGTH_SHORT).show()
        Log.w(TAG, "Device Admin System awareness note System awareness note System awareness note")
    }

    override fun onPasswordChanged(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, user)
        // System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
        _threatScore.value = 0
        _deviceAdminState.value = _deviceAdminState.value.copy(failedPasswordAttempts = 0)
        addAuditEntry(AuditEntry("PASSWORD_CHANGED", "System awareness note System awareness note System awareness note System awareness note System awareness note", success = true))
        Log.d(TAG, "System awareness note System awareness note System awareness note System awareness note System awareness note — System awareness note System awareness note System awareness note")
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordFailed(context, intent, user)

        val newScore = _threatScore.value + THREAT_SCORE_PER_FAILURE
        _threatScore.value = newScore
        val attempts = _deviceAdminState.value.failedPasswordAttempts + 1
        _deviceAdminState.value = _deviceAdminState.value.copy(failedPasswordAttempts = attempts)

        Log.w(TAG, "⚠️ System awareness note System awareness note System awareness note #$attempts | System awareness note System awareness note: $newScore")
        addAuditEntry(AuditEntry("PASSWORD_FAILED", "System awareness note #$attempts", success = false))

        // System awareness note System awareness note System awareness note System awareness note System awareness note
        when {
            newScore >= THREAT_ALERT_THRESHOLD -> {
                // System awareness note System awareness note: System awareness note + System awareness note System awareness note + System awareness note
                lockScreen(context, "System awareness note System awareness note: $attempts System awareness note System awareness note")
                setCameraDisabled(context, true, "System awareness note System awareness note System awareness note")
                addAuditEntry(AuditEntry("AUTO_RESPONSE_CRITICAL",
                    "System awareness note + System awareness note System awareness note System awareness note $attempts System awareness note", success = true))
            }
            newScore >= THREAT_CAMERA_THRESHOLD -> {
                // System awareness note System awareness note: System awareness note + System awareness note
                lockScreen(context, "System awareness note System awareness note: $attempts System awareness note System awareness note")
                setCameraDisabled(context, true, "System awareness note System awareness note System awareness note")
            }
            newScore >= THREAT_LOCK_THRESHOLD -> {
                // System awareness note System awareness note: System awareness note System awareness note
                lockScreen(context, "System awareness note System awareness note: $attempts System awareness note System awareness note")
            }
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordSucceeded(context, intent, user)
        _threatScore.value = 0
        _deviceAdminState.value = _deviceAdminState.value.copy(failedPasswordAttempts = 0)
        addAuditEntry(AuditEntry("PASSWORD_SUCCESS", "System awareness note System awareness note — System awareness note System awareness note", success = true))
        Log.i(TAG, "✅ System awareness note System awareness note — System awareness note System awareness note System awareness note")
    }

    // ── Data Classes ──────────────────────────────────────────────────────────

    data class DeviceAdminState(
        val isAdminActive: Boolean = false,
        val isDeviceOwner: Boolean = false,
        val isCameraDisabled: Boolean = false,
        val failedPasswordAttempts: Int = 0
    )

    data class AuditEntry(
        val action: String,
        val reason: String,
        val success: Boolean,
        val error: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val formattedTime: String get() =
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    enum class ThreatLevel { NONE, LOW, MEDIUM, HIGH, CRITICAL }
}
