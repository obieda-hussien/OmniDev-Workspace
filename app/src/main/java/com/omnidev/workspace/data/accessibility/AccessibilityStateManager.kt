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
 * AccessibilityStateManager — [Localized] [Localized] [Localized] [Localized] [Localized]
 *
 * [Localized] [Localized] [Localized]:
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **[Localized] [Localized] [Localized] [Localized] (UI Change Velocity)**: [Localized] [Localized] [Localized] [Localized]
 *    [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] (loading) [Localized] [Localized] [Localized].
 *
 * 2. **[Localized] [Localized] [Localized] (Navigation Pattern Detection)**: [Localized] [Localized] [Localized] packages
 *    [Localized] [Localized] [Localized] ([Localized]: Settings → WiFi → back → Settings).
 *
 * 3. **[Localized] [Localized] [Localized] (UI State Classification)**: [Localized] [Localized] [Localized]
 *    [Localized] LOADING / INTERACTIVE / ERROR / DIALOG / LIST / FORM.
 *
 * 4. **[Localized] [Localized] [Localized] [Localized] (Smart Debounce)**: [Localized] [Localized] [Localized]
 *    [Localized] [Localized] [Localized] content [Localized] (scroll, animation).
 *
 * 5. **[Localized] [Localized] (Session Memory)**: [Localized] [Localized] [Localized] [Localized] —
 *    [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
 *
 * 6. **[Localized] UI [Localized] [Localized] (UI Event Streaming)**: [Localized] [Localized] [Localized]
 *    (UIEvent) [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] — [Localized] [Localized] ReAct.
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

    /** [Localized] [Localized]: [Localized] 20 [Localized] [Localized] [Localized] [Localized] */
    private val _navigationHistory = MutableStateFlow<List<NavigationEntry>>(emptyList())
    val navigationHistory: StateFlow<List<NavigationEntry>> = _navigationHistory.asStateFlow()

    /** [Localized] [Localized] [Localized] [Localized] [Localized] */
    private val _screenClass = MutableStateFlow(ScreenClass.UNKNOWN)
    val screenClass: StateFlow<ScreenClass> = _screenClass.asStateFlow()

    /** [Localized] [Localized] [Localized]: [Localized] [Localized] [Localized] content [Localized] [Localized] 5 [Localized] */
    private val _changeVelocity = MutableStateFlow(0f)
    val changeVelocity: StateFlow<Float> = _changeVelocity.asStateFlow()

    /** [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized] velocity + node count) */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** [Localized] [Localized] UI [Localized] [Localized] */
    private val _uiEvents = MutableSharedFlow<UIEvent>(extraBufferCapacity = 32)
    val uiEvents: SharedFlow<UIEvent> = _uiEvents.asSharedFlow()

    /** [Localized] [Localized] [Localized] */
    private val _sessionStats = MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats.asStateFlow()

    /** [Localized] snapshot [Localized] node count ([Localized] [Localized] loading) */
    private val _lastNodeCount = MutableStateFlow(0)
    val lastNodeCount: StateFlow<Int> = _lastNodeCount.asStateFlow()

    // ── Internal Tracking ────────────────────────────────────────────────────

    /** [Localized] [Localized] 5 [Localized] [Localized] [Localized] velocity */
    private val recentChangeTimestamps = ConcurrentLinkedDeque<Long>()
    private val VELOCITY_WINDOW_MS = 5_000L

    /** [Localized] [Localized] [Localized] root [Localized] [Localized] package change */
    private val rootUpdatesSincePackageChange = AtomicInteger(0)

    /** [Localized] [Localized] [Localized] [Localized] package */
    private val lastPackageChangeMs = AtomicLong(0L)

    /** [Localized] [Localized] [Localized] [Localized] */
    private val detectedNavigationPatterns = mutableMapOf<String, Int>()

    /** [Localized] package hash [Localized] debounce */
    @Volatile
    private var lastKnownPackage: String? = null

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * [Localized] [Localized] root node [Localized] [Localized] [Localized] [Localized].
     * [Localized] [Localized] [OmniAccessibilityService] [Localized] [Localized] [Localized].
     */
    fun updateRootNode(node: AccessibilityNodeInfo?) {
        recycleOldRoot(node)
        _rootNode.value = node
        val now = System.currentTimeMillis()
        _lastUpdateTime.value = now
        rootUpdatesSincePackageChange.incrementAndGet()

        // [Localized] [Localized] velocity
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
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    fun updateActiveWindow(packageName: String?, activityName: String?) {
        val prev = _activePackage.value
        _activePackage.value = packageName
        _activeActivity.value = activityName

        if (packageName != null && packageName != prev) {
            val now = System.currentTimeMillis()
            lastPackageChangeMs.set(now)
            rootUpdatesSincePackageChange.set(0)

            // [Localized] [Localized] [Localized]
            val entry = NavigationEntry(packageName, activityName, now)
            val history = _navigationHistory.value.toMutableList()
            history.add(entry)
            if (history.size > 20) history.removeAt(0)
            _navigationHistory.value = history

            // [Localized] [Localized] [Localized] [Localized]
            analyzeNavigationPattern(history)

            // [Localized] [Localized] [Localized]
            updateSessionStats(packageName, prev, now)

            // [Localized] [Localized] [Localized] [Localized]
            _uiEvents.tryEmit(UIEvent.AppSwitched(prev, packageName, now))
        }
    }

    /** [Localized] [Localized] [Localized] [Localized] [Localized] / [Localized] [Localized]. */
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
     * [Localized] [Localized] [Localized] [Localized] accessibility [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] dump_tree [Localized].
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
     * [Localized] [Localized] [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized] [Localized] [Localized] system prompt.
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
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    fun getMostUsedApps(limit: Int = 5): List<Pair<String, Int>> =
        _sessionStats.value.appVisitCounts
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] (form) — [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    fun isFormScreen(): Boolean = _screenClass.value == ScreenClass.FORM

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
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
        // [Localized] [Localized] [Localized] [Localized] 5 [Localized]
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
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] node tree.
     * [Localized]: [Localized] [Localized] heuristics + [Localized] [Localized].
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
                (velocity > 10f) // [Localized] [Localized] [Localized] [Localized] = [Localized]
    }

    private fun analyzeNavigationPattern(history: List<NavigationEntry>) {
        if (history.size < 3) return
        // [Localized] [Localized] A→B→A [Localized] (bounce back patterns)
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

    /** [Localized] [Localized] [Localized] */
    enum class ScreenClass {
        UNKNOWN,    // [Localized] [Localized] [Localized]
        LOADING,    // [Localized] [Localized] / [Localized]
        INTERACTIVE,// [Localized] [Localized] [Localized]
        FORM,       // [Localized] [Localized] [Localized]
        SEARCH,     // [Localized] [Localized] / [Localized] [Localized]
        LIST,       // [Localized] [Localized]
        DIALOG,     // [Localized] [Localized]
        ERROR       // [Localized] [Localized]
    }

    /** [Localized] UI [Localized] */
    sealed class UIEvent {
        object ServiceConnected : UIEvent()
        object ServiceDisconnected : UIEvent()
        data class AppSwitched(val from: String?, val to: String, val timestamp: Long) : UIEvent()
        data class ContentChanged(val packageName: String?, val timestamp: Long) : UIEvent()
        data class ScreenClassChanged(val newClass: ScreenClass, val packageName: String?) : UIEvent()
    }
}
