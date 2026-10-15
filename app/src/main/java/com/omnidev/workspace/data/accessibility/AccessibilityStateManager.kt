package com.omnidev.workspace.data.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/** AccessibilityStateManager tracks UI change velocity, recent package navigation, screen classification, debounced content changes, session statistics and structured UI events for the agent loop. */
object AccessibilityStateManager {

    // ── Core State ──────────────────────────────────────────────────────────

    private val _rootNode = MutableStateFlow<AccessibilityNodeInfo?>(null)
    val rootNode: StateFlow<AccessibilityNodeInfo?> = _rootNode.asStateFlow()

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _activePackage = MutableStateFlow<String?>(null)
    val activePackage: StateFlow<String?> = _activePackage.asStateFlow()

    private val _activeActivity = MutableStateFlow<String?>(null)
    val activeActivity: StateFlow<String?> = _activeActivity.asStateFlow()

    private val _lastUpdateTime = MutableStateFlow(0L)
    val lastUpdateTime: StateFlow<Long> = _lastUpdateTime.asStateFlow()

    // ── Enhanced State ───────────────────────────────────────────────────────

    /** Navigation history: the last 20 packages with timestamps. */
    private val _navigationHistory = MutableStateFlow<List<NavigationEntry>>(emptyList())
    val navigationHistory: StateFlow<List<NavigationEntry>> = _navigationHistory.asStateFlow()

    /** Heuristic classification of the current screen. */
    private val _screenClass = MutableStateFlow(ScreenClass.UNKNOWN)
    val screenClass: StateFlow<ScreenClass> = _screenClass.asStateFlow()

    /** UI change velocity: content changes over the last five seconds. */
    private val _changeVelocity = MutableStateFlow(0f)
    val changeVelocity: StateFlow<Float> = _changeVelocity.asStateFlow()

    /** Whether velocity and node count suggest a loading screen. */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Structured UI event stream for the agent. */
    private val _uiEvents = MutableSharedFlow<UIEvent>(extraBufferCapacity = 32)
    val uiEvents: SharedFlow<UIEvent> = _uiEvents.asSharedFlow()

    /** Statistics for the current session. */
    private val _sessionStats = MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats.asStateFlow()

    /** Last node-count snapshot for loading detection. */
    private val _lastNodeCount = MutableStateFlow(0)
    val lastNodeCount: StateFlow<Int> = _lastNodeCount.asStateFlow()

    // ── Internal Tracking ────────────────────────────────────────────────────

    /** Five-second window for measuring change velocity. */
    private val recentChangeTimestamps = ConcurrentLinkedDeque<Long>()
    private val VELOCITY_WINDOW_MS = 5_000L

    /** Root changes since the last package change. */
    private val rootUpdatesSincePackageChange = AtomicInteger(0)

    /** Timestamp of the last package change. */
    private val lastPackageChangeMs = AtomicLong(0L)

    /** Detected navigation pattern sequences. */
    private val detectedNavigationPatterns = ConcurrentHashMap<String, Int>()

    private val rootGeneration = AtomicLong(0L)
    internal fun snapshotGeneration(): Long = rootGeneration.get()

    /** Last package hash used for debouncing. */
    @Volatile
    private var lastKnownPackage: String? = null

    // ── Public API ───────────────────────────────────────────────────────────

    /** Constant-time publication only. Never traverse a remote tree in a service callback. */
    @Synchronized
    fun updateRootNode(node: AccessibilityNodeInfo?) {
        rootGeneration.incrementAndGet()
        publishRoot(node, null)
    }

    /** Reject work that finished after consent revocation, window invalidation or disconnect. */
    @Synchronized
    internal fun publishRootNode(
        node: AccessibilityNodeInfo?,
        analysis: AccessibilityScreenAnalyzer.Analysis?,
        generation: Long
    ): Boolean {
        if (!_isServiceConnected.value || rootGeneration.get() != generation) return false
        publishRoot(node, analysis)
        return true
    }

