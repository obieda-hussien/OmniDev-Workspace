package com.omnidev.workspace.data.ipc

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.omnilink.sdk.ActionOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared periodic worker that drives recurring checks for all bound Omni-Link extensions.
 *
 * Avoids spawning multiple background services, respects Android 15 foreground timeouts,
 * and executes due extensions concurrently with individual timeouts.
 */
class ExtensionTickWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ExtensionTickWorker"

        // In-memory cache tracking the last successful tick timestamp per extension ID
        internal val lastTickTimestamps = ConcurrentHashMap<String, Long>()

        // Test-overridable tick timeout to keep unit tests fast and responsive
        internal var tickTimeoutMs = 30_000L
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting ExtensionTickWorker execution cycle")

        // 1. Gather all currently active extension handles that support periodic ticks
        val handles = ExtensionConnectionManager.handles.values.filter { handle ->
            val manifest = handle.manifest
            manifest != null && manifest.supportsTicks
        }

        if (handles.isEmpty()) {
            Log.i(TAG, "No active extensions support periodic ticks")
            return Result.success()
        }

        val currentTime = System.currentTimeMillis()
        val dueHandles = handles.filter { handle ->
            val manifest = handle.manifest!!
            // preferredTickIntervalSeconds is in seconds, fallback to default 900 (15 minutes)
            val preferredIntervalMs = (manifest.preferredTickIntervalSeconds.takeIf { it > 0 } ?: 900) * 1000L
            val lastTick = lastTickTimestamps[handle.id] ?: 0L
            (currentTime - lastTick) >= preferredIntervalMs
        }

        if (dueHandles.isEmpty()) {
            Log.i(TAG, "No extensions are currently due for a tick")
            return Result.success()
        }

        Log.i(TAG, "Dispatching concurrent ticks for ${dueHandles.size} extensions")

        // 2. Dispatch all ticks concurrently, each wrapped in its own timeout
        coroutineScope {
            dueHandles.map { handle ->
                async {
                    val timeoutMs = tickTimeoutMs
                    val success = withTimeoutOrNull(timeoutMs) {
                        try {
                            Log.d(TAG, "Executing _tick on extension: ${handle.id}")
                            val resultJson = ExtensionConnectionManager.executeAction(handle.id, "_tick", "{}")

                            val outcome = kotlinx.serialization.json.Json.decodeFromString<ActionOutcome>(
                                ActionOutcome.serializer(),
                                resultJson
                            )
                            if (outcome is ActionOutcome.Success) {
                                lastTickTimestamps[handle.id] = System.currentTimeMillis()
                                true
                            } else {
                                Log.w(TAG, "Extension ${handle.id} tick returned failure result")
                                false
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error executing tick on ${handle.id}: ${e.message}")
                            false
                        }
                    } ?: run {
                        Log.w(TAG, "Tick execution timed out for extension: ${handle.id}")
                        false
                    }
                }
            }.awaitAll()
        }

        Log.i(TAG, "Finished ExtensionTickWorker execution cycle")
        return Result.success()
    }
}
