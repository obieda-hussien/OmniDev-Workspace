package com.omnidev.workspace.data.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.R
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.domain.engine.AgentEvent
import com.omnidev.workspace.domain.engine.ChatToolLoop
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.domain.engine.SwarmEvent
import com.omnidev.workspace.domain.engine.AgentRuntime
import com.omnidev.workspace.registry.ModelRegistry
import com.omnidev.workspace.ui.chat.AgentConsoleEntry
import com.omnidev.workspace.ui.chat.consoleEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground execution owner for user-initiated Chat / Agent / Swarm runs.
 *
 * Unlike ChatViewModel, this service is independent of Activity lifecycle: closing the app,
 * navigating away, or recreating Compose does not cancel the active run. Every meaningful
 * event is checkpointed to Room + [BackgroundAgentRunStore], and a persistent notification
 * mirrors the same progress the in-app console sees.
 *
 * The service intentionally serializes runs. Long AI/tool workloads compete heavily for radio,
 * CPU, provider rate limits, terminal state, and project files; bounded serial execution is more
 * reliable than allowing multiple autonomous writers to race. Additional runs remain persisted
 * as QUEUED and are drained automatically.
 */
class BackgroundAgentService : Service() {

    companion object {
        private const val ACTION_ENQUEUE = "com.omnidev.workspace.background.ENQUEUE"
        private const val ACTION_RECOVER = "com.omnidev.workspace.background.RECOVER"
        private const val ACTION_CANCEL = "com.omnidev.workspace.background.CANCEL"
        private const val EXTRA_RUN_ID = "run_id"
        private const val CHANNEL_ID = "omni_background_agent"
        private const val BOOTSTRAP_NOTIFICATION_ID = 7200
        private const val RUN_NOTIFICATION_BASE = 7300
        private const val RUN_NOTIFICATION_RANGE = 800
        private const val CHECKPOINT_INTERVAL_MS = 700L
        private const val NOTIFICATION_INTERVAL_MS = 700L
        private const val WAKELOCK_RENEW_MS = 20L * 60 * 1000
        private const val MAX_NOTIFICATION_PREVIEW = 220

        fun enqueue(
            context: Context,
            sessionId: Long,
            userMessageId: String,
            mode: OmniMode,
            scopePath: String,
            disabledToolNames: Set<String>,
            toolAccessMode: String
        ): String {
            val store = BackgroundAgentRunStore(context)
            val run = store.create(
                sessionId = sessionId,
                userMessageId = userMessageId,
                mode = mode.name,
                scopePath = scopePath,
                disabledToolNames = disabledToolNames,
                toolAccessMode = toolAccessMode
            )
            BackgroundAgentRunBus.publish(run)
            val intent = Intent(context, BackgroundAgentService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_RUN_ID, run.id)
            }
            ContextCompat.startForegroundService(context, intent)
            return run.id
        }