    private fun publishRoot(node: AccessibilityNodeInfo?, analysis: AccessibilityScreenAnalyzer.Analysis?) {
        val old = _rootNode.value
        if (old !== node) {
            // AccessibilityNodeInfo.equals compares node identity, not snapshot identity. Clear
            // first so StateFlow cannot retain an equal, recycled older wrapper on Android 11.
            _rootNode.value = null
            try { old?.recycle() } catch (_: IllegalStateException) { }
            _rootNode.value = node
        }
        val now = System.currentTimeMillis()
        _lastUpdateTime.value = now
        rootUpdatesSincePackageChange.incrementAndGet()
        if (node == null) {
            _lastNodeCount.value = 0
            _screenClass.value = ScreenClass.UNKNOWN
            _isLoading.value = false
            _changeVelocity.value = 0f
            recentChangeTimestamps.clear()
        } else {
            trackVelocity(now)
            val count = analysis?.nodeCount ?: 0
            val screen = analysis?.screenClass ?: ScreenClass.UNKNOWN
            _lastNodeCount.value = count
            if (screen != _screenClass.value) {
                _screenClass.value = screen
                _uiEvents.tryEmit(UIEvent.ScreenClassChanged(screen, _activePackage.value))
            }
            _isLoading.value = analysis != null && !analysis.truncated &&
                detectLoading(count, _changeVelocity.value, screen)
        }
        _uiEvents.tryEmit(UIEvent.ContentChanged(_activePackage.value, now))
    }

    /** Update the active application window and analyze navigation patterns. */
    fun updateActiveWindow(packageName: String?, activityName: String?) {
        val prev = _activePackage.value
        _activePackage.value = packageName
        _activeActivity.value = activityName

        if (packageName != null && packageName != prev) {
            val now = System.currentTimeMillis()
            lastPackageChangeMs.set(now)
            rootUpdatesSincePackageChange.set(0)

            // Update navigation history.
            val entry = NavigationEntry(packageName, activityName, now)
            val history = _navigationHistory.value.toMutableList()
            history.add(entry)
            if (history.size > 20) history.removeAt(0)
            _navigationHistory.value = history

            // Detect repeated navigation patterns.
            analyzeNavigationPattern(history)

            // Update session statistics.
            updateSessionStats(packageName, prev, now)

            // Emit an application-change event.
            _uiEvents.tryEmit(UIEvent.AppSwitched(prev, packageName, now))
        }
    }

    /** Set connection state and initialize or clear session variables. */
    fun setServiceConnected(connected: Boolean) {
        _isServiceConnected.value = connected
        if (!connected) {
            updateRootNode(null)
            _activePackage.value = null
            _activeActivity.value = null
            _screenClass.value = ScreenClass.UNKNOWN
            _isLoading.value = false
            _changeVelocity.value = 0f
            recentChangeTimestamps.clear()
            _uiEvents.tryEmit(UIEvent.ServiceDisconnected)
        } else {
            _uiEvents.tryEmit(UIEvent.ServiceConnected)
        }
    }

    /** Find the first accessibility node matching a predicate without a full dump_tree. */
    fun findNodeByText(text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        val root = _rootNode.value ?: return null
        return findNodeRecursive(root) { node ->
            if (node.isPassword || node.viewIdResourceName?.substringAfterLast('/') in setOf("pinEntry", "passwordEntry")) return@findNodeRecursive false
            val nodeText = node.text?.toString() ?: ""
            val nodeDesc = node.contentDescription?.toString() ?: ""
            if (exactMatch) {
                nodeText.equals(text, ignoreCase = true) || nodeDesc.equals(text, ignoreCase = true)
            } else {
                nodeText.contains(text, ignoreCase = true) || nodeDesc.contains(text, ignoreCase = true)
            }
        }
    }

    /** Describe the current state for system-prompt context. */
    fun buildContextSummary(): String = buildString {
        append("🖥️ UI Context Summary\n")
        append("━━━━━━━━━━━━━━━━━━━━\n")
        append("App: ${_activePackage.value ?: "Unknown"}\n")
        append("Activity: ${_activeActivity.value?.substringAfterLast('.') ?: "Unknown"}\n")
        append("Screen Type: ${_screenClass.value.name}\n")
        append("Loading: ${if (_isLoading.value) "Yes ⏳" else "No ✅"}\n")
        append("Node Count: ${_lastNodeCount.value}\n")
        append("Change Velocity: ${"%.1f".format(_changeVelocity.value)} changes/5s\n")

        val recentApps = _navigationHistory.value.takeLast(5).map {
            it.packageName.substringAfterLast('.')
        }
        if (recentApps.isNotEmpty()) {
            append("Recent Path: ${recentApps.joinToString(" → ")}\n")
        }

        val patterns = detectedNavigationPatterns.entries
            .sortedByDescending { it.value }
            .take(3)
        if (patterns.isNotEmpty()) {
            append("Detected Patterns: ${patterns.joinToString(", ") { "${it.key}(×${it.value})" }}\n")
        }

        val stats = _sessionStats.value
        append("Session: ${stats.totalScreenVisits} screens, ")
        append("${stats.uniqueApps.size} apps, ")
        append("${formatDuration(stats.sessionDurationMs())} elapsed")
    }

