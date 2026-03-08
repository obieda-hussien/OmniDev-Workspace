package com.omnidev.workspace.ui.overlay

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.network.CompletionService
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.domain.engine.IntentClassifier
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.registry.ModelRegistry
import com.omnidev.workspace.ui.theme.OmniDevTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    /** Persistent conversation messages shown in the bubble. */
    private val messages = mutableStateListOf<BubbleMessage>()

    /** Accumulates in-progress streaming tokens before the message is committed. */
    private val streamingText = mutableStateOf<String?>(null)

    /** Detected intent mode for the current/last request. */
    private val detectedMode = mutableStateOf<OmniMode?>(null)

    /** True while an LLM call is in-flight. */
    private val isProcessing = mutableStateOf(false)

    /** Counts unread AI replies accumulated while the bubble is collapsed. */
    private val unreadCount = mutableIntStateOf(0)

    // ── Execution dependencies (lazy — only created when first message is sent) ──

    /** Coroutine scope for async LLM calls. Cancelled in [onDestroy]. */
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Active streaming/completion job so it can be cancelled by the user. */
    private var currentJob: Job? = null

    /** Reads model IDs, custom prompts, and user persona. */
    private val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(applicationContext)
    }

    /** Provides stored API keys for each provider. */
    private val apiKeyRepository: ApiKeyRepository by lazy {
        ApiKeyRepository(applicationContext)
    }

    /** Routes [CompletionRequest]s to the appropriate LLM provider. */
    private val completionService: CompletionService by lazy { CompletionService() }

    /** Conversation history for the current bubble session. */
    private val conversationHistory = mutableListOf<ChatMessage>()

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
                if (!greeting.isNullOrBlank() && messages.lastOrNull()?.text != greeting) {
                    messages.add(BubbleMessage(isUser = false, text = greeting))
                    unreadCount.intValue = 0
                }
                bubbleExpanded.value = true
                unreadCount.intValue = 0
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
        serviceScope.cancel()
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
                    streamingText = streamingText.value,
                    detectedMode = detectedMode.value,
                    isProcessing = isProcessing.value,
                    unreadCount = unreadCount.intValue,
                    onToggleExpand = {
                        bubbleExpanded.value = !bubbleExpanded.value
                        if (bubbleExpanded.value) unreadCount.intValue = 0
                    },
                    onSendMessage = { text -> handleUserMessage(text) },
                    onStopGeneration = {
                        currentJob?.cancel()
                        currentJob = null
                        streamingText.value?.let { partial ->
                            if (partial.isNotBlank()) {
                                messages.add(BubbleMessage(
                                    isUser = false,
                                    text = partial,
                                    mode = detectedMode.value
                                ))
                            }
                        }
                        streamingText.value = null
                        isProcessing.value = false
                    },
                    onClearChat = {
                        messages.clear()
                        conversationHistory.clear()
                        streamingText.value = null
                        unreadCount.intValue = 0
                    },
                    onOpenApp = {
                        val intent = Intent(this@OmniBubbleService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                    },
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
    //  Intent-based message handling — streaming AUTO routing with real LLM execution
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Processes a user message from the floating overlay using intent-based AUTO routing.
     * Streams the response token-by-token for a ChatGPT-like experience.
     *
     * - **CHAT intent** → Streaming completion with concise conversational system prompt
     * - **AGENT intent** → Streaming completion with God-Protocol agent system prompt
     * - **SWARM intent** → Static reply prompting user to open the full app
     */
    private fun handleUserMessage(text: String) {
        if (text.isBlank() || isProcessing.value) return

        messages.add(BubbleMessage(isUser = true, text = text))
        conversationHistory.add(ChatMessage(role = MessageRole.USER, content = text))
        isProcessing.value = true
        streamingText.value = ""

        currentJob = serviceScope.launch {
            try {
                // Classify intent to choose the right system prompt and execution strategy
                val intent = IntentClassifier.classify(text)
                detectedMode.value = intent

                // For very complex multi-agent tasks, prompt to open the full app instead
                if (intent == OmniMode.SWARM) {
                    val reply = "🧠 يبدو ده مشروع كبير يحتاج تنسيق بين عدة وكلاء.\n" +
                        "افتح التطبيق للاستخدام الكامل مع وضع Team Agents."
                    streamingText.value = null
                    messages.add(BubbleMessage(isUser = false, text = reply, mode = intent))
                    conversationHistory.add(ChatMessage(role = MessageRole.ASSISTANT, content = reply))
                    if (!bubbleExpanded.value) unreadCount.intValue++
                    isProcessing.value = false
                    return@launch
                }

                // Pick model and system prompt based on intent
                val modelRole = if (intent == OmniMode.AGENT)
                    com.omnidev.workspace.data.model.ModelRole.AGENT
                else
                    com.omnidev.workspace.data.model.ModelRole.CHAT

                val modelId = settingsRepository
                    .observeModelIdForRole(modelRole)
                    .first()

                val systemPrompt = when (intent) {
                    OmniMode.AGENT ->
                        "You are an Autonomous Operator running inside a floating overlay. " +
                        "Be DIRECT, CONCISE, and ACTION-ORIENTED. Think step-by-step but respond briefly. " +
                        "Do not use filler phrases like 'Great question!' or 'I hope this helps'. " +
                        "Just solve the problem. If the task requires reading or editing files on disk, " +
                        "tell the user to open the full app."
                    else ->
                        "You are a smart, concise assistant. Answer directly and clearly. " +
                        "The user is in a floating overlay — keep answers brief and scannable. " +
                        "No filler phrases. Just useful information."
                }

                // Inject user persona if available
                val userPersona = settingsRepository.observeUserPersona().first()
                val effectiveSystemPrompt = if (!userPersona.isNullOrBlank())
                    "$systemPrompt\n\n## User Context\n$userPersona"
                else systemPrompt

                val model = ModelRegistry.findModelById(modelId)
                val apiKey = model?.let { apiKeyRepository.getApiKey(it.provider) }

                val request = CompletionRequest(
                    modelId = modelId,
                    messages = conversationHistory.toList(),
                    systemPrompt = effectiveSystemPrompt,
                    maxTokens = 1024,
                    temperature = 0.7,
                    apiKey = apiKey
                )

                // Stream the response token-by-token
                val accumulated = StringBuilder()
                completionService.stream(request) { chunk ->
                    accumulated.append(chunk)
                    streamingText.value = accumulated.toString()
                }

                val finalReply = accumulated.toString().trim()
                streamingText.value = null
                messages.add(BubbleMessage(isUser = false, text = finalReply, mode = intent))
                conversationHistory.add(ChatMessage(role = MessageRole.ASSISTANT, content = finalReply))
                if (!bubbleExpanded.value) unreadCount.intValue++
            } catch (e: Exception) {
                streamingText.value = null
                val errorMsg = "⚠️ حصل خطأ: ${e.message?.take(120) ?: "خطأ غير معروف"}"
                messages.add(BubbleMessage(isUser = false, text = errorMsg))
            } finally {
                isProcessing.value = false
                currentJob = null
            }
        }
    }

    /**
     * Delegates to [IntentClassifier.classify] for consistent intent scoring with ChatViewModel.
     */
    private fun classifyIntent(input: String): OmniMode = IntentClassifier.classify(input)
}

