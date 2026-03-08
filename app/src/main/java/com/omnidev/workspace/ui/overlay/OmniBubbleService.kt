package com.omnidev.workspace.ui.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.omnidev.workspace.ui.theme.OmniDevTheme

/**
 * System-wide floating AI assistant bubble that persists across all apps.
 *
 * ### Architecture
 * - Uses [WindowManager] with `TYPE_APPLICATION_OVERLAY` to attach a [ComposeView] directly
 *   to the system window layer, making it visible on top of any other app.
 * - Implements [LifecycleOwner], [ViewModelStoreOwner], and [SavedStateRegistryOwner] so the
 *   [ComposeView] functions correctly within the service lifecycle.
 * - The bubble starts as a small draggable circle. Tapping it expands to a mini chat panel.
 *
 * ### Permissions
 * Requires `android.permission.SYSTEM_ALERT_WINDOW`. Request it via
 * `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` before starting this service.
 *
 * ### Usage
 * ```kotlin
 * startService(Intent(context, OmniBubbleService::class.java))
 * ```
 * Send [ACTION_STOP] to dismiss the bubble:
 * ```kotlin
 * startService(Intent(context, OmniBubbleService::class.java).apply {
 *     action = OmniBubbleService.ACTION_STOP
 * })
 * ```
 */