    /** List the most frequently used applications in the current session. */
    fun getMostUsedApps(limit: Int = 5): List<Pair<String, Int>> =
        _sessionStats.value.appVisitCounts
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }

    /** Check whether the current screen resembles a form. */
    fun isFormScreen(): Boolean = _screenClass.value == ScreenClass.FORM

    /** Check whether the agent should wait before interacting. */
    fun shouldWaitForUI(): Boolean = _isLoading.value || _changeVelocity.value > 5f

    // ── Private Helpers ───────────────────────────────────────────────────────

    private fun trackVelocity(now: Long) {
        recentChangeTimestamps.addLast(now)
        // Remove timestamps older than five seconds.
        while (recentChangeTimestamps.isNotEmpty() &&
            now - recentChangeTimestamps.peekFirst() > VELOCITY_WINDOW_MS) {
            recentChangeTimestamps.pollFirst()
        }
        _changeVelocity.value = recentChangeTimestamps.size.toFloat()
    }

    private fun detectLoading(nodeCount: Int, velocity: Float, screenClass: ScreenClass): Boolean {
        return screenClass == ScreenClass.LOADING ||
                (nodeCount < 8 && velocity > 3f) ||
                (velocity > 10f) // Rapid UI changes suggest a transition.
    }

    private fun analyzeNavigationPattern(history: List<NavigationEntry>) {
        if (history.size < 3) return
        // Detect repeated A→B→A navigation patterns.
        val recent = history.takeLast(6)
        val safeSize = recent.size
        for (i in 0 until safeSize - 2) {
            val a = recent.getOrNull(i)?.packageName
            val b = recent.getOrNull(i + 1)?.packageName
            val c = recent.getOrNull(i + 2)?.packageName
            if (a == c && a != b && a != null && b != null) {
                val pattern = "${a.substringAfterLast('.')}→${b.substringAfterLast('.')}"
                detectedNavigationPatterns[pattern] =
                    (detectedNavigationPatterns[pattern] ?: 0) + 1
            }
        }
    }

    private fun updateSessionStats(currentPkg: String, prevPkg: String?, now: Long) {
        val current = _sessionStats.value
        val newCounts = current.appVisitCounts.toMutableMap()
        newCounts[currentPkg] = (newCounts[currentPkg] ?: 0) + 1

        val newApps = current.uniqueApps.toMutableSet().also { it.add(currentPkg) }

        _sessionStats.value = current.copy(
            totalScreenVisits = current.totalScreenVisits + 1,
            appVisitCounts = newCounts,
            uniqueApps = newApps,
            lastActivityMs = now
        )
    }

    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeRecursive(child, predicate)
            if (found != null) return found
            if (found == null && child != node) child.recycle()
        }
        return null
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min > 0) "${min}m ${sec}s" else "${sec}s"
    }

    // ── Data Classes & Enums ─────────────────────────────────────────────────

    data class NavigationEntry(
        val packageName: String,
        val activityName: String?,
        val timestampMs: Long
    )

    data class SessionStats(
        val sessionStartMs: Long = System.currentTimeMillis(),
        val lastActivityMs: Long = System.currentTimeMillis(),
        val totalScreenVisits: Int = 0,
        val appVisitCounts: Map<String, Int> = emptyMap(),
        val uniqueApps: Set<String> = emptySet()
    ) {
        fun sessionDurationMs(): Long = lastActivityMs - sessionStartMs
    }

    /** Screen classifications. */
    enum class ScreenClass {
        UNKNOWN,    // Not analyzed yet.
        LOADING,    // Loading screen or transition.
        INTERACTIVE,// General interactive screen.
        FORM,       // Data-entry form.
        SEARCH,     // Search bar or single input.
        LIST,       // List of items.
        DIALOG,     // Popup dialog.
        ERROR       // Error message.
    }

    /** Structured UI events. */
    sealed class UIEvent {
        object ServiceConnected : UIEvent()
        object ServiceDisconnected : UIEvent()
        data class AppSwitched(val from: String?, val to: String, val timestamp: Long) : UIEvent()
        data class ContentChanged(val packageName: String?, val timestamp: Long) : UIEvent()
        data class ScreenClassChanged(val newClass: ScreenClass, val packageName: String?) : UIEvent()
    }
}