// ──────────────────────────────────────────────────────────────────────────────
//  Data model
// ──────────────────────────────────────────────────────────────────────────────

/**
 * A single message in the bubble conversation.
 *
 * @param isUser True if the message was typed by the user.
 * @param text   The display text content.
 * @param mode   The intent mode auto-detected for this message (null for user messages).
 */
private data class BubbleMessage(
    val isUser: Boolean,
    val text: String,
    val mode: OmniMode? = null
)

// ──────────────────────────────────────────────────────────────────────────────
//  Compose UI — collapsed bubble + expanded panel
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Top-level Compose UI for the Omni-Bubble overlay.
 *
 * **Collapsed** — draggable 60 dp gradient circle with an unread-message badge.
 * **Expanded** — modern floating chat panel (360 dp wide) with streaming responses,
 * auto-scroll, animated typing indicator, and mode badges on AI messages.
 */
@Composable
private fun OmniBubbleContent(
    expanded: Boolean,
    messages: List<BubbleMessage>,
    streamingText: String?,
    detectedMode: OmniMode?,
    isProcessing: Boolean,
    unreadCount: Int,
    onToggleExpand: () -> Unit,
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onClearChat: () -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!expanded) {
        // ── Collapsed bubble with unread badge ──
        BadgedBox(
            badge = {
                if (unreadCount > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.error
                    ) {
                        Text(
                            text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError
                        )
                    }
                }
            }
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .shadow(10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF6750A4),
                                Color(0xFF4A3780)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = "OmniDev Bubble",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    } else {
        // ── Expanded chat panel ──
        var inputText by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        // Auto-scroll to bottom when new messages arrive or streaming updates
        val totalItems = messages.size + if (streamingText != null) 1 else 0
        LaunchedEffect(totalItems, streamingText) {
            if (totalItems > 0) {
                listState.animateScrollToItem(totalItems - 1)
            }
        }

        Surface(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 380.dp)
                .shadow(20.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column {
                // ── Header ──
                BubbleHeader(
                    isProcessing = isProcessing,
                    detectedMode = detectedMode,
                    hasMessages = messages.isNotEmpty(),
                    onMinimise = onToggleExpand,
                    onClearChat = onClearChat,
                    onOpenApp = onOpenApp,
                    onDismiss = onDismiss
                )

                // ── Message list ──
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    if (messages.isEmpty() && streamingText == null && !isProcessing) {
                        item { BubbleWelcomeHint() }
                        item {
                            QuickActionChips(
                                onAction = onSendMessage
                            )
                        }
                    }

                    itemsIndexed(messages) { _, msg ->
                        BubbleChatMessage(
                            message = msg,
                            onLongClick = { text ->
                                // Copy to clipboard handled inside the composable
                            }
                        )
                    }

                    // Streaming message (live token accumulation)
                    if (streamingText != null) {
                        item {
                            BubbleChatMessage(
                                message = BubbleMessage(
                                    isUser = false,
                                    text = streamingText,
                                    mode = detectedMode
                                ),
                                isStreaming = true,
                                onLongClick = {}
                            )
                        }
                    }

                    // Animated typing dots while waiting for first token
                    if (isProcessing && streamingText.isNullOrEmpty()) {
                        item { TypingDotsIndicator() }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // ── Input row ──
                BubbleInputRow(
                    inputText = inputText,
                    isProcessing = isProcessing,
                    onInputChange = { inputText = it },
                    onSend = {
                        if (inputText.isNotBlank()) {
                            onSendMessage(inputText)
                            inputText = ""
                        }
                    },
                    onStop = onStopGeneration
                )

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ── Header composable ──

@Composable
private fun BubbleHeader(
    isProcessing: Boolean,
    detectedMode: OmniMode?,
    hasMessages: Boolean,
    onMinimise: () -> Unit,
    onClearChat: () -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color(0xFF6750A4), Color(0xFF4A3780))
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: icon + title + mode badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        text = "OmniDev",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    if (isProcessing && detectedMode != null) {
                        val modeLabel = when (detectedMode) {
                            OmniMode.AGENT -> "🤖 Agent"
                            OmniMode.SWARM -> "🌐 Swarm"
                            else -> "💬 Chat"
                        }
                        Text(
                            text = modeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    } else if (!isProcessing) {
                        Text(
                            text = "أنا هنا 👋",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            // Right side: action buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Open in full app
                IconButton(onClick = onOpenApp, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.OpenInFull,
                        contentDescription = "Open in app",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                }
                // Clear chat (only show if there are messages)
                if (hasMessages) {
                    IconButton(onClick = onClearChat, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = "Clear chat",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                // Minimise
                IconButton(onClick = onMinimise, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Minimise",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                }
                // Dismiss entirely
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Text(
                        "✕",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ── Welcome hint + quick actions ──

@Composable
private fun BubbleWelcomeHint() {
    Text(
        text = "أنا OmniDev — اسألني أي حاجة 🤖",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun QuickActionChips(onAction: (String) -> Unit) {
    val suggestions = listOf(
        "اشرحلي Kotlin coroutines",
        "اكتب unit test للـ ViewModel",
        "فين الـ bug في الكود ده؟",
        "ايه الفرق بين Flow و LiveData؟"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        suggestions.forEach { suggestion ->
            AssistChip(
                onClick = { onAction(suggestion) },
                label = {
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ── Message bubble ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleChatMessage(
    message: BubbleMessage,
    isStreaming: Boolean = false,
    onLongClick: (String) -> Unit
) {
    val context = LocalContext.current
    val isUser = message.isUser

    val bubbleColor = if (isUser)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceVariant

    val textColor = if (isUser)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onSurface

    val align = if (isUser) Alignment.End else Alignment.Start

    val cornerRadius = 16.dp
    val tailRadius = 4.dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        // Mode badge on AI messages
        if (!isUser && message.mode != null && !isStreaming) {
            val modeLabel = when (message.mode) {
                OmniMode.AGENT -> "🤖 Agent"
                OmniMode.SWARM -> "🌐 Swarm"
                else -> "💬 Chat"
            }
            Text(
                text = modeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }

        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = cornerRadius,
                        topEnd = cornerRadius,
                        bottomStart = if (isUser) cornerRadius else tailRadius,
                        bottomEnd = if (isUser) tailRadius else cornerRadius
                    )
                )
                .background(bubbleColor)
                .combinedClickable(
                    // Single tap is a no-op; long-press copies the message text
                    onClick = {},
                    onLongClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("OmniDev", message.text)
                        )
                        Toast.makeText(context, "تم النسخ", Toast.LENGTH_SHORT).show()
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 280.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // Streaming cursor blink
                if (isStreaming) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "▌",
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

// ── Animated typing dots ──

@Composable
private fun TypingDotsIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing_dots")

    @Composable
    fun bounceDot(delayMs: Int): Float {
        val anim by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = -6f,
            animationSpec = infiniteRepeatable(
                animation = tween(300, delayMs, LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot_$delayMs"
        )
        return anim
    }

    val d1 = bounceDot(0)
    val d2 = bounceDot(150)
    val d3 = bounceDot(300)

    Row(
        modifier = Modifier
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(d1, d2, d3).forEach { offset ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .offset(y = offset.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
            )
        }
    }
}

// ── Input row ──

@Composable
private fun BubbleInputRow(
    inputText: String,
    isProcessing: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    "قولي عايز إيه…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            },
            enabled = !isProcessing,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(20.dp),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                capitalization = KeyboardCapitalization.Sentences
            ),
            keyboardActions = KeyboardActions(onSend = {
                if (!isProcessing) onSend()
            })
        )
        Spacer(Modifier.width(6.dp))

        if (isProcessing) {
            // Stop generation button
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer)
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = "Stop",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            // Send button
            IconButton(
                onClick = onSend,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "Send",
                    tint = if (inputText.isNotBlank())
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
