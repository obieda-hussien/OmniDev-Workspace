package com.omnidev.workspace.data.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.omnidev.workspace.data.debug.DebugLogManager
import com.omnidev.workspace.data.sync.OmniSyncService
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BootReceiver — مُشغّل الإقلاع الذكي
 *
 * الجيل الثاني: إقلاع مرحلي ذكي
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **كشف نوع الإقلاع (Boot Type Detection)**:
 *    - COLD_BOOT: إقلاع كامل من إيقاف تشغيل
 *    - WARM_BOOT: إعادة تشغيل عادية
 *    - UPDATE_BOOT: ترقية/تحديث التطبيق
 *    - QUICK_BOOT: Fast Boot (Qualcomm/HTC)
 *    لكل نوع سياسة تشغيل خدمات مختلفة.
 *
 * 2. **الإقلاع المرحلي (Phased Startup)**:
 *    المرحلة 1 (فوري): SyncService — أحرص خدمة، تبدأ أولاً
 *    المرحلة 2 (+8s): فحص صحة الخدمات والتحقق من نجاح الإقلاع
 *    هذا يمنع قتل الخدمات بسبب بدء كل شيء دفعة واحدة في الذاكرة المحدودة.
 *
 * 3. **سجل الإقلاع (Boot Log)**:
 *    يُسجّل كل إقلاع مع: الوقت، النوع، الخدمات التي بدأت، الإخفاقات.
 *    مفيد لتشخيص مشاكل الإقلاع والـ ANR.
 *
 * 4. **حماية من الـ Storm**: إذا أُعيد الإقلاع 3 مرات في 5 دقائق →
 *    تأخير الخدمات لتجنب الحلقة المفرغة.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        // تأخيرات المراحل (بالمللي ثانية)
        private const val PHASE_1_DELAY_MS = 0L
        private const val PHASE_2_DELAY_MS = 8_000L

        // حماية من إعادة الإقلاع المتكررة
        private const val RAPID_REBOOT_THRESHOLD = 3
        private const val RAPID_REBOOT_WINDOW_MS = 5 * 60 * 1000L // 5 دقائق
        private const val RAPID_REBOOT_PENALTY_DELAY_MS = 15_000L  // تأخير 15 ثانية

        private val recentBootTimes = mutableListOf<Long>()

        private val prefs_key_boot_count = "boot_count"
        private val prefs_key_last_boot = "last_boot_time"
        private val prefs_name = "omnidev_boot_prefs"
    }

    enum class BootType {
        COLD_BOOT,   // إقلاع كامل
        WARM_BOOT,   // إعادة تشغيل
        UPDATE_BOOT, // تحديث التطبيق
        QUICK_BOOT   // Fast Boot
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val bootType = detectBootType(action)
        val bootTime = System.currentTimeMillis()

        Log.i(TAG, "🚀 حدث إقلاع: $action | النوع: $bootType")

        // سجّل هذا الإقلاع
        DebugLogManager.appendInfo(TAG, buildString {
            append("إقلاع جديد: $bootType")
            append(" في ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(bootTime))}")
        })

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                launchPhasedStartup(context, bootType, bootTime)
            }
        }
    }

    private fun detectBootType(action: String): BootType = when (action) {
        Intent.ACTION_MY_PACKAGE_REPLACED -> BootType.UPDATE_BOOT
        "android.intent.action.QUICKBOOT_POWERON" -> BootType.QUICK_BOOT
        Intent.ACTION_BOOT_COMPLETED -> BootType.COLD_BOOT
        else -> BootType.WARM_BOOT
    }

    private fun launchPhasedStartup(context: Context, bootType: BootType, bootTime: Long) {
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                // ── حماية من إعادة الإقلاع المتكررة ──
                val isRapidRebooting = checkRapidReboot(bootTime)
                if (isRapidRebooting) {
                    Log.w(TAG, "⚠️ إقلاع متكرر — تأخير بدء الخدمات ${RAPID_REBOOT_PENALTY_DELAY_MS}ms")
                    DebugLogManager.appendWarning(TAG, "إقلاع متكرر مكتشف — تأخير الخدمات")
                    delay(RAPID_REBOOT_PENALTY_DELAY_MS)
                }

                // ─────────────────────────────────────────────────────────────
                // المرحلة 1: خدمات الأساس (SyncService)
                // ─────────────────────────────────────────────────────────────
                delay(PHASE_1_DELAY_MS)
                Log.i(TAG, "📌 المرحلة 1: خدمات الأساس")
                val phase1Results = startPhase1Services(context, bootType)
                DebugLogManager.appendInfo(TAG, "المرحلة 1: ${phase1Results.joinToString(", ")}")

                // ─────────────────────────────────────────────────────────────
                // المرحلة 2: التحقق من الصحة
                // ─────────────────────────────────────────────────────────────
                delay(PHASE_2_DELAY_MS)
                Log.i(TAG, "📌 المرحلة 2: فحص صحة الخدمات")
                val healthReport = validateServiceHealth(context)
                DebugLogManager.appendInfo(TAG, "صحة الإقلاع: $healthReport")

                Log.i(TAG, "✅ الإقلاع المرحلي اكتمل — ${bootType.name}")

            } catch (e: Exception) {
                Log.e(TAG, "❌ خطأ في الإقلاع المرحلي: ${e.message}", e)
                DebugLogManager.appendError(TAG, e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * المرحلة 1: الخدمات الأساسية التي يجب أن تبدأ دائماً.
     */
    private fun startPhase1Services(context: Context, bootType: BootType): List<String> {
        val results = mutableListOf<String>()

        // SyncService — أساسي دائماً
        safeStartForeground(context, OmniSyncService::class.java)
            .let { results.add(if (it) "✅ SyncService" else "❌ SyncService") }

        return results
    }

    /**
     * المرحلة 2: التحقق من صحة الخدمات وتسجيل التقرير.
     */
    private fun validateServiceHealth(context: Context): String = buildString {
        append("فحص بعد الإقلاع:\n")

        // يمكن توسيع هذا للتحقق من SyncService state وما إلى ذلك
        val syncState = OmniSyncService.syncState.value
        append("  SyncService: ${syncState.name}\n")

        val circuitState = OmniSyncService.circuitState.value
        append("  Circuit Breaker: ${circuitState.name}\n")

        if (syncState == OmniSyncService.SyncState.ERROR) {
            append("  ⚠️ SyncService في حالة خطأ — ستُعيد المحاولة تلقائياً")
        }
    }

    /**
     * يكتشف ما إذا كانت هناك إقلاعات متكررة في فترة قصيرة.
     */
    private fun checkRapidReboot(bootTime: Long): Boolean {
        recentBootTimes.add(bootTime)
        // إزالة الإقلاعات القديمة خارج النافذة الزمنية
        recentBootTimes.removeAll { bootTime - it > RAPID_REBOOT_WINDOW_MS }
        return recentBootTimes.size >= RAPID_REBOOT_THRESHOLD
    }

    /**
     * يبدأ خدمة Foreground بأمان مع معالجة استثناءات كل إصدار Android.
     */
    private fun <T : android.app.Service> safeStartForeground(
        context: Context,
        serviceClass: Class<T>
    ): Boolean {
        return try {
            val intent = Intent(context, serviceClass)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "بدأت: ${serviceClass.simpleName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "فشل بدء ${serviceClass.simpleName}: ${e.message}")
            false
        }
    }
}