class OmniBubbleService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        const val ACTION_STOP = "com.omnidev.workspace.OMNI_BUBBLE_STOP"

        /**
         * When the wake word is detected, start (or bring to front) the bubble,
         * auto-expand it, and show a personalised greeting from the assistant.
         */
        const val ACTION_WAKE = "com.omnidev.workspace.OMNI_BUBBLE_WAKE"

        /** Intent extras for [ACTION_WAKE]. */
        const val EXTRA_USER_NAME = "user_name"
        const val EXTRA_GREETING = "greeting"

        /**
         * Indicates whether an [OmniBubbleService] instance is currently active.
         * Updated in [onCreate] / [onDestroy]. Readable from UI without binding.
         */
        @Volatile var isRunning: Boolean = false
            private set

        /** Start (or bring to front) the Omni-Bubble from any context. */
        fun start(context: Context) {
            context.startService(Intent(context, OmniBubbleService::class.java))
        }

        /** Stop and dismiss the Omni-Bubble. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, OmniBubbleService::class.java).apply { action = ACTION_STOP }
            )
        }

        /**
         * Start (or bring to front) the bubble in wake mode: auto-expanded with a greeting
         * message already injected so it looks like the assistant woke up and said hello.
         */
        fun startWithGreeting(context: Context, userName: String, greeting: String) {
            context.startService(
                Intent(context, OmniBubbleService::class.java).apply {
                    action = ACTION_WAKE
                    putExtra(EXTRA_USER_NAME, userName)
                    putExtra(EXTRA_GREETING, greeting)
                }
            )
        }
    }

    // ── Lifecycle, ViewModel, SavedState owners required by ComposeView ──

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val _viewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // ── WindowManager state ──

    private lateinit var windowManager: WindowManager
    private var bubbleView: ComposeView? = null

    /** Current window layout params — mutated when the user drags the bubble. */
    private lateinit var layoutParams: WindowManager.LayoutParams

    // ── Bubble UI state shared between Compose recompositions ──

    private val bubbleExpanded = mutableStateOf(false)
    private val messages = mutableStateListOf<Pair<Boolean, String>>() // (isUser, text)

    // ──────────────────────────────────────────────────────────────────────────────
    //  Service lifecycle
    // ──────────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        attachBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_WAKE -> {
                // Wake word triggered — auto-expand and inject the greeting (deduplicated)
                val greeting = intent.getStringExtra(EXTRA_GREETING)
                if (!greeting.isNullOrBlank() && messages.lastOrNull()?.second != greeting) {
                    messages.add(false to greeting)
                }
                bubbleExpanded.value = true
                // Re-enable focus so the input field is immediately usable
                layoutParams.flags = layoutParams.flags and
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                bubbleView?.let { windowManager.updateViewLayout(it, layoutParams) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        bubbleView?.let { windowManager.removeView(it) }
        bubbleView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────────────────────────────────
    //  WindowManager attachment
    // ──────────────────────────────────────────────────────────────────────────────

    private fun attachBubble() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 200
        }

        val view = ComposeView(this).also { bubbleView = it }

        // Wire lifecycle owners so Compose works inside a Service
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)

        view.setContent {
            OmniDevTheme {
                OmniBubbleContent(
                    expanded = bubbleExpanded.value,
                    messages = messages,
                    onToggleExpand = { bubbleExpanded.value = !bubbleExpanded.value },
                    onSendMessage = { text -> handleUserMessage(text) },
                    onDismiss = { stop(this@OmniBubbleService) }
                )
            }
        }

        // Pass touch events through to the WindowManager for dragging when collapsed.
        // AccessibilityDelegate is not needed here — the bubble has a tap-to-expand affordance
        // handled by ACTION_UP in handleDragTouch().
        @Suppress("ClickableViewAccessibility")
        view.setOnTouchListener { _, event ->
            if (!bubbleExpanded.value) {
                handleDragTouch(event)
                true
            } else {
                false
            }
        }

        windowManager.addView(view, layoutParams)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    // ──────────────────────────────────────────────────────────────────────────────
    //  Drag handling
    // ──────────────────────────────────────────────────────────────────────────────

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialParamX = 0
    private var initialParamY = 0

    private fun handleDragTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialParamX = layoutParams.x
                initialParamY = layoutParams.y
            }
            MotionEvent.ACTION_MOVE -> {
                layoutParams.x = initialParamX + (event.rawX - initialTouchX).toInt()
                layoutParams.y = initialParamY + (event.rawY - initialTouchY).toInt()
                bubbleView?.let { windowManager.updateViewLayout(it, layoutParams) }
            }
            MotionEvent.ACTION_UP -> {
                // If the drag distance is small, treat it as a tap (expand)
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (dx * dx + dy * dy < 25f * 25f) {
                    bubbleExpanded.value = true
                    // Re-enable focus so the expanded panel can receive text input
                    layoutParams.flags = layoutParams.flags and
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    bubbleView?.let { windowManager.updateViewLayout(it, layoutParams) }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    //  Message handling (placeholder — no pipeline wired in the overlay)
    // ──────────────────────────────────────────────────────────────────────────────

    private fun handleUserMessage(text: String) {
        if (text.isBlank()) return
        messages.add(true to text)
        // Placeholder response — the full pipeline runs in MainActivity.
        // Open the app for the complete Agent/Swarm experience.
        messages.add(false to "⚡ استلمت رسالتك: \"$text\"\n\nافتح التطبيق للاستخدام الكامل.")
    }
}

// ──────────────────────────────────────────────────────────────────────────────
//  Compose UI
// ──────────────────────────────────────────────────────────────────────────────

/**
 * The full Compose UI for the Omni-Bubble overlay.
 *
 * In **collapsed** mode this renders a single draggable circle with the OmniDev logo.
 * In **expanded** mode it becomes a mini chat panel with a message list and input field.
 */
@Composable
private fun OmniBubbleContent(
    expanded: Boolean,
    messages: List<Pair<Boolean, String>>,
    onToggleExpand: () -> Unit,
    onSendMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!expanded) {
        // ── Collapsed bubble ──
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = "OmniDev Bubble",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
    } else {
        // ── Expanded mini-chat panel ──
        var inputText by remember { mutableStateOf("") }

        Surface(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 360.dp)
                .shadow(16.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // ── Header ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "OmniDev — أنا هنا 👋",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row {
                        // Minimise
                        IconButton(onClick = onToggleExpand, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Minimise",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        // Dismiss entirely
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── Message list ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (messages.isEmpty()) {
                        Text(
                            text = "قولي عايز إيه…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                    messages.forEach { (isUser, text) ->
                        BubbleChatMessage(isUser = isUser, text = text)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── Input row ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("Message…", style = MaterialTheme.typography.bodySmall)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            onSendMessage(inputText)
                            inputText = ""
                        })
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            onSendMessage(inputText)
                            inputText = ""
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleChatMessage(isUser: Boolean, text: String) {
    val bgColor = if (isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    val align = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp, topEnd = 12.dp,
                        bottomStart = if (isUser) 12.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 12.dp
                    )
                )
                .background(bgColor)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .widthIn(max = 260.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
