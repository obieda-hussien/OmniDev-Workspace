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
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Optional, user-started overlay; no audio recording or hidden capture while minimized. */
class AssistantBubbleService : Service() {
    private var bubble: View? = null
    private val manager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val owners = mutableSetOf<String>()
    private var generation = -1
    private val privacyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updateLockVisibility() }
    }
    private val consentListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateLockVisibility() }
    private val consentPrefs by lazy { getSharedPreferences("device-user-consent", Context.MODE_PRIVATE) }
    override fun onCreate() {
        super.onCreate(); active = this
        androidx.core.content.ContextCompat.registerReceiver(this, privacyReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        consentPrefs.registerOnSharedPreferenceChangeListener(consentListener)
    }
    private fun updateLockVisibility() {
        val consent = com.omnidev.workspace.data.admin.DeviceConsentStore(this)
        bubble?.visibility = if (consent.locked() && !consent.enabled(com.omnidev.workspace.data.admin.DeviceConsentPolicy.Scope.LOCK_OVERLAY)) View.GONE else View.VISIBLE
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CLOSE) { AssistantRuntime.close(this); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == RESUME) { OmniVoiceInteractionService.resume(this); stopSelf(); return START_NOT_STICKY }
        val id = intent?.getStringExtra(REQUEST)
        val request = id?.let { pending[it] }
        if (id == null || request == null || request.generation != AssistantRuntime.get(this).sessionGeneration) {
            request?.result?.complete(false)
            if (bubble == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        owners += id
        if (!AssistantFlavorPolicy(com.omnidev.workspace.core.policy.TierPolicyHolder.current).allowBubble || !Settings.canDrawOverlays(this)) {
            signal(false); stopSelf(); return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Floating assistant", NotificationManager.IMPORTANCE_LOW))
            fun action(value: String) = PendingIntent.getService(this, value.hashCode(), Intent(this, AssistantBubbleService::class.java).setAction(value), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startForeground(9183, NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("OmniDev assistant")
                .setContentText("Tap the bubble to continue. Long press it to close.").setOngoing(true)
                .setContentIntent(action(RESUME)).addAction(0, "Close", action(CLOSE)).build())
            generation = request.generation
            if (bubble == null) addBubble()
            // addView can return before ViewRoot attaches; its listener completes pending starts.
            if (readyFor(request.generation)) signal(true)
        } catch (error: Exception) {
            signal(false); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun readyFor(epoch: Int): Boolean = generation == epoch &&
        bubble?.isAttachedToWindow == true && bubble?.windowToken != null && Settings.canDrawOverlays(this)
    private fun signal(attached: Boolean) {
        owners.toList().forEach { id -> pending[id]?.let { request ->
            request.result.complete(attached && request.generation == generation &&
                request.generation == AssistantRuntime.get(this).sessionGeneration && readyFor(request.generation))
        } }
    }
    private fun discard(id: String) {
        if (owners.remove(id) && owners.isEmpty()) { removeBubble(); stopSelf() }
    }
    private fun removeBubble() {
        val view = bubble
        bubble = null
        view?.let { runCatching { manager.removeView(it) } }
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
                        runCatching { manager.updateViewLayout(view, params) }.onFailure {
                            signal(false); stopSelf()
                            runCatching { OmniVoiceInteractionService.resume(this) }
                        }
                    }
                }
                MotionEvent.ACTION_UP -> { view.removeCallbacks(longPress); if (!moved && !longPressed) view.performClick() }
                MotionEvent.ACTION_CANCEL -> view.removeCallbacks(longPress)
            }
            true
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attached: View) {
                if (bubble === attached && readyFor(generation)) signal(true)
                else { signal(false); stopSelf() }
            }
            override fun onViewDetachedFromWindow(detached: View) { signal(false) }
        })
        bubble = view
        manager.addView(view, params)
        updateLockVisibility()
    }
    override fun onDestroy() {
        unregisterReceiver(privacyReceiver)
        consentPrefs.unregisterOnSharedPreferenceChangeListener(consentListener)
        signal(false)
        removeBubble()
        owners.clear()
        if (active === this) active = null
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "assistant_bubble"
        private const val CLOSE = "assistant_bubble_close"
        private const val RESUME = "assistant_bubble_resume"
        private const val REQUEST = "assistant_bubble_request"
        private data class StartRequest(val generation: Int, val result: CompletableDeferred<Boolean>)
        // Service and native/Activity hosts share the main thread and default app process.
        private val pending = mutableMapOf<String, StartRequest>()
        private var active: AssistantBubbleService? = null
        fun isReady(context: Context): Boolean = active?.readyFor(AssistantRuntime.get(context).sessionGeneration) == true

        suspend fun show(context: Context): Boolean = withContext(Dispatchers.Main.immediate) {
            val controller = AssistantRuntime.get(context)
            if (!controller.flavor.allowBubble || controller.state.value.minimizing) return@withContext false
            val epoch = controller.sessionGeneration
            val id = UUID.randomUUID().toString()
            val request = StartRequest(epoch, CompletableDeferred())
            var attached = false
            pending[id] = request
            controller.minimizing(true)
            try {
                attached = awaitBubbleAttachment(request.result) {
                    Settings.canDrawOverlays(context) && ContextCompat.startForegroundService(context,
                        Intent(context, AssistantBubbleService::class.java).putExtra(REQUEST, id)) != null
                } && epoch == controller.sessionGeneration && isReady(context)
                if (!attached && epoch == controller.sessionGeneration)
                    controller.message("The floating bubble could not attach. Keep the assistant open and try again.")
                attached
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (epoch == controller.sessionGeneration) controller.message("The floating bubble could not attach. Try again.")
                false
            } finally {
                pending.remove(id)
                if (!attached) active?.discard(id)
                if (epoch == controller.sessionGeneration) controller.minimizing(false)
            }
        }
        fun remove(context: Context) { context.stopService(Intent(context, AssistantBubbleService::class.java)) }
    }
}
