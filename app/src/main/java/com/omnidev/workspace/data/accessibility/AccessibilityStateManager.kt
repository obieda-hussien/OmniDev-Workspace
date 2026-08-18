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
import kotlin.math.abs

/**
 * AccessibilityStateManager — Context note Context note Context note Context note Context note
 *
 * Context note Context note Context note:
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **Context note Context note Context note Context note (UI Change Velocity)**: Context note Context note Context note Context note
 *    Context note Context note Context note Context note Context note Context note Context note (loading) Context note Context note Context note.
 *
 * 2. **Context note Context note Context note (Navigation Pattern Detection)**: Context note Context note Context note packages
 *    Context note Context note Context note (Context note: Settings → WiFi → back → Settings).
 *
 * 3. **Context note Context note Context note (UI State Classification)**: Context note Context note Context note
 *    Context note LOADING / INTERACTIVE / ERROR / DIALOG / LIST / FORM.
 *
 * 4. **Context note Context note Context note Context note (Smart Debounce)**: Context note Context note Context note
 *    Context note Context note Context note content Context note (scroll, animation).
 *
 * 5. **Context note Context note (Session Memory)**: Context note Context note Context note Context note —
 *    Context note Context note Context note Context note Context note Context note Context note.
 *
 * 6. **Context note UI Context note Context note (UI Event Streaming)**: Context note Context note Context note
 *    (UIEvent) Context note Context note Context note Context note Context note Context note — Context note Context note ReAct.
 */
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

    /** Context note Context note: Context note 20 Context note Context note Context note Context note */
    private val _navigationHistory = MutableStateFlow<List<NavigationEntry>>(emptyList())
    val navigationHistory: StateFlow<List<NavigationEntry>> = _navigationHistory.asStateFlow()

    /** Context note Context note Context note Context note Context note */
    private val _screenClass = MutableStateFlow(ScreenClass.UNKNOWN)
    val screenClass: StateFlow<ScreenClass> = _screenClass.asStateFlow()

    /** Context note Context note Context note: Context note Context note Context note content Context note Context note 5 Context note */
    private val _changeVelocity = MutableStateFlow(0f)
    val changeVelocity: StateFlow<Float> = _changeVelocity.asStateFlow()

    /** Context note Context note Context note Context note Context note (Context note Context note Context note velocity + node count) */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Context note Context note UI Context note Context note */
    private val _uiEvents = MutableSharedFlow<UIEvent>(extraBufferCapacity = 32)
    val uiEvents: SharedFlow<UIEvent> = _uiEvents.asSharedFlow()

    /** Context note Context note Context note */
    private val _sessionStats = MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats.asStateFlow()

    /** Context note snapshot Context note node count (Context note Context note loading) */
    private val _lastNodeCount = MutableStateFlow(0)
    val lastNodeCount: StateFlow<Int> = _lastNodeCount.asStateFlow()

    // ── Internal Tracking ────────────────────────────────────────────────────

    /** Context note Context note 5 Context note Context note Context note velocity */
    private val recentChangeTimestamps = ConcurrentLinkedDeque<Long>()
    private val VELOCITY_WINDOW_MS = 5_000L

    /** Context note Context note Context note root Context note Context note package change */
    private val rootUpdatesSincePackageChange = AtomicInteger(0)

    /** Context note Context note Context note Context note package */
    private val lastPackageChangeMs = AtomicLong(0L)

    /** Context note Context note Context note Context note */
    private val detectedNavigationPatterns = mutableMapOf<String, Int>()

    /** Context note package hash Context note debounce */
    @Volatile
    private var lastKnownPackage: String? = null

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Context note Context note root node Context note Context note Context note Context note.
     * Context note Context note [OmniAccessibilityService] Context note Context note Context note.
     */
    fun updateRootNode(node: AccessibilityNodeInfo?) {
        recycleOldRoot(node)
        _rootNode.value = node
        val now = System.currentTimeMillis()
        _lastUpdateTime.value = now
        rootUpdatesSincePackageChange.incrementAndGet()

        // Context note Context note velocity
        trackVelocity(now)

        if (node != null) {
            val nodeCount = countNodes(node)
            _lastNodeCount.value = nodeCount
            val newClass = classifyScreen(node, nodeCount)
            if (newClass != _screenClass.value) {
                _screenClass.value = newClass
                _uiEvents.tryEmit(UIEvent.ScreenClassChanged(newClass, _activePackage.value))
            }
            _isLoading.value = detectLoading(nodeCount, _changeVelocity.value, newClass)
        }

        _uiEvents.tryEmit(UIEvent.ContentChanged(_activePackage.value, now))
    }

    /**
     * Context note Context note Context note Context note Context note Context note Context note Context note.
     */
    fun updateActiveWindow(packageName: String?, activityName: String?) {
        val prev = _activePackage.value
        _activePackage.value = packageName
        _activeActivity.value = activityName

        if (packageName != null && packageName != prev) {
            val now = System.currentTimeMillis()
            lastPackageChangeMs.set(now)
            rootUpdatesSincePackageChange.set(0)

            // Context note Context note Context note
            val entry = NavigationEntry(packageName, activityName, now)
            val history = _navigationHistory.value.toMutableList()
            history.add(entry)
            if (history.size > 20) history.removeAt(0)
            _navigationHistory.value = history

            // Context note Context note Context note Context note
            analyzeNavigationPattern(history)

            // Context note Context note Context note
            updateSessionStats(packageName, prev, now)

            // Context note Context note Context note Context note
            _uiEvents.tryEmit(UIEvent.AppSwitched(prev, packageName, now))
        }
    }

    /** Context note Context note Context note Context note Context note / Context note Context note. */
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

    /**
     * Context note Context note Context note Context note accessibility Context note Context note Context note Context note Context note Context note.
     * Context note Context note Context note Context note Context note Context note dump_tree Context note.
     */
    fun findNodeByText(text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        val root = _rootNode.value ?: return null
        return findNodeRecursive(root) { node ->
            val nodeText = node.text?.toString() ?: ""
            val nodeDesc = node.contentDescription?.toString() ?: ""
            if (exactMatch) {
                nodeText.equals(text, ignoreCase = true) || nodeDesc.equals(text, ignoreCase = true)
            } else {
                nodeText.contains(text, ignoreCase = true) || nodeDesc.contains(text, ignoreCase = true)
            }
        }
    }

    /**
     * Context note Context note Context note Context note Context note — Context note Context note Context note Context note Context note system prompt.
     */
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

    /**
     * Context note Context note Context note Context note Context note Context note Context note Context note.
     */
    fun getMostUsedApps(limit: Int = 5): List<Pair<String, Int>> =
        _sessionStats.value.appVisitCounts
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }

    /**
     * Context note Context note Context note Context note Context note Context note Context note Context note (form) — Context note Context note Context note Context note Context note.
     */
    fun isFormScreen(): Boolean = _screenClass.value == ScreenClass.FORM

    /**
     * Context note Context note Context note Context note Context note Context note Context note Context note Context note Context note.
     */
    fun shouldWaitForUI(): Boolean = _isLoading.value || _changeVelocity.value > 5f

    // ── Private Helpers ───────────────────────────────────────────────────────

    private fun recycleOldRoot(newNode: AccessibilityNodeInfo?) {
        val oldNode = _rootNode.value
        if (oldNode != null && oldNode != newNode) {
            try { oldNode.recycle() } catch (_: IllegalStateException) {}
        }
    }

    private fun trackVelocity(now: Long) {
        recentChangeTimestamps.addLast(now)
        // Context note Context note Context note Context note 5 Context note
        while (recentChangeTimestamps.isNotEmpty() &&
            now - recentChangeTimestamps.peekFirst() > VELOCITY_WINDOW_MS) {
            recentChangeTimestamps.pollFirst()
        }
        _changeVelocity.value = recentChangeTimestamps.size.toFloat()
    }

    private fun countNodes(root: AccessibilityNodeInfo): Int {
        var count = 1
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            count += countNodes(child)
            child.recycle()
        }
        return count
    }

    /**
     * Context note Context note Context note Context note Context note Context note node tree.
     * Context note: Context note Context note heuristics + Context note Context note.
     */
    private fun classifyScreen(root: AccessibilityNodeInfo, nodeCount: Int): ScreenClass {
        var editableCount = 0
        var listItemCount = 0
        var progressCount = 0
        var dialogSignal = false
        var errorSignal = false

        fun scan(node: AccessibilityNodeInfo) {
            val className = node.className?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""

            when {
                node.isEditable -> editableCount++
                "recyclerview" in className || "listview" in className -> listItemCount++
                "progressbar" in className || "progress" in text -> progressCount++
                "dialog" in className || "alertdialog" in className -> dialogSignal = true
                text.contains("error") || text.contains("failed") ||
                        desc.contains("error") -> errorSignal = true
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                scan(child)
                child.recycle()
            }
        }

        scan(root)

        return when {
            progressCount > 0 && nodeCount < 15 -> ScreenClass.LOADING
            dialogSignal -> ScreenClass.DIALOG
            errorSignal -> ScreenClass.ERROR
            editableCount >= 2 -> ScreenClass.FORM
            editableCount == 1 -> ScreenClass.SEARCH
            listItemCount > 0 -> ScreenClass.LIST
            nodeCount < 5 -> ScreenClass.LOADING
            else -> ScreenClass.INTERACTIVE
        }
    }

    private fun detectLoading(nodeCount: Int, velocity: Float, screenClass: ScreenClass): Boolean {
        return screenClass == ScreenClass.LOADING ||
                (nodeCount < 8 && velocity > 3f) ||
                (velocity > 10f) // Context note Context note Context note Context note = Context note
    }

    private fun analyzeNavigationPattern(history: List<NavigationEntry>) {
        if (history.size < 3) return
        // Context note Context note A→B→A Context note (bounce back patterns)
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

    /** Context note Context note Context note */
    enum class ScreenClass {
        UNKNOWN,    // Context note Context note Context note
        LOADING,    // Context note Context note / Context note
        INTERACTIVE,// Context note Context note Context note
        FORM,       // Context note Context note Context note
        SEARCH,     // Context note Context note / Context note Context note
        LIST,       // Context note Context note
        DIALOG,     // Context note Context note
        ERROR       // Context note Context note
    }

    /** Context note UI Context note */
    sealed class UIEvent {
        object ServiceConnected : UIEvent()
        object ServiceDisconnected : UIEvent()
        data class AppSwitched(val from: String?, val to: String, val timestamp: Long) : UIEvent()
        data class ContentChanged(val packageName: String?, val timestamp: Long) : UIEvent()
        data class ScreenClassChanged(val newClass: ScreenClass, val packageName: String?) : UIEvent()
    }
}
