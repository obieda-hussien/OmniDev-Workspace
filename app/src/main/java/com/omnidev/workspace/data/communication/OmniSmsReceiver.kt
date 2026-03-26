package com.omnidev.workspace.data.communication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real-time SMS capture receiver.
 *
 * Listens for [Telephony.Sms.Intents.SMS_RECEIVED_ACTION] and stores incoming
 * messages in a bounded, thread-safe in-memory buffer — similar to
 * [com.omnidev.workspace.data.tools.NotificationCaptureTool].
 *
 * The agent can read captured SMS via [SmsCaptureBuffer.getRecent].
 *
 * This receiver is declared with `android:priority="999"` in the manifest so it
 * processes messages before other receivers but does NOT abort the broadcast
 * (the default SMS app still receives and stores the message).
 */
class OmniSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OmniSmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            for (sms in messages) {
                val sender = sms.originatingAddress ?: "unknown"
                val body = sms.messageBody ?: ""
                val timestamp = sms.timestampMillis

                Log.d(TAG, "SMS received from $sender (${body.length} chars)")

                SmsCaptureBuffer.onSmsReceived(
                    sender = sender,
                    body = body,
                    timestamp = timestamp
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming SMS", e)
        }
    }
}

/**
 * Thread-safe in-memory buffer for incoming SMS messages.
 *
 * Capped at [MAX_CAPTURED] entries. Oldest messages are evicted when the
 * buffer is full. The agent queries this buffer via the `read_incoming_sms` tool.
 */
object SmsCaptureBuffer {

    private const val MAX_CAPTURED = 50

    data class CapturedSms(
        val sender: String,
        val body: String,
        val timestamp: Long
    )

    private val buffer = CopyOnWriteArrayList<CapturedSms>()

    /** Called by [OmniSmsReceiver] when a new SMS arrives. */
    fun onSmsReceived(sender: String, body: String, timestamp: Long) {
        buffer.add(CapturedSms(sender, body, timestamp))

        val excess = buffer.size - MAX_CAPTURED
        if (excess > 0) {
            val toRemove = buffer.take(excess)
            buffer.removeAll(toRemove.toSet())
        }
    }

    /** Returns the most recent [limit] captured SMS messages. */
    fun getRecent(limit: Int = 20): List<CapturedSms> =
        buffer.takeLast(limit.coerceIn(1, MAX_CAPTURED))

    /** Returns messages from a specific sender (substring match). */
    fun getFromSender(senderQuery: String, limit: Int = 20): List<CapturedSms> =
        buffer.filter { it.sender.contains(senderQuery, ignoreCase = true) }
            .takeLast(limit.coerceIn(1, MAX_CAPTURED))

    /** Clears all captured SMS. */
    fun clear() = buffer.clear()

    /** Current buffer size. */
    fun count(): Int = buffer.size
}
