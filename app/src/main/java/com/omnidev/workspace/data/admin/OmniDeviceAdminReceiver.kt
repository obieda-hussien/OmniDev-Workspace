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
 * OmniDeviceAdminReceiver — مدير الجهاز المتقدم
 *
 * الجيل الثاني: أمان ذكي وتحليل سلوكي
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **نقاط التهديد الديناميكية (Dynamic Threat Scoring)**:
 *    كل محاولة فاشلة لفتح القفل تُضيف نقاطاً للتهديد.
 *    عند تجاوز عتبة معينة → تفعيل إجراء أمني تلقائي.
 *
 * 2. **سجل التدقيق الكامل (Audit Log)**:
 *    كل إجراء أمني (قفل، تغيير كلمة مرور، تعطيل كاميرا)
 *    يُسجَّل مع الوقت والسبب — قابل للاستعراض من الوكيل.
 *
 * 3. **سياسات الاستجابة التلقائية (Auto-Response Policies)**:
 *    - 3 محاولات فاشلة → قفل فوري
 *    - 10 محاولات فاشلة → تعطيل الكاميرا وإشعار
 *    - 15 محاولة فاشلة → زيادة مهلة القفل تدريجياً
 *
 * 4. **مراقبة صحة الجهاز (Device Health Monitoring)**:
 *    يتتبع: وضع Admin نشط/معطّل، Device Owner، كاميرا مفعّلة/معطّلة.
 *
 * 5. **إجراءات موسّعة (Extended Actions)**:
 *    - setPasswordExpiry: انتهاء صلاحية كلمة المرور
 *    - setKeyguardFeatures: تخصيص شاشة القفل
 *    - enableNetworkLogging: تسجيل حركة الشبكة (Device Owner فقط)
 */
class OmniDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "OmniDeviceAdmin"
        private const val MAX_AUDIT_LOG_SIZE = 200
        private const val THREAT_SCORE_PER_FAILURE = 10

        // عتبات التهديد
        private const val THREAT_LOCK_THRESHOLD = 30    // 3 محاولات → قفل
        private const val THREAT_CAMERA_THRESHOLD = 100 // 10 محاولات → تعطيل كاميرا
        private const val THREAT_ALERT_THRESHOLD = 150  // 15 محاولة → تنبيه متقدم

        // ── State ────────────────────────────────────────────────────────────
        private val _deviceAdminState = MutableStateFlow(DeviceAdminState())
        val deviceAdminState: StateFlow<DeviceAdminState> = _deviceAdminState.asStateFlow()

        private val _threatScore = MutableStateFlow(0)
        val threatScore: StateFlow<Int> = _threatScore.asStateFlow()

        /** سجل التدقيق: آخر MAX_AUDIT_LOG_SIZE حدث */
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
                    explanation ?: "OmniDev يحتاج Device Admin لحماية جهازك وإدارته ذكياً."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(intent) }
            catch (e: Exception) { Log.e(TAG, "فشل تشغيل Device Admin activation", e) }
        }

        // ── Security Actions ──────────────────────────────────────────────────

        /**
         * يقفل الشاشة فوراً مع تسجيل السبب.
         */
        fun lockScreen(context: Context, reason: String = "Agent command"): Boolean {
            val dpm = getDpm(context) ?: return false
            if (!dpm.isAdminActive(getComponentName(context))) {
                Log.w(TAG, "قفل الشاشة: Admin غير نشط")
                return false
            }
            return try {
                dpm.lockNow()
                addAuditEntry(AuditEntry("LOCK_SCREEN", reason, success = true))
                Log.i(TAG, "✅ قُفلت الشاشة: $reason")
                true
            } catch (e: Exception) {
                addAuditEntry(AuditEntry("LOCK_SCREEN", reason, success = false, error = e.message))
                false
            }
        }

        /**
         * يُعطّل أو يُفعّل الكاميرات مع تسجيل.
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
                Log.i(TAG, "${if (disabled) "تعطيل" else "تفعيل"} الكاميرا: $reason")
                true
            } catch (e: Exception) {
                addAuditEntry(AuditEntry("CAMERA_STATE_CHANGE", reason, success = false, error = e.message))
                false
            }
        }

        /**
         * [جديد] يُعيّن مهلة انتهاء كلمة المرور.
         * يجبر المستخدم على تغيير كلمة المرور بعد X يوم.
         */
        fun setPasswordExpiry(context: Context, daysFromNow: Int): Boolean {
            if (!isDeviceOwner(context)) return false
            val dpm = getDpm(context) ?: return false
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val expiryMs = System.currentTimeMillis() + daysFromNow * 24 * 60 * 60 * 1000L
                    @Suppress("DEPRECATION")
                    dpm.setPasswordExpirationTimeout(getComponentName(context), expiryMs)
                    addAuditEntry(AuditEntry("SET_PASSWORD_EXPIRY", "انتهاء بعد $daysFromNow يوم", success = true))
                    true
                } else false
            } catch (e: Exception) { false }
        }

        /**
         * [جديد] يُعيّن خصائص شاشة القفل (Keyguard Features).
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
         * [جديد] يُعيّن الحد الأدنى لطول كلمة المرور مع تحقق من الصلاحيات.
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
                    Log.w(TAG, "Device Owner مطلوب لـ setPasswordMinimumLength على Android 11+")
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
         * ⚠️ خطير جداً: يمسح كل بيانات الجهاز (Factory Reset).
         * يتطلب تأكيداً مزدوجاً.
         */
        fun wipeDeviceData(context: Context, confirmationToken: String): Boolean {
            if (confirmationToken != "CONFIRMED_WIPE_ALL_DATA") {
                Log.e(TAG, "محاولة مسح بيانات الجهاز بدون تأكيد صحيح!")
                addAuditEntry(AuditEntry("WIPE_REJECTED", "رمز تأكيد خاطئ", success = false))
                return false
            }
            val dpm = getDpm(context) ?: return false
            if (!dpm.isAdminActive(getComponentName(context))) return false
            return try {
                addAuditEntry(AuditEntry("DEVICE_WIPE", "تم تأكيد المسح الكامل", success = true))
                Log.e(TAG, "⚠️ بدأ مسح بيانات الجهاز!")
                dpm.wipeData(0)
                true
            } catch (e: Exception) {
                addAuditEntry(AuditEntry("DEVICE_WIPE", "فشل", success = false, error = e.message))
                false
            }
        }

        // ── Audit & Threat System ─────────────────────────────────────────────

        /**
         * يُرجع سجل التدقيق الكامل للوكيل.
         */
        fun getAuditLog(limit: Int = 50): String = buildString {
            append("📋 سجل تدقيق Device Admin (آخر $limit):\n")
            append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            val entries = auditLog.toList().takeLast(limit)
            if (entries.isEmpty()) { append("(فارغ)"); return@buildString }
            entries.reversed().forEach { entry ->
                val status = if (entry.success) "✅" else "❌"
                append("$status [${entry.formattedTime}] ${entry.action}: ${entry.reason}\n")
                if (!entry.error.isNullOrEmpty()) append("   ⚠️ ${entry.error}\n")
            }
        }

        /**
         * يُرجع تقرير الأمان الكامل.
         */
        fun getSecurityReport(context: Context): String = buildString {
            val state = _deviceAdminState.value
            val score = _threatScore.value

            append("🔐 تقرير أمان الجهاز\n")
            append("━━━━━━━━━━━━━━━━━━━━━\n")
            append("Device Admin نشط: ${isAdminActive(context)}\n")
            append("Device Owner: ${isDeviceOwner(context)}\n")
            append("الكاميرا معطّلة: ${state.isCameraDisabled}\n")
            append("نقاط التهديد: $score\n")
            append("مستوى التهديد: ${getThreatLevel(score).name}\n")
            append("محاولات دخول فاشلة: ${state.failedPasswordAttempts}\n")
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
        addAuditEntry(AuditEntry("ADMIN_ENABLED", "تفعيل من المستخدم", success = true))
        Toast.makeText(context, "✅ OmniDev Device Admin نشط", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "✅ Device Admin تم تفعيله")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        addAuditEntry(AuditEntry("ADMIN_DISABLED", "إلغاء تفعيل من المستخدم", success = true))
        _deviceAdminState.value = DeviceAdminState()
        Toast.makeText(context, "⚠️ OmniDev Device Admin معطّل", Toast.LENGTH_SHORT).show()
        Log.w(TAG, "Device Admin تم إلغاء تفعيله")
    }

    override fun onPasswordChanged(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, user)
        // إعادة تعيين نقاط التهديد عند تغيير كلمة المرور
        _threatScore.value = 0
        _deviceAdminState.value = _deviceAdminState.value.copy(failedPasswordAttempts = 0)
        addAuditEntry(AuditEntry("PASSWORD_CHANGED", "تم تغيير كلمة المرور بنجاح", success = true))
        Log.d(TAG, "تم تغيير كلمة مرور الجهاز — تصفير نقاط التهديد")
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordFailed(context, intent, user)

        val newScore = _threatScore.value + THREAT_SCORE_PER_FAILURE
        _threatScore.value = newScore
        val attempts = _deviceAdminState.value.failedPasswordAttempts + 1
        _deviceAdminState.value = _deviceAdminState.value.copy(failedPasswordAttempts = attempts)

        Log.w(TAG, "⚠️ محاولة دخول فاشلة #$attempts | نقاط التهديد: $newScore")
        addAuditEntry(AuditEntry("PASSWORD_FAILED", "محاولة #$attempts", success = false))

        // الاستجابة التلقائية حسب مستوى التهديد
        when {
            newScore >= THREAT_ALERT_THRESHOLD -> {
                // مستوى حرج: قفل + تعطيل كاميرا + تسجيل
                lockScreen(context, "تهديد حرج: $attempts محاولة فاشلة")
                setCameraDisabled(context, true, "تهديد أمني حرج")
                addAuditEntry(AuditEntry("AUTO_RESPONSE_CRITICAL",
                    "قفل + تعطيل كاميرا بعد $attempts محاولة", success = true))
            }
            newScore >= THREAT_CAMERA_THRESHOLD -> {
                // مستوى عالٍ: قفل + تسجيل
                lockScreen(context, "تهديد عالٍ: $attempts محاولة فاشلة")
                setCameraDisabled(context, true, "تهديد أمني عالٍ")
            }
            newScore >= THREAT_LOCK_THRESHOLD -> {
                // مستوى متوسط: قفل فقط
                lockScreen(context, "تهديد متوسط: $attempts محاولة فاشلة")
            }
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordSucceeded(context, intent, user)
        _threatScore.value = 0
        _deviceAdminState.value = _deviceAdminState.value.copy(failedPasswordAttempts = 0)
        addAuditEntry(AuditEntry("PASSWORD_SUCCESS", "دخول ناجح — تصفير التهديد", success = true))
        Log.i(TAG, "✅ دخول ناجح — تصفير نقاط التهديد")
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