        /** Restart/recover every non-terminal run from its durable chat checkpoint. */
        fun recover(context: Context) {
            val store = BackgroundAgentRunStore(context)
            if (store.recoverable().isEmpty()) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, BackgroundAgentService::class.java).setAction(ACTION_RECOVER)
            )
        }

        fun cancel(context: Context, runId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BackgroundAgentService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_RUN_ID, runId)
            )
        }

        fun chatIntent(context: Context, sessionId: Long): Intent = Intent(context, MainActivity::class.java).apply {
            putExtra("deep_link_session_id", sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }

    private val serviceJob = SupervisorJob()
    private val scope = kotlinx.coroutines.CoroutineScope(serviceJob + Dispatchers.IO)
    private lateinit var store: BackgroundAgentRunStore
    private lateinit var notifications: NotificationManagerCompat
    private val draining = AtomicBoolean(false)
    @Volatile private var activeRunId: String? = null
    @Volatile private var activeExecution: Job? = null
    @Volatile private var lastNotificationAt = 0L
    @Volatile private var lastCheckpointAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = BackgroundAgentRunStore(applicationContext)
        notifications = NotificationManagerCompat.from(this)
        createChannel()
        BackgroundAgentRunBus.publishAll(store.all())
        startForegroundCompat(BOOTSTRAP_NOTIFICATION_ID, bootstrapNotification("Preparing background agent…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_RUN_ID)?.let(::requestCancellation)
            ACTION_RECOVER -> {
                store.recoverable().filter { it.status != BackgroundAgentRunStore.Status.QUEUED }
                    .forEach { run -> store.markRecovering(run.id)?.let(BackgroundAgentRunBus::publish) }
                drain()
            }
            ACTION_ENQUEUE, null -> drain()
            else -> drain()
        }
        return START_REDELIVER_INTENT
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do NOT cancel: removing OmniDev from Recents is explicitly supported.
        // START_REDELIVER_INTENT + the durable ledger allow Android to recreate us if needed.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        activeExecution?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun drain() {
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (isActive) {
                    val next = store.active()
                        .sortedWith(compareBy<BackgroundAgentRunStore.Run> { it.createdAtMs }.thenBy { it.id })
                        .firstOrNull() ?: break
                    if (next.status == BackgroundAgentRunStore.Status.RUNNING) {
                        store.markRecovering(next.id)?.let(BackgroundAgentRunBus::publish)
                    }
                    executeOne(store.get(next.id) ?: next)
                }
            } finally {
                draining.set(false)
                if (store.active().isNotEmpty()) {
                    drain()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun executeOne(initial: BackgroundAgentRunStore.Run) {
        val started = store.update(initial.id) { run ->
            run.copy(
                status = if (run.status == BackgroundAgentRunStore.Status.RECOVERING)
                    BackgroundAgentRunStore.Status.RECOVERING else BackgroundAgentRunStore.Status.RUNNING,
                startedAtMs = run.startedAtMs ?: System.currentTimeMillis(),
                progressText = if (run.status == BackgroundAgentRunStore.Status.RECOVERING)
                    "Recovering task from checkpoint…" else "Starting task…",
                lastError = null
            )
        } ?: return
        activeRunId = started.id
        BackgroundAgentRunBus.publish(started)
        acquireWakeLock(started.id)
        val notificationId = notificationId(started.id)
        startForegroundCompat(notificationId, runningNotification(started))
        notifications.cancel(BOOTSTRAP_NOTIFICATION_ID)

        val execution = scope.launch { runPipeline(started, notificationId) }
        activeExecution = execution
        try {
            execution.join()
        } finally {
            activeExecution = null
            activeRunId = null
            releaseWakeLock()
        }
    }

    private suspend fun runPipeline(runAtStart: BackgroundAgentRunStore.Run, notificationId: Int) {
        val runtime = AgentRuntime(applicationContext)
        runtime.fileToolManager.godModeEnabled = runtime.settingsRepository.observeGodMode().first()
        runtime.toolManager.currentSessionId = runAtStart.sessionId

        val userMessage = runtime.chatRepository.getMessageById(runAtStart.userMessageId)
        if (userMessage == null) {
            finishFailure(runAtStart.id, notificationId, "Original chat message no longer exists.")
            return
        }

        val loaded = runtime.chatRepository.loadMessages(runAtStart.sessionId).first
        val existingRun = store.get(runAtStart.id) ?: runAtStart
        var assistant = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = existingRun.partialOutput.ifBlank { "Background task is running…" },
            messageId = existingRun.assistantMessageId ?: java.util.UUID.randomUUID().toString()
        )
        val assistantRow = if (existingRun.assistantRowId >= 0L) {
            existingRun.assistantRowId
        } else {
            runtime.chatRepository.saveMessage(runAtStart.sessionId, assistant)
        }
        store.update(runAtStart.id) {
            it.copy(assistantRowId = assistantRow, assistantMessageId = assistant.messageId)
        }?.let(BackgroundAgentRunBus::publish)

        var console = loaded
            .zip(runtime.chatRepository.loadMessages(runAtStart.sessionId).second.values)
            .firstOrNull { it.first.messageId == assistant.messageId }
            ?.second
            .orEmpty()
            .toMutableList()
        var partial = existingRun.partialOutput
        var finalAnswer: String? = null
        var fatalError: String? = null

        suspend fun checkpoint(progress: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastCheckpointAt < CHECKPOINT_INTERVAL_MS) return
            lastCheckpointAt = now
            assistant = assistant.copy(content = partial.ifBlank { progress })
            runtime.chatRepository.updateRun(assistantRow, assistant, console)
            store.update(runAtStart.id) {
                it.copy(
                    status = if (it.status == BackgroundAgentRunStore.Status.RECOVERING)
                        BackgroundAgentRunStore.Status.RECOVERING else BackgroundAgentRunStore.Status.RUNNING,
                    progressText = progress,
                    partialOutput = partial.take(100_000),
                    assistantRowId = assistantRow,
                    assistantMessageId = assistant.messageId
                )
            }?.let { updated ->
                BackgroundAgentRunBus.publish(updated)
                maybeUpdateNotification(notificationId, updated)
            }
        }

        val recoveryPrefix = if (existingRun.recoveryAttempt > 0 || existingRun.status == BackgroundAgentRunStore.Status.RECOVERING) {
            """
            [RECOVERY CONTINUATION]
            This run was interrupted by Android/process recreation and is continuing from a durable checkpoint.
            Do not blindly repeat side-effecting actions already completed (writes, sends, deletes, installs, commits, settings changes).
            Inspect/verify current state first, reuse persisted results when possible, then continue only the unfinished work.
            Previous partial assistant output: ${existingRun.partialOutput.take(3000)}
            [/RECOVERY CONTINUATION]
            """.trimIndent() + "\n\n"
        } else ""
        val prompt = recoveryPrefix + userMessage.content
        val history = loaded.filterNot { it.messageId == assistant.messageId || it.messageId == userMessage.messageId }

        try {
            renewWakeLockLoop(runAtStart.id)
            when (runAtStart.mode) {
                OmniMode.CHAT.name -> {
                    val modelId = runtime.settingsRepository.observeModelIdForRole(ModelRole.CHAT).first()
                    val model = ModelRegistry.findModelById(modelId) ?: ModelRegistry.getModelById(modelId)
                    val base = CompletionRequest(
                        modelId = modelId,
                        messages = (history + userMessage.copy(content = prompt)).takeLast(20),
                        systemPrompt = CHAT_BACKGROUND_SYSTEM_PROMPT,
                        maxTokens = minOf(model.maxOutputTokens, 4096),
                        enableThinking = runtime.settingsRepository.observeDeepThinking().first() && model.supportsThinking,
                        apiKey = runtime.apiKeyRepository.getApiKey(model.provider),
                        onReasoning = { delta ->
                            console += AgentConsoleEntry.DeepThinkingEntry(delta.take(280))
                            checkpoint("Deep thinking…")
                        }
                    )
                    val result = ChatToolLoop(runtime.toolManager).run(
                        base = base,
                        disabled = runAtStart.disabledToolNames,
                        originMessageId = userMessage.messageId,
                        complete = { request ->
                            partial = ""
                            runtime.completionService.stream(request) { delta ->
                                partial += delta
                                checkpoint("Writing response…")
                            }
                        },
                        event = { event ->
                            event.consoleEntry()?.let { console += it }
                            checkpoint(progressFor(event), force = event is AgentEvent.ToolExecution || event is AgentEvent.ToolResult)
                        }
                    )
                    finalAnswer = result.content
                    assistant = assistant.copy(content = result.content, executionRequest = result.request)
                }

                OmniMode.SWARM.name -> {
                    val orchestratorModel = runtime.settingsRepository.observeModelIdForRole(ModelRole.SWARM_ORCHESTRATOR).first()
                    val workerModel = runtime.settingsRepository.observeModelIdForRole(ModelRole.SWARM_WORKER).first()
                    runtime.swarmOrchestrator.execute(
                        userMessage = prompt,
                        orchestratorModelId = orchestratorModel,
                        workerModelId = workerModel,
                        scopePath = runAtStart.scopePath,
                        enableDeepThinking = runtime.settingsRepository.observeDeepThinking().first(),
                        godModeEnabled = runtime.settingsRepository.observeGodMode().first()
                    ).collect { event ->
                        when (event) {
                            SwarmEvent.PlanningStarted -> checkpoint("Planning team workflow…", true)
                            is SwarmEvent.PlanCompleted -> checkpoint("Plan ready · ${event.tasks.size} stages", true)
                            is SwarmEvent.TaskStarted -> checkpoint("Worker: ${event.task.description.take(100)}", true)
                            is SwarmEvent.WorkerToolUse -> {
                                console += AgentConsoleEntry.ToolEntry(event.toolName, event.arguments.toString().take(160), 0)
                                checkpoint("Executing ${event.toolName}…", true)
                            }
                            is SwarmEvent.WorkerToolResult -> {
                                console += AgentConsoleEntry.ResultEntry(event.toolName, event.output.take(220), event.isError)
                                checkpoint(if (event.isError) "Tool error: ${event.toolName}" else "Completed ${event.toolName}", true)
                            }
                            is SwarmEvent.WorkerStreamChunk -> {
                                partial += event.delta
                                checkpoint("Worker responding…")
                            }
                            is SwarmEvent.TaskCompleted -> checkpoint("Stage completed", true)
                            is SwarmEvent.TaskFailed -> checkpoint("Stage failed: ${event.error.take(100)}", true)
                            is SwarmEvent.TaskSkipped -> checkpoint("Stage skipped: ${event.reason.take(100)}", true)
                            SwarmEvent.SynthesisStarted -> { partial = ""; checkpoint("Synthesizing final answer…", true) }
                            is SwarmEvent.Completed -> { finalAnswer = event.summary; partial = event.summary }
                            is SwarmEvent.Error -> fatalError = event.message
                            else -> Unit
                        }
                    }
                }

                else -> {
                    val modelId = runtime.settingsRepository.observeModelIdForRole(ModelRole.AGENT).first()
                    runtime.agentPipeline.execute(
                        userMessage = prompt,
                        conversationHistory = history,
                        modelId = modelId,
                        scopePath = runAtStart.scopePath,
                        enableDeepThinking = runtime.settingsRepository.observeDeepThinking().first(),
                        userAttachments = userMessage.attachments,
                        userContext = runtime.settingsRepository.observeUserPersona().first(),
                        disabledToolNames = runAtStart.disabledToolNames,
                        toolAccessMode = runAtStart.toolAccessMode
                    ).collect { event ->
                        event.consoleEntry()?.let { console += it }
                        when (event) {
                            is AgentEvent.StreamChunk -> partial += event.delta
                            is AgentEvent.FinalAnswer -> { finalAnswer = event.content; partial = event.content }
                            is AgentEvent.Error -> fatalError = event.message
                            else -> Unit
                        }
                        checkpoint(progressFor(event), force = event is AgentEvent.ToolExecution || event is AgentEvent.ToolResult || event is AgentEvent.FinalAnswer)
                    }
                }
            }

            if (fatalError != null || finalAnswer.isNullOrBlank()) {
                finishFailure(runAtStart.id, notificationId, fatalError ?: "No final response received", runtime, assistantRow, assistant, console, partial)
            } else {
                val answer = finalAnswer.orEmpty()
                assistant = assistant.copy(content = answer)
                console += AgentConsoleEntry.ReplyEntry()
                runtime.chatRepository.updateRun(assistantRow, assistant, console)
                runtime.chatRepository.updateSessionRunStatus(runAtStart.sessionId, "Completed")
                runtime.chatRepository.touchSession(runAtStart.sessionId, loaded.firstOrNull()?.content?.take(60).orEmpty().ifBlank { "Conversation" })
                val finished = store.update(runAtStart.id) {
                    it.copy(
                        status = BackgroundAgentRunStore.Status.COMPLETED,
                        progressText = "Completed",
                        partialOutput = answer.take(100_000),
                        completedAtMs = System.currentTimeMillis(),
                        lastError = null
                    )
                }
                if (finished != null) {
                    BackgroundAgentRunBus.publish(finished)
                    detachAndPostTerminal(notificationId, finished, success = true)
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val current = store.get(runAtStart.id)
                if (current?.status == BackgroundAgentRunStore.Status.CANCELLED) {
                    assistant = assistant.copy(content = partial.ifBlank { "Run cancelled by user." })
                    runtime.chatRepository.updateRun(assistantRow, assistant, console)
                    runtime.chatRepository.updateSessionRunStatus(runAtStart.sessionId, "Cancelled")
                } else {
                    // Service/process cancellation is recoverable, not a task failure.
                    store.markRecovering(runAtStart.id)?.let(BackgroundAgentRunBus::publish)
                    runtime.chatRepository.updateRun(assistantRow, assistant.copy(content = partial.ifBlank { "Recovering background run…" }), console)
                }
            }
            throw cancelled
        } catch (error: Exception) {
            finishFailure(runAtStart.id, notificationId, error.message ?: "Background execution failed", runtime, assistantRow, assistant, console, partial)
        }
    }

    private suspend fun finishFailure(
        runId: String,
        notificationId: Int,
        message: String,
        runtime: AgentRuntime? = null,
        assistantRow: Long = -1L,
        assistant: ChatMessage? = null,
        console: List<AgentConsoleEntry> = emptyList(),
        partial: String = ""
    ) {
        val failed = store.update(runId) {
            it.copy(
                status = BackgroundAgentRunStore.Status.FAILED,
                progressText = "Failed",
                partialOutput = partial.take(100_000),
                completedAtMs = System.currentTimeMillis(),
                lastError = message
            )
        } ?: return
        if (runtime != null && assistant != null && assistantRow >= 0) {
            val errorEntry = AgentConsoleEntry.ErrorEntry(message.take(500))
            runtime.chatRepository.updateRun(
                assistantRow,
                assistant.copy(content = partial.ifBlank { "Background run failed: $message" }),
                console + errorEntry
            )
            runtime.chatRepository.updateSessionRunStatus(failed.sessionId, "Failed")
        }
        BackgroundAgentRunBus.publish(failed)
        detachAndPostTerminal(notificationId, failed, success = false)
    }

    private fun requestCancellation(runId: String) {
        val cancelled = store.update(runId) {
            if (it.terminal) it else it.copy(
                status = BackgroundAgentRunStore.Status.CANCELLED,
                progressText = "Cancelled",
                completedAtMs = System.currentTimeMillis(),
                lastError = null
            )
        }
        cancelled?.let {
            BackgroundAgentRunBus.publish(it)
            notifications.notify(notificationId(it.id), terminalNotification(it, success = false, cancelled = true))
        }
        if (activeRunId == runId) activeExecution?.cancel(CancellationException("User cancelled background run"))
    }

    private fun progressFor(event: AgentEvent): String = when (event) {
        AgentEvent.Started -> "Agent started"
        is AgentEvent.Thinking -> "Thinking · iteration ${event.iteration}"
        is AgentEvent.ThinkingBlock -> "Deep thinking…"
        is AgentEvent.ToolExecution -> "Executing ${event.toolName}…"
        is AgentEvent.ToolResult -> if (event.isError) "Tool error · ${event.toolName}" else "Completed ${event.toolName}"
        is AgentEvent.TokenUsageUpdate -> "Working · ${event.totalTokens} tokens"
        is AgentEvent.PhaseChanged -> "${event.phase.name.lowercase().replaceFirstChar { it.uppercase() }}${event.detail?.let { d -> " · ${d.take(80)}" } ?: ""}"
        is AgentEvent.StreamChunk -> "Writing response…"
        is AgentEvent.FinalAnswer -> "Finalizing…"
        is AgentEvent.Reflecting -> "Reviewing answer…"
        is AgentEvent.Error -> "Error · ${event.message.take(100)}"
        is AgentEvent.ContextCompaction -> "Compacting context…"
    }

    private fun maybeUpdateNotification(id: Int, run: BackgroundAgentRunStore.Run) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt < NOTIFICATION_INTERVAL_MS) return
        lastNotificationAt = now
        notifications.notify(id, runningNotification(run))
    }

    private fun runningNotification(run: BackgroundAgentRunStore.Run): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            run.sessionId.hashCode(),
            chatIntent(this, run.sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this,
            run.id.hashCode(),
            Intent(this, BackgroundAgentService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_RUN_ID, run.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val detail = run.partialOutput.lineSequence().lastOrNull { it.isNotBlank() }?.take(MAX_NOTIFICATION_PREVIEW)
            ?: run.progressText
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (run.status == BackgroundAgentRunStore.Status.RECOVERING) "Omni is recovering your task" else "Omni is working in the background")
            .setContentText(run.progressText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${run.progressText}\n$detail"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .addAction(0, "Stop", cancel)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun terminalNotification(
        run: BackgroundAgentRunStore.Run,
        success: Boolean,
        cancelled: Boolean = false
    ): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            run.sessionId.hashCode(),
            chatIntent(this, run.sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when {
            cancelled -> "Omni task cancelled"
            success -> "Omni finished your task"
            else -> "Omni task needs attention"
        }
        val body = when {
            cancelled -> run.partialOutput.ifBlank { "The background run was stopped." }
            success -> run.partialOutput.ifBlank { "Task completed." }
            else -> run.lastError ?: "Task failed."
        }.take(MAX_NOTIFICATION_PREVIEW)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun detachAndPostTerminal(id: Int, run: BackgroundAgentRunStore.Run, success: Boolean) {
        stopForeground(STOP_FOREGROUND_DETACH)
        notifications.notify(id, terminalNotification(run, success))
    }

    private fun bootstrapNotification(text: String): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Omni background runtime")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Agent",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Live progress for user-started Omni chat and agent tasks"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundCompat(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }

    private fun notificationId(runId: String): Int =
        RUN_NOTIFICATION_BASE + kotlin.math.abs(runId.hashCode() % RUN_NOTIFICATION_RANGE)

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock(runId: String) {
        releaseWakeLock()
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniDev:BackgroundAgent:${runId.take(8)}").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_RENEW_MS)
        }
    }

    private fun renewWakeLockLoop(runId: String) {
        scope.launch {
            while (isActive && activeRunId == runId) {
                delay(WAKELOCK_RENEW_MS / 2)
                if (activeRunId != runId) break
                val lock = wakeLock ?: break
                if (!lock.isHeld) lock.acquire(WAKELOCK_RENEW_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private companion object Prompt {
        const val CHAT_BACKGROUND_SYSTEM_PROMPT =
            "You are Omni, a concise assistant running in OmniDev's durable background chat runtime. " +
            "You may use the supplied web tools when useful. Complete the user's request autonomously, " +
            "report tool failures accurately, and never assume a side effect succeeded without verification."
    }
}
