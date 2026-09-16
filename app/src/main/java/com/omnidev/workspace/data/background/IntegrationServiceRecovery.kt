package com.omnidev.workspace.data.background

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.omnidev.workspace.data.integration.DiscordPollingService
import com.omnidev.workspace.data.integration.WhatsAppBridgeService
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restores integration listeners whose enabled state is already persisted in DataStore.
 *
 * Telegram is deliberately not inferred from the presence of a bot token. Its current UI toggle
 * is process-local, so automatically treating "token exists" as "listener desired" would violate
 * an explicit user stop. Once Telegram's toggle is made durable it can join this registry safely.
 */
object IntegrationServiceRecovery {
    private const val TAG = "IntegrationRecovery"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget entry point for non-suspending lifecycle callbacks. */
    fun kick(context: Context, reason: String) {
        val app = context.applicationContext
        scope.launch { recoverEnabledNow(app, reason) }
    }

    /** Awaitable entry point used by WorkManager so the process stays alive until starts are sent. */
    suspend fun recoverEnabledNow(context: Context, reason: String) {
        val app = context.applicationContext
        val settings = SettingsRepository(app)
        val discordEnabled = runCatching {
            settings.observeDiscordListenerEnabled().first()
        }.getOrDefault(false)
        val whatsAppEnabled = runCatching {
            settings.observeWhatsAppBridgeEnabled().first()
        }.getOrDefault(false)

        if (discordEnabled && !DiscordPollingService.isRunning) {
            startServiceSafely(app, DiscordPollingService::class.java, "Discord", reason)
        }
        if (whatsAppEnabled && !WhatsAppBridgeService.isRunning) {
            startServiceSafely(app, WhatsAppBridgeService::class.java, "WhatsApp", reason)
        }
    }

    private fun startServiceSafely(
        context: Context,
        serviceClass: Class<out android.app.Service>,
        label: String,
        reason: String
    ) {
        runCatching {
            val intent = Intent(context, serviceClass).apply {
                putExtra("recovery_reason", reason)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "$label listener recovery requested: $reason")
        }.onFailure {
            BackgroundServiceSupervisor.recordFailure(
                context,
                "$label listener recovery failed: ${it.javaClass.simpleName}: ${it.message}"
            )
        }
    }
}
