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
 * BootReceiver — System awareness note System awareness note System awareness note
 *
 * System awareness note System awareness note: System awareness note System awareness note System awareness note
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **System awareness note System awareness note System awareness note (Boot Type Detection)**:
 *    - COLD_BOOT: System awareness note System awareness note System awareness note System awareness note System awareness note
 *    - WARM_BOOT: System awareness note System awareness note System awareness note
 *    - UPDATE_BOOT: System awareness note/System awareness note System awareness note
 *    - QUICK_BOOT: Fast Boot (Qualcomm/HTC)
 *    System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
 *
 * 2. **System awareness note System awareness note (Phased Startup)**:
 *    System awareness note 1 (System awareness note): SyncService — System awareness note System awareness note System awareness note System awareness note
 *    System awareness note 2 (+8s): System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
 *    System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
 *
 * 3. **System awareness note System awareness note (Boot Log)**:
 *    System awareness note System awareness note System awareness note System awareness note: System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
 *    System awareness note System awareness note System awareness note System awareness note System awareness note ANR.
 *
 * 4. **System awareness note System awareness note System awareness note Storm**: System awareness note System awareness note System awareness note 3 System awareness note System awareness note 5 System awareness note →
 *    System awareness note System awareness note System awareness note System awareness note System awareness note.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        // System awareness note System awareness note (System awareness note System awareness note)
        private const val PHASE_1_DELAY_MS = 0L
        private const val PHASE_2_DELAY_MS = 8_000L

        // System awareness note System awareness note System awareness note System awareness note System awareness note
        private const val RAPID_REBOOT_THRESHOLD = 3
        private const val RAPID_REBOOT_WINDOW_MS = 5 * 60 * 1000L // 5 System awareness note
        private const val RAPID_REBOOT_PENALTY_DELAY_MS = 15_000L  // System awareness note 15 System awareness note

        private val recentBootTimes = mutableListOf<Long>()

        private val prefs_key_boot_count = "boot_count"
        private val prefs_key_last_boot = "last_boot_time"
        private val prefs_name = "omnidev_boot_prefs"
    }

    enum class BootType {
        COLD_BOOT,   // System awareness note System awareness note
        WARM_BOOT,   // System awareness note System awareness note
        UPDATE_BOOT, // System awareness note System awareness note
        QUICK_BOOT   // Fast Boot
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val bootType = detectBootType(action)
        val bootTime = System.currentTimeMillis()

        Log.i(TAG, "🚀 System awareness note System awareness note: $action | System awareness note: $bootType")

        // System awareness note System awareness note System awareness note
        DebugLogManager.appendInfo(TAG, buildString {
            append("System awareness note System awareness note: $bootType")
            append(" System awareness note ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(bootTime))}")
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
                // ── System awareness note System awareness note System awareness note System awareness note System awareness note ──
                val isRapidRebooting = checkRapidReboot(bootTime)
                if (isRapidRebooting) {
                    Log.w(TAG, "⚠️ System awareness note System awareness note — System awareness note System awareness note System awareness note ${RAPID_REBOOT_PENALTY_DELAY_MS}ms")
                    DebugLogManager.appendWarning(TAG, "System awareness note System awareness note System awareness note — System awareness note System awareness note")
                    delay(RAPID_REBOOT_PENALTY_DELAY_MS)
                }

                // ─────────────────────────────────────────────────────────────
                // System awareness note 1: System awareness note System awareness note (SyncService)
                // ─────────────────────────────────────────────────────────────
                delay(PHASE_1_DELAY_MS)
                Log.i(TAG, "📌 System awareness note 1: System awareness note System awareness note")
                val phase1Results = startPhase1Services(context, bootType)
                DebugLogManager.appendInfo(TAG, "System awareness note 1: ${phase1Results.joinToString(", ")}")

                // ─────────────────────────────────────────────────────────────
                // System awareness note 2: System awareness note System awareness note System awareness note
                // ─────────────────────────────────────────────────────────────
                delay(PHASE_2_DELAY_MS)
                Log.i(TAG, "📌 System awareness note 2: System awareness note System awareness note System awareness note")
                val healthReport = validateServiceHealth(context)
                DebugLogManager.appendInfo(TAG, "System awareness note System awareness note: $healthReport")

                Log.i(TAG, "✅ System awareness note System awareness note System awareness note — ${bootType.name}")

            } catch (e: Exception) {
                Log.e(TAG, "❌ System awareness note System awareness note System awareness note System awareness note: ${e.message}", e)
                DebugLogManager.appendError(TAG, e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * System awareness note 1: System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
     */
    private fun startPhase1Services(context: Context, bootType: BootType): List<String> {
        val results = mutableListOf<String>()

        // SyncService — System awareness note System awareness note
        safeStartForeground(context, OmniSyncService::class.java)
            .let { results.add(if (it) "✅ SyncService" else "❌ SyncService") }

        return results
    }

    /**
     * System awareness note 2: System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
     */
    private fun validateServiceHealth(context: Context): String = buildString {
        append("System awareness note System awareness note System awareness note:\n")

        // System awareness note System awareness note System awareness note System awareness note System awareness note SyncService state System awareness note System awareness note System awareness note
        val syncState = OmniSyncService.syncState.value
        append("  SyncService: ${syncState.name}\n")

        val circuitState = OmniSyncService.circuitState.value
        append("  Circuit Breaker: ${circuitState.name}\n")

        if (syncState == OmniSyncService.SyncState.ERROR) {
            append("  ⚠️ SyncService System awareness note System awareness note System awareness note — System awareness note System awareness note System awareness note")
        }
    }

    /**
     * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
     */
    private fun checkRapidReboot(bootTime: Long): Boolean {
        recentBootTimes.add(bootTime)
        // System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
        recentBootTimes.removeAll { bootTime - it > RAPID_REBOOT_WINDOW_MS }
        return recentBootTimes.size >= RAPID_REBOOT_THRESHOLD
    }

    /**
     * System awareness note System awareness note Foreground System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note Android.
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
            Log.i(TAG, "System awareness note: ${serviceClass.simpleName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "System awareness note System awareness note ${serviceClass.simpleName}: ${e.message}")
            false
        }
    }
}
