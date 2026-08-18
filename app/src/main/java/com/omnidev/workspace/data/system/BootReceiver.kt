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
 * BootReceiver — [Localized] [Localized] [Localized]
 *
 * [Localized] [Localized]: [Localized] [Localized] [Localized]
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **[Localized] [Localized] [Localized] (Boot Type Detection)**:
 *    - COLD_BOOT: [Localized] [Localized] [Localized] [Localized] [Localized]
 *    - WARM_BOOT: [Localized] [Localized] [Localized]
 *    - UPDATE_BOOT: [Localized]/[Localized] [Localized]
 *    - QUICK_BOOT: Fast Boot (Qualcomm/HTC)
 *    [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
 *
 * 2. **[Localized] [Localized] (Phased Startup)**:
 *    [Localized] 1 ([Localized]): SyncService — [Localized] [Localized] [Localized] [Localized]
 *    [Localized] 2 (+8s): [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
 *    [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
 *
 * 3. **[Localized] [Localized] (Boot Log)**:
 *    [Localized] [Localized] [Localized] [Localized]: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
 *    [Localized] [Localized] [Localized] [Localized] [Localized] ANR.
 *
 * 4. **[Localized] [Localized] [Localized] Storm**: [Localized] [Localized] [Localized] 3 [Localized] [Localized] 5 [Localized] →
 *    [Localized] [Localized] [Localized] [Localized] [Localized].
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        // [Localized] [Localized] ([Localized] [Localized])
        private const val PHASE_1_DELAY_MS = 0L
        private const val PHASE_2_DELAY_MS = 8_000L

        // [Localized] [Localized] [Localized] [Localized] [Localized]
        private const val RAPID_REBOOT_THRESHOLD = 3
        private const val RAPID_REBOOT_WINDOW_MS = 5 * 60 * 1000L // 5 [Localized]
        private const val RAPID_REBOOT_PENALTY_DELAY_MS = 15_000L  // [Localized] 15 [Localized]

        private val recentBootTimes = mutableListOf<Long>()

        private val prefs_key_boot_count = "boot_count"
        private val prefs_key_last_boot = "last_boot_time"
        private val prefs_name = "omnidev_boot_prefs"
    }

    enum class BootType {
        COLD_BOOT,   // [Localized] [Localized]
        WARM_BOOT,   // [Localized] [Localized]
        UPDATE_BOOT, // [Localized] [Localized]
        QUICK_BOOT   // Fast Boot
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val bootType = detectBootType(action)
        val bootTime = System.currentTimeMillis()

        Log.i(TAG, "🚀 [Localized] [Localized]: $action | [Localized]: $bootType")

        // [Localized] [Localized] [Localized]
        DebugLogManager.appendInfo(TAG, buildString {
            append("[Localized] [Localized]: $bootType")
            append(" [Localized] ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(bootTime))}")
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
                // ── [Localized] [Localized] [Localized] [Localized] [Localized] ──
                val isRapidRebooting = checkRapidReboot(bootTime)
                if (isRapidRebooting) {
                    Log.w(TAG, "⚠️ [Localized] [Localized] — [Localized] [Localized] [Localized] ${RAPID_REBOOT_PENALTY_DELAY_MS}ms")
                    DebugLogManager.appendWarning(TAG, "[Localized] [Localized] [Localized] — [Localized] [Localized]")
                    delay(RAPID_REBOOT_PENALTY_DELAY_MS)
                }

                // ─────────────────────────────────────────────────────────────
                // [Localized] 1: [Localized] [Localized] (SyncService)
                // ─────────────────────────────────────────────────────────────
                delay(PHASE_1_DELAY_MS)
                Log.i(TAG, "📌 [Localized] 1: [Localized] [Localized]")
                val phase1Results = startPhase1Services(context, bootType)
                DebugLogManager.appendInfo(TAG, "[Localized] 1: ${phase1Results.joinToString(", ")}")

                // ─────────────────────────────────────────────────────────────
                // [Localized] 2: [Localized] [Localized] [Localized]
                // ─────────────────────────────────────────────────────────────
                delay(PHASE_2_DELAY_MS)
                Log.i(TAG, "📌 [Localized] 2: [Localized] [Localized] [Localized]")
                val healthReport = validateServiceHealth(context)
                DebugLogManager.appendInfo(TAG, "[Localized] [Localized]: $healthReport")

                Log.i(TAG, "✅ [Localized] [Localized] [Localized] — ${bootType.name}")

            } catch (e: Exception) {
                Log.e(TAG, "❌ [Localized] [Localized] [Localized] [Localized]: ${e.message}", e)
                DebugLogManager.appendError(TAG, e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * [Localized] 1: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    private fun startPhase1Services(context: Context, bootType: BootType): List<String> {
        val results = mutableListOf<String>()

        // SyncService — [Localized] [Localized]
        safeStartForeground(context, OmniSyncService::class.java)
            .let { results.add(if (it) "✅ SyncService" else "❌ SyncService") }

        return results
    }

    /**
     * [Localized] 2: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    private fun validateServiceHealth(context: Context): String = buildString {
        append("[Localized] [Localized] [Localized]:\n")

        // [Localized] [Localized] [Localized] [Localized] [Localized] SyncService state [Localized] [Localized] [Localized]
        val syncState = OmniSyncService.syncState.value
        append("  SyncService: ${syncState.name}\n")

        val circuitState = OmniSyncService.circuitState.value
        append("  Circuit Breaker: ${circuitState.name}\n")

        if (syncState == OmniSyncService.SyncState.ERROR) {
            append("  ⚠️ SyncService [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized]")
        }
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    private fun checkRapidReboot(bootTime: Long): Boolean {
        recentBootTimes.add(bootTime)
        // [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
        recentBootTimes.removeAll { bootTime - it > RAPID_REBOOT_WINDOW_MS }
        return recentBootTimes.size >= RAPID_REBOOT_THRESHOLD
    }

    /**
     * [Localized] [Localized] Foreground [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] Android.
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
            Log.i(TAG, "[Localized]: ${serviceClass.simpleName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[Localized] [Localized] ${serviceClass.simpleName}: ${e.message}")
            false
        }
    }
}
