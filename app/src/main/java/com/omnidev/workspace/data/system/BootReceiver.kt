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
 * BootReceiver — Context note Context note Context note
 *
 * Context note Context note: Context note Context note Context note
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **Context note Context note Context note (Boot Type Detection)**:
 *    - COLD_BOOT: Context note Context note Context note Context note Context note
 *    - WARM_BOOT: Context note Context note Context note
 *    - UPDATE_BOOT: Context note/Context note Context note
 *    - QUICK_BOOT: Fast Boot (Qualcomm/HTC)
 *    Context note Context note Context note Context note Context note Context note.
 *
 * 2. **Context note Context note (Phased Startup)**:
 *    Context note 1 (Context note): SyncService — Context note Context note Context note Context note
 *    Context note 2 (+8s): Context note Context note Context note Context note Context note Context note Context note
 *    Context note Context note Context note Context note Context note Context note Context note Context note Context note Context note Context note Context note Context note.
 *
 * 3. **Context note Context note (Boot Log)**:
 *    Context note Context note Context note Context note: Context note Context note Context note Context note Context note Context note.
 *    Context note Context note Context note Context note Context note ANR.
 *
 * 4. **Context note Context note Context note Storm**: Context note Context note Context note 3 Context note Context note 5 Context note →
 *    Context note Context note Context note Context note Context note.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        // Context note Context note (Context note Context note)
        private const val PHASE_1_DELAY_MS = 0L
        private const val PHASE_2_DELAY_MS = 8_000L

        // Context note Context note Context note Context note Context note
        private const val RAPID_REBOOT_THRESHOLD = 3
        private const val RAPID_REBOOT_WINDOW_MS = 5 * 60 * 1000L // 5 Context note
        private const val RAPID_REBOOT_PENALTY_DELAY_MS = 15_000L  // Context note 15 Context note

        private val recentBootTimes = mutableListOf<Long>()

        private val prefs_key_boot_count = "boot_count"
        private val prefs_key_last_boot = "last_boot_time"
        private val prefs_name = "omnidev_boot_prefs"
    }

    enum class BootType {
        COLD_BOOT,   // Context note Context note
        WARM_BOOT,   // Context note Context note
        UPDATE_BOOT, // Context note Context note
        QUICK_BOOT   // Fast Boot
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val bootType = detectBootType(action)
        val bootTime = System.currentTimeMillis()

        Log.i(TAG, "🚀 Info Info: $action | Info: $bootType")

        // Context note Context note Context note
        DebugLogManager.appendInfo(TAG, buildString {
            append("Info Info: $bootType")
            append(" Info ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(bootTime))}")
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
                // ── Context note Context note Context note Context note Context note ──
                val isRapidRebooting = checkRapidReboot(bootTime)
                if (isRapidRebooting) {
                    Log.w(TAG, "⚠️ Info Info — Info Info Info ${RAPID_REBOOT_PENALTY_DELAY_MS}ms")
                    DebugLogManager.appendWarning(TAG, "Info Info Info — Info Info")
                    delay(RAPID_REBOOT_PENALTY_DELAY_MS)
                }

                // ─────────────────────────────────────────────────────────────
                // Context note 1: Context note Context note (SyncService)
                // ─────────────────────────────────────────────────────────────
                delay(PHASE_1_DELAY_MS)
                Log.i(TAG, "📌 Info 1: Info Info")
                val phase1Results = startPhase1Services(context, bootType)
                DebugLogManager.appendInfo(TAG, "Info 1: ${phase1Results.joinToString(", ")}")

                // ─────────────────────────────────────────────────────────────
                // Context note 2: Context note Context note Context note
                // ─────────────────────────────────────────────────────────────
                delay(PHASE_2_DELAY_MS)
                Log.i(TAG, "📌 Info 2: Info Info Info")
                val healthReport = validateServiceHealth(context)
                DebugLogManager.appendInfo(TAG, "Info Info: $healthReport")

                Log.i(TAG, "✅ Info Info Info — ${bootType.name}")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Info Info Info Info: ${e.message}", e)
                DebugLogManager.appendError(TAG, e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Context note 1: Context note Context note Context note Context note Context note Context note Context note.
     */
    private fun startPhase1Services(context: Context, bootType: BootType): List<String> {
        val results = mutableListOf<String>()

        // SyncService — Context note Context note
        safeStartForeground(context, OmniSyncService::class.java)
            .let { results.add(if (it) "✅ SyncService" else "❌ SyncService") }

        return results
    }

    /**
     * Context note 2: Context note Context note Context note Context note Context note Context note.
     */
    private fun validateServiceHealth(context: Context): String = buildString {
        append("Info Info Info:\n")

        // Context note Context note Context note Context note Context note SyncService state Context note Context note Context note
        val syncState = OmniSyncService.syncState.value
        append("  SyncService: ${syncState.name}\n")

        val circuitState = OmniSyncService.circuitState.value
        append("  Circuit Breaker: ${circuitState.name}\n")

        if (syncState == OmniSyncService.SyncState.ERROR) {
            append("  ⚠️ SyncService Info Info Info — Info Info Info")
        }
    }

    /**
     * Context note Context note Context note Context note Context note Context note Context note Context note Context note Context note.
     */
    private fun checkRapidReboot(bootTime: Long): Boolean {
        recentBootTimes.add(bootTime)
        // Context note Context note Context note Context note Context note Context note
        recentBootTimes.removeAll { bootTime - it > RAPID_REBOOT_WINDOW_MS }
        return recentBootTimes.size >= RAPID_REBOOT_THRESHOLD
    }

    /**
     * Context note Context note Foreground Context note Context note Context note Context note Context note Context note Android.
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
            Log.i(TAG, "Info: ${serviceClass.simpleName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Info Info ${serviceClass.simpleName}: ${e.message}")
            false
        }
    }
}
