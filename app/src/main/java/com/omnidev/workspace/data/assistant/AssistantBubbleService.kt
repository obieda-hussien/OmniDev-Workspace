package com.omnidev.workspace.data.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.omnidev.workspace.R
import kotlin.math.abs

/** Optional, user-started overlay; no audio recording or hidden capture while minimized. */
class AssistantBubbleService : Service() {
    private var bubble: View? = null
    private val manager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CLOSE) { AssistantRuntime.close(this); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == RESUME) { OmniVoiceInteractionService.resume(this); stopSelf(); return START_NOT_STICKY }
        if (!AssistantFlavorPolicy(com.omnidev.workspace.core.policy.TierPolicyHolder.current).allowBubble || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT >= 26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Floating assistant", NotificationManager.IMPORTANCE_LOW))
        fun action(value: String) = PendingIntent.getService(this, value.hashCode(), Intent(this, AssistantBubbleService::class.java).setAction(value), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(9183, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("OmniDev assistant")
            .setContentText("Tap the bubble to continue. Long press it to close.").setOngoing(true)
            .setContentIntent(action(RESUME)).addAction(0, "Close", action(CLOSE)).build())
        if (bubble == null) runCatching { addBubble() }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION", "ClickableViewAccessibility")
    private fun addBubble() {
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val params = WindowManager.LayoutParams(size, size,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = resources.displayMetrics.widthPixels - size - (12 * density).toInt()
            y = (160 * density).toInt()
        }
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFDDD6FE.toInt()) }
            elevation = 8 * density
            contentDescription = "Open OmniDev assistant"
            setOnClickListener { OmniVoiceInteractionService.resume(this@AssistantBubbleService); stopSelf() }
            setOnLongClickListener { AssistantRuntime.close(this@AssistantBubbleService); stopSelf(); true }
        }
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f; var moved = false; var longPressed = false
        val longPress = Runnable { longPressed = true; view.performLongClick() }
        view.setOnTouchListener { _, event ->
            if (bubble !== view) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; downX = event.rawX; downY = event.rawY; moved = false; longPressed = false; view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()) }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > 8 * density) { moved = true; view.removeCallbacks(longPress) }
                    if (moved) {
                        params.x = (startX + dx.toInt()).coerceIn(0, (resources.displayMetrics.widthPixels - size).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (resources.displayMetrics.heightPixels - size).coerceAtLeast(0))
                        manager.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> { view.removeCallbacks(longPress); if (!moved && !longPressed) view.performClick() }
                MotionEvent.ACTION_CANCEL -> view.removeCallbacks(longPress)
            }
            true
        }
        manager.addView(view, params); bubble = view
    }
    override fun onDestroy() { bubble?.let { runCatching { manager.removeView(it) } }; bubble = null; super.onDestroy() }
    companion object {
        private const val CHANNEL = "assistant_bubble"
        private const val CLOSE = "assistant_bubble_close"
        private const val RESUME = "assistant_bubble_resume"
        fun show(context: Context) {
            if (AssistantFlavorPolicy(com.omnidev.workspace.core.policy.TierPolicyHolder.current).allowBubble)
                ContextCompat.startForegroundService(context, Intent(context, AssistantBubbleService::class.java))
        }
        fun remove(context: Context) { context.stopService(Intent(context, AssistantBubbleService::class.java)) }
    }
}
