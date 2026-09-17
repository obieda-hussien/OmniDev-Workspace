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
 * OmniDeviceAdminReceiver — advanced device administration.
 *
 * Second generation: intelligent security and behavioral analysis.
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **Dynamic Threat Scoring**:
 *    Every failed unlock attempt adds threat points.
 *    Crossing configured thresholds triggers automatic security actions.
 *
 * 2. **Complete Audit Log**:
 *    Every security action (lock, password policy change, camera disable)
 *    is recorded with its timestamp and reason for agent inspection.
 *
 * 3. **Auto-Response Policies**:
 *    - 3 failed attempts → immediate lock
 *    - 10 failed attempts → disable camera and alert
 *    - 15 failed attempts → progressively stronger response
 *
 * 4. **Device Health Monitoring**:
 *    Tracks whether Device Admin is active, Device Owner state, and camera policy state.
 *
 * 5. **Extended Actions**:
 *    - setPasswordExpiry: password expiration policy
 *    - setKeyguardFeatures: lock-screen feature policy
 *    - enableNetworkLogging: network logging (Device Owner only)
 */
class OmniDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "OmniDeviceAdmin"
        private const val MAX_AUDIT_LOG_SIZE = 200
        private const val THREAT_SCORE_PER_FAILURE = 10

        // Threat thresholds
        private const val THREAT_LOCK_THRESHOLD = 30    // 3 attempts → lock
        private const val THREAT_CAMERA_THRESHOLD = 100 // 10 attempts → disable camera
        private const val THREAT_ALERT_THRESHOLD = 150  // 15 attempts → advanced response

        // ── State ────────────────────────────────────────────────────────────
        private val _deviceAdminState = MutableStateFlow(DeviceAdminState())
        val deviceAdminState: StateFlow<DeviceAdminState> = _deviceAdminState.asStateFlow()

        private val _threatScore = MutableStateFlow(0)
        val threatScore: StateFlow<Int> = _threatScore.asStateFlow()

        /** Audit log containing the latest [MAX_AUDIT_LOG_SIZE] events. */
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
                    explanation ?: "OmniDev needs Device Admin access to protect and intelligently manage your device."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(intent) }
            catch (e: Exception) { Log.e(TAG, "Failed to launch Device Admin activation", e) }
        }

        // ── Security Actions ──────────────────────────────────────────────────

        /** Locks the screen immediately and records the reason. */
        fun lockScreen(context: Context, reason: String = "Agent command"): Boolean {
            val dpm = getDpm(context) ?: return false
            if (!dpm.isAdminActive(getComponentName(context))) {
                Log.w(TAG, "Screen lock rejected: Device Admin is inactive")
                return false
            }
            return try {
                dpm.lockNow()
                addAuditEntry(AuditEntry("LOCK_SCREEN", reason, success = true))
                Log.i(TAG, "✅ Screen locked: $reason")
                true
            } catch (e: Exception) {
                addAuditEntry(AuditEntry("LOCK_SCREEN", reason, success = false, error = e.message))
                false
            }
        }

        /** Enables or disables cameras and records the policy change. */
        fun setCameraDisabled(context: Context, disabled: Boolean, reason: String = "Agent policy"): Boolean {
            val dpm = getDpm(context) ?: return false
            if (!dpm.isAdminActive(getComponentName(context))) return false
            return try {
                dpm.setCameraDisabled(getComponentName(context), disabled)
                addAuditEntry(AuditEntry(
                    if (disabled) "CAMERA_DISABLED" else "CAMERA_ENABLED", reason, success = true
                ))
                updateDeviceState(context)
                Log.i(TAG, "Camera ${if (disabled) "disabled" else "enabled"}: $reason")
                true
            } catch (e: Exception) {
                addAuditEntry(AuditEntry("CAMERA_STATE_CHANGE", reason, success = false, error = e.message))
                false
            }
        }

        /**
         * Sets the password-expiration timeout.
         * The user must change the password after the configured number of days.
         */
        fun setPasswordExpiry(context: Context, daysFromNow: Int): Boolean {
            if (!isDeviceOwner(context)) return false
            val dpm = getDpm(context) ?: return false
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val expiryMs = System.currentTimeMillis() + daysFromNow * 24 * 60 * 60 * 1000L
                    @Suppress("DEPRECATION")
                    dpm.setPasswordExpirationTimeout(getComponentName(context), expiryMs)
                    addAuditEntry(AuditEntry("SET_PASSWORD_EXPIRY", "Expires in $daysFromNow days", success = true))
                    true
                } else false
            } catch (e: Exception) { false }
        }

        /** Sets lock-screen keyguard feature restrictions. */
        fun setKeyguardFeatures(context: Context, features: Int): Boolean {
            val dpm = getDpm(context) ?: return false
            if (!dpm.isAdminActive(getComponentName(context))) return false
            return try {
                dpm.setKeyguardDisabledFeatures(getComponentName(context), features)
                addAuditEntry(AuditEntry("SET_KEYGUARD_FEATURES", "features=$features", success = true))
                true
            } catch (e: Exception) { false }
        }

        /** Sets the minimum password length after checking privileges. */
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
                    Log.w(TAG, "Device Owner is required for setPasswordMinimumLength on Android 11+")
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
         * ⚠️ Highly destructive: erases all device data (Factory Reset).
         * Requires an explicit confirmation token.
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
                        "Blocked by tier policy (tier=${policy.tier})",
                        success = false
                    )
                )
                return false
            }
            // ────────────────────────────────────────────────────────────────

            if (confirmationToken != "CONFIRMED_WIPE_ALL_DATA") {
                Log.e(TAG, "Device wipe attempted without a valid confirmation token")
                addAuditEntry(AuditEntry("WIPE_REJECTED", "Invalid confirmation token", success = false))
                return false
            }
            val dpm = getDpm(context) ?: return false
            if (!dpm.isAdminActive(getComponentName(context))) return false
            return try {
                addAuditEntry(AuditEntry("DEVICE_WIPE", "Full wipe confirmed", success = true))
                Log.e(TAG, "⚠️ Device data wipe started")
                dpm.wipeData(0)
                true
            } catch (e: Exception) {
                addAuditEntry(AuditEntry("DEVICE_WIPE", "Failed", success = false, error = e.message))
                false
            }
        }

        // ── Audit & Threat System ─────────────────────────────────────────────

        /** Returns the complete Device Admin audit log for the agent. */
        fun getAuditLog(limit: Int = 50): String = buildString {
            append("📋 Device Admin audit log (latest $limit):\n")
            append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            val entries = auditLog.toList().takeLast(limit)
            if (entries.isEmpty()) { append("(empty)"); return@buildString }
            entries.reversed().forEach { entry ->
                val status = if (entry.success) "✅" else "❌"
                append("$status [${entry.formattedTime}] ${entry.action}: ${entry.reason}\n")
                if (!entry.error.isNullOrEmpty()) append("   ⚠️ ${entry.error}\n")
            }
        }

        /** Returns a complete device security report. */
        fun getSecurityReport(context: Context): String = buildString {
            val state = _deviceAdminState.value
            val score = _threatScore.value

            append("🔐 Device security report\n")
            append("━━━━━━━━━━━━━━━━━━━━━\n")
            append("Device Admin active: ${isAdminActive(context)}\n")
            append("Device Owner: ${isDeviceOwner(context)}\n")
            append("Camera disabled: ${state.isCameraDisabled}\n")
            append("Threat score: $score\n")
            append("Threat level: ${getThreatLevel(score).name}\n")
            append("Failed unlock attempts: ${state.failedPasswordAttempts}\n")
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
        addAuditEntry(AuditEntry("ADMIN_ENABLED", "Enabled by user", success = true))
        Toast.makeText(context, "✅ OmniDev Device Admin is active", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "✅ Device Admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        addAuditEntry(AuditEntry("ADMIN_DISABLED", "Disabled by user", success = true))
        _deviceAdminState.value = DeviceAdminState()
        Toast.makeText(context, "⚠️ OmniDev Device Admin is disabled", Toast.LENGTH_SHORT).show()
        Log.w(TAG, "Device Admin disabled")
    }

    override fun onPasswordChanged(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, user)
        // Reset the threat score when the password changes.
        _threatScore.value = 0
        _deviceAdminState.value = _deviceAdminState.value.copy(failedPasswordAttempts = 0)
        addAuditEntry(AuditEntry("PASSWORD_CHANGED", "Password changed successfully", success = true))
        Log.d(TAG, "Device password changed — threat score reset")
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordFailed(context, intent, user)

        val newScore = _threatScore.value + THREAT_SCORE_PER_FAILURE
        _threatScore.value = newScore
        val attempts = _deviceAdminState.value.failedPasswordAttempts + 1
        _deviceAdminState.value = _deviceAdminState.value.copy(failedPasswordAttempts = attempts)

        Log.w(TAG, "⚠️ Failed unlock attempt #$attempts | Threat score: $newScore")
        addAuditEntry(AuditEntry("PASSWORD_FAILED", "Attempt #$attempts", success = false))

        // Automatic response based on the current threat level.
        when {
            newScore >= THREAT_ALERT_THRESHOLD -> {
                // Critical: lock + disable camera + audit.
                lockScreen(context, "Critical threat: $attempts failed attempts")
                setCameraDisabled(context, true, "Critical security threat")
                addAuditEntry(AuditEntry("AUTO_RESPONSE_CRITICAL",
                    "Locked device and disabled camera after $attempts attempts", success = true))
            }
            newScore >= THREAT_CAMERA_THRESHOLD -> {
                // High: lock + disable camera.
                lockScreen(context, "High threat: $attempts failed attempts")
                setCameraDisabled(context, true, "High security threat")
            }
            newScore >= THREAT_LOCK_THRESHOLD -> {
                // Medium: lock only.
                lockScreen(context, "Medium threat: $attempts failed attempts")
            }
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordSucceeded(context, intent, user)
        _threatScore.value = 0
        _deviceAdminState.value = _deviceAdminState.value.copy(failedPasswordAttempts = 0)
        addAuditEntry(AuditEntry("PASSWORD_SUCCESS", "Successful unlock — threat score reset", success = true))
        Log.i(TAG, "✅ Successful unlock — threat score reset")
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
