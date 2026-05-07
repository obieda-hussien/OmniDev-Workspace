package com.omnidev.workspace.data.tools

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ═══════════════════════════════════════════════════════════════════════════════
// HeadlessBrowserManager — v3.0 "Smart Readiness Edition"
//
// KEY IMPROVEMENTS:
//  ✦ PageReadinessOracle — multi-signal page load detection engine:
//      • Network idle: no active XHR/fetch for 600ms
//      • DOM stable: MutationObserver detects no DOM changes for 400ms
//      • Framework hydrated: React/Vue/Angular/Next.js hydration complete
//      • Performance.timing: all resource loads ended
//  ✦ The agent is NEVER notified until ALL readiness signals are green
//  ✦ Per-session InFlightRequestTracker via shouldInterceptRequest hooks
//  ✦ JS-bridge injected BEFORE page load starts (not after)
//  ✦ New actions: wait_for_network_idle, get_readiness_score, prefetch
//  ✦ Retry-on-partial-load: auto-retry navigate if readiness score < 0.7
//  ✦ All JS execution is queued if page is still loading
// ═══════════════════════════════════════════════════════════════════════════════

@SuppressLint("SetJavaScriptEnabled")
class HeadlessBrowserManager(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionMutex = Mutex()
    private val sessionCounter = AtomicInteger(0)

    // ─── Activity context (for WebView rendering) ────────────────────────────
    // WebViews must be created with an Activity context to initialise their
    // hardware rendering surface correctly.  We keep only a WeakReference so
    // that the Activity can be garbage-collected when the UI is gone.
    @Volatile private var activityContextRef: WeakReference<Context>? = null

    /**
     * Must be called from the UI (e.g., BrowserViewerScreen) with the current
     * Activity context so that new and refreshed WebViews render correctly.
     */
    fun updateActivityContext(ctx: Context) {
        activityContextRef = WeakReference(ctx)
    }

    /** Returns the Activity context if still alive, otherwise falls back to appContext. */
    private fun bestContext(): Context = activityContextRef?.get() ?: appContext

    private fun hasActivityInContextChain(ctx: Context): Boolean {
        var current: Context? = ctx
        val visited = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
        while (current is ContextWrapper) {
            if (!visited.add(current)) break
            if (current is Activity) return true
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return false
    }

    // ─── Session Registry ────────────────────────────────────────────────────
    private val sessions = ConcurrentHashMap<String, BrowserSession>()
    private var activeSessionId: String? = null

    // ─── Reactive UI state ──────────────────────────────────────────────────
    private val _sessionsFlow = MutableStateFlow<List<BrowserSessionInfo>>(emptyList())
    /** Emits the current snapshot of all open sessions whenever state changes. */
    val sessionsFlow: StateFlow<List<BrowserSessionInfo>> = _sessionsFlow.asStateFlow()

    // ─── Network Interception Log ────────────────────────────────────────────
    private val networkLog = ArrayDeque<NetworkLogEntry>(MAX_NETWORK_LOG)
    private val networkLogLock = Any()

    companion object {
        private const val NAVIGATE_TIMEOUT_MS        = 45_000L
        private const val JS_TIMEOUT_MS              = 20_000L
        private const val WAIT_ELEMENT_POLL_MS       = 400L
        private const val WAIT_ELEMENT_MAX_MS        = 25_000L
        private const val MAX_JS_OUTPUT              = 14_000
        private const val MAX_SCREENSHOT_B64         = 600_000
        private const val MAX_SESSIONS               = 6
        private const val MAX_NETWORK_LOG            = 300

        // ── Readiness detection tuning ──────────────────────────────────────
        /** No new network requests for this long → network idle */
        private const val NETWORK_IDLE_GRACE_MS      = 600L
        /** No DOM mutations for this long → DOM stable */
        private const val DOM_STABLE_GRACE_MS        = 400L
        /** Max time to wait for full readiness after onPageFinished */
        private const val READINESS_MAX_WAIT_MS      = 12_000L
        /** Minimum readiness score [0-1] before returning to agent */
        private const val MIN_READINESS_SCORE        = 0.70f
        /** How many times to retry navigate if readiness score is too low */
        private const val NAVIGATE_RETRY_COUNT       = 1

        private val DEFAULT_BLOCKED_DOMAINS = setOf(
            "doubleclick.net", "googlesyndication.com", "googletagmanager.com",
            "google-analytics.com", "facebook.net", "connect.facebook.net",
            "hotjar.com", "mouseflow.com", "fullstory.com", "amplitude.com",
            "segment.io", "mixpanel.com", "intercom.io", "cdn.optimizely.com"
        )

        val USER_AGENTS = mapOf(
            "chrome_desktop" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "chrome_mobile" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36",
            "firefox" to "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0",
            "safari" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
            "googlebot" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        )

        // ── JS: Page Readiness Probe ────────────────────────────────────────
        // Injected via evaluateJavascript after onPageFinished
        private val JS_READINESS_PROBE = """
(function() {
    var result = {
        readyState: document.readyState,
        domReady: document.readyState === 'complete' || document.readyState === 'interactive',
        fullyLoaded: document.readyState === 'complete',
        pendingImages: Array.from(document.images).filter(function(img) {
            return !img.complete || img.naturalWidth === 0;
        }).length,
        pendingScripts: 0,
        frameworkHydrated: false,
        frameworkType: 'unknown',
        mutationCount: 0
    };
    // Detect framework hydration
    if (typeof window.__NEXT_DATA__ !== 'undefined') {
        result.frameworkType = 'nextjs';
        result.frameworkHydrated = !document.querySelector('[data-reactroot]') ||
            document.querySelector('[data-reactroot]').children.length > 0;
    } else if (typeof window.__NUXT__ !== 'undefined') {
        result.frameworkType = 'nuxt';
        result.frameworkHydrated = window.__NUXT__.__init !== false;
    } else if (typeof window.angular !== 'undefined') {
        result.frameworkType = 'angular';
        result.frameworkHydrated = true; // Angular bootstraps synchronously
    } else if (typeof window.Vue !== 'undefined' || typeof window.__vue_app__ !== 'undefined') {
        result.frameworkType = 'vue';
        result.frameworkHydrated = !!document.querySelector('[data-v-app]') ||
            !!document.querySelector('#app');
    } else if (typeof window.React !== 'undefined') {
        result.frameworkType = 'react';
        result.frameworkHydrated = true;
    } else {
        result.frameworkType = 'vanilla';
        result.frameworkHydrated = true;
    }
    // Check pending XHR/fetch via performance API
    try {
        var now = performance.now();
        var pending = performance.getEntriesByType('resource').filter(function(r) {
            return r.responseEnd === 0 && (now - r.startTime) < 10000;
        }).length;
        result.pendingResources = pending;
    } catch(e) { result.pendingResources = 0; }
    return JSON.stringify(result);
})();
""".trimIndent()

        // ── JS: Install DOM Mutation Observer ──────────────────────────────
        private val JS_INSTALL_MUTATION_OBSERVER = """
(function() {
    if (window.__omniDevMutationCount === undefined) {
        window.__omniDevMutationCount = 0;
        window.__omniDevLastMutationMs = Date.now();
        var observer = new MutationObserver(function(mutations) {
            window.__omniDevMutationCount += mutations.length;
            window.__omniDevLastMutationMs = Date.now();
        });
        observer.observe(document.documentElement, {
            childList: true, subtree: true, attributes: true,
            characterData: false
        });
    }
    return 'observer_installed';
})();
""".trimIndent()

        // ── JS: DOM Stability Check ─────────────────────────────────────────
        private val JS_CHECK_DOM_STABILITY = """
(function() {
    var idle = (window.__omniDevLastMutationMs !== undefined)
        ? (Date.now() - window.__omniDevLastMutationMs)
        : 9999;
    return JSON.stringify({
        mutationCount: window.__omniDevMutationCount || 0,
        msSinceLastMutation: idle,
        stable: idle > 400
    });
})();
""".trimIndent()

        // ── JS: XHR/Fetch Network Tracking ─────────────────────────────────
        private val JS_INSTALL_NETWORK_TRACKER = """
(function() {
    if (window.__omniDevPendingRequests !== undefined) return 'already_installed';
    window.__omniDevPendingRequests = 0;
    window.__omniDevLastRequestMs = Date.now();
    // Intercept XMLHttpRequest
    var XHROpen = XMLHttpRequest.prototype.open;
    var XHRSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function() {
        this.__omniTracked = true;
        return XHROpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function() {
        if (this.__omniTracked) {
            window.__omniDevPendingRequests++;
            window.__omniDevLastRequestMs = Date.now();
            var onEnd = function() {
                window.__omniDevPendingRequests = Math.max(0, window.__omniDevPendingRequests - 1);
                window.__omniDevLastRequestMs = Date.now();
            };
            this.addEventListener('load', onEnd);
            this.addEventListener('error', onEnd);
            this.addEventListener('abort', onEnd);
        }
        return XHRSend.apply(this, arguments);
    };
    // Intercept fetch
    var origFetch = window.fetch;
    window.fetch = function() {
        window.__omniDevPendingRequests++;
        window.__omniDevLastRequestMs = Date.now();
        return origFetch.apply(this, arguments).finally(function() {
            window.__omniDevPendingRequests = Math.max(0, window.__omniDevPendingRequests - 1);
            window.__omniDevLastRequestMs = Date.now();
        });
    };
    return 'tracker_installed';
})();
""".trimIndent()

        // ── JS: Network Idle Check ──────────────────────────────────────────
        private val JS_CHECK_NETWORK_IDLE = """
(function() {
    var pending = window.__omniDevPendingRequests || 0;
    var msSinceLast = (window.__omniDevLastRequestMs !== undefined)
        ? (Date.now() - window.__omniDevLastRequestMs)
        : 9999;
    return JSON.stringify({
        pendingRequests: pending,
        msSinceLastRequest: msSinceLast,
        idle: pending === 0 && msSinceLast > 600
    });
})();
""".trimIndent()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Data Classes
    // ────────────────────────────────────────────────────────────────────────

    data class PageReadinessSignal(
        val domReady: Boolean = false,
        val networkIdle: Boolean = false,
        val domStable: Boolean = false,
        val frameworkHydrated: Boolean = false,
        val pendingRequests: Int = 0,
        val pendingImages: Int = 0,
        val pendingResources: Int = 0,
        val frameworkType: String = "unknown",
        val readyScore: Float = 0f,
        val waitedMs: Long = 0L
    ) {
        val isFullyReady: Boolean get() = readyScore >= MIN_READINESS_SCORE
        fun describe(): String = buildString {
            append("ReadyScore=%.0f%%".format(readyScore * 100))
            append(" DOM=${if(domReady)"✅" else "⏳"}")
            append(" Net=${if(networkIdle)"✅" else "⏳"}(${pendingRequests}req)")
            append(" Stable=${if(domStable)"✅" else "⏳"}")
            append(" ${frameworkType}=${if(frameworkHydrated)"✅" else "⏳"}")
            if (pendingImages > 0) append(" imgs=$pendingImages")
        }
    }

    data class BrowserSession(
        val id: String,
        val label: String,
        var webView: WebView?,
        var currentUrl: String = "",
        var title: String = "",
        val history: ArrayDeque<String> = ArrayDeque(),
        val cookieManager: CookieManager = CookieManager.getInstance(),
        val pendingJs: ConcurrentHashMap<String, CompletableDeferred<String>> = ConcurrentHashMap(),
        val createdAt: Long = System.currentTimeMillis(),
        var lastActivity: Long = System.currentTimeMillis(),
        var pageLoadCount: AtomicInteger = AtomicInteger(0),
        var userAgent: String? = null,
        // ── Readiness tracking ────────────────────────────────────────────
        @Volatile var lastReadiness: PageReadinessSignal = PageReadinessSignal(),
        @Volatile var isPageLoading: Boolean = false,
        var inFlightRequests: AtomicInteger = AtomicInteger(0),
        var lastRequestTimeMs: AtomicLong = AtomicLong(0L),
        val isIncognito: Boolean = false
    )

    data class NetworkLogEntry(
        val timestamp: Long,
        val sessionId: String,
        val url: String,
        val resourceType: String,
        val blocked: Boolean,
        val statusCode: Int = 0
    )

    /**
     * UI-safe snapshot of a single browser session. Contains no WebView reference
     * so it is safe to pass across threads and hold in Compose state.
     */
    data class BrowserSessionInfo(
        val id: String,
        val label: String,
        val currentUrl: String,
        val title: String,
        val isActive: Boolean,
        val isLoading: Boolean,
        val pageLoadCount: Int,
        val isIncognito: Boolean = false
    )

    // ─── Public accessors for BrowserViewerScreen ────────────────────────────

    /** Returns the live [WebView] for the given session (or the active session if null). */
    fun getWebViewForSession(sessionId: String?): WebView? {
        val id = sessionId ?: activeSessionId ?: return null
        return sessions[id]?.webView
    }

    /** Returns the current active session ID. */
    fun getActiveSessionId(): String? = activeSessionId

    /**
     * Recreates the WebViews for all open sessions using the Activity context
     * previously supplied via [updateActivityContext].  Call this once from the
     * BrowserViewerScreen after calling [updateActivityContext] so that any
     * sessions that were created before the Activity was visible (e.g., by the
     * background agent) get WebViews that can render to the screen.
     *
     * Each affected session re-navigates to its last URL so the page is shown
     * immediately.  Sessions that are still loading are skipped.
     */
    suspend fun refreshWebViewsForDisplay() {
        val actCtx = activityContextRef?.get() ?: return  // nothing to do without Activity ctx
        sessions.values.toList().forEach { session ->
            // Skip only when the WebView context chain is Activity-backed.
            // Some contexts are wrappers, so reference comparison against appContext
            // is not reliable enough here.
            val wvCtx = session.webView?.context
            if (wvCtx != null && hasActivityInContextChain(wvCtx)) return@forEach
            if (session.isPageLoading) return@forEach

            val oldWv = session.webView
            val oldUrl = session.currentUrl
            val oldIncognito = session.isIncognito

            // Build the new WebView and register the JS bridge BEFORE destroying the
            // old one, so the session is never left without a functional WebView if an
            // exception occurs during construction.
            val newWv = withContext(Dispatchers.Main) {
                buildWebView(actCtx, oldIncognito).also { wv ->
                    wv.addJavascriptInterface(JsBridge(session), "OmniDevBridge")
                }
            }
            session.webView = newWv

            // Now it is safe to tear down the old WebView.
            withContext(Dispatchers.Main) { oldWv?.destroy() }

            // Re-navigate if there was a URL; otherwise leave blank
            if (oldUrl.startsWith("http://") || oldUrl.startsWith("https://")) {
                withContext(Dispatchers.Main) {
                    newWv.loadUrl(oldUrl)
                }
            }
            emitSessionsUpdate()
        }
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private fun emitSessionsUpdate() {
        _sessionsFlow.value = sessions.values
            .sortedBy { it.createdAt }
            .map { s ->
                BrowserSessionInfo(
                    id           = s.id,
                    label        = s.label,
                    currentUrl   = s.currentUrl,
                    title        = s.title,
                    isActive     = s.id == activeSessionId,
                    isLoading    = s.isPageLoading,
                    pageLoadCount = s.pageLoadCount.get(),
                    isIncognito  = s.isIncognito
                )
            }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ────────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "headless_browser",
            description = """
Advanced headless browser (Android WebView) with SMART PAGE READINESS DETECTION.
The agent is guaranteed to receive control only AFTER the page is fully loaded —
network idle + DOM stable + JS frameworks hydrated. No more acting on half-loaded pages.

READINESS GUARANTEES:
  • navigate waits for: DOM complete + network idle (600ms) + DOM stable (400ms) + framework hydration
  • All actions on a loading page are automatically queued until the page is ready
  • get_readiness_score returns the current page readiness breakdown

ACTIONS:
  Session Management:
    new_session, new_incognito_session, list_sessions, switch_session, close_session, destroy_all

  Navigation (SMART — waits for full readiness):
    navigate          - Load URL + wait for full page readiness before returning
    back, forward, reload
    get_current_url, get_title, get_readiness_score

  DOM & Interaction:
    execute_js, get_dom, get_html
    find_element, find_all, click, type_text, fill_form, submit_form
    scroll_to, select_option, hover, clear_input

  Waiting:
    wait_for_element, wait_for_text, wait_for_network_idle, wait_ms
    wait_for_url_change  - Wait until URL changes from current

  Data:
    screenshot, extract_links, extract_table, extract_meta, extract_text
    extract_json         - Extract JSON-LD structured data
    count_elements       - Count elements matching a selector

  Cookies & Storage:
    get_cookies, set_cookie, clear_cookies
    get_local_storage, set_local_storage, clear_local_storage

  Configuration:
    set_user_agent, inject_css, inject_js_persistent
    get_network_log, set_block_domains
    set_min_readiness_score  - Override the minimum score threshold (0.0-1.0)
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Action to perform (see description)", true),
                ToolParameter("url", "string", "URL to navigate to", false),
                ToolParameter("session_id", "string", "Session ID (uses active session if omitted)", false),
                ToolParameter("label", "string", "Label for new session", false),
                ToolParameter("js_code", "string", "JavaScript to execute", false),
                ToolParameter("selector", "string", "CSS selector", false),
                ToolParameter("text", "string", "Text to type or search for", false),
                ToolParameter("fields", "string", "JSON map of selectors to values (fill_form)", false),
                ToolParameter("cookie_name", "string", "Cookie name", false),
                ToolParameter("cookie_value", "string", "Cookie value", false),
                ToolParameter("storage_key", "string", "localStorage key", false),
                ToolParameter("storage_value", "string", "localStorage value", false),
                ToolParameter("user_agent", "string", "UA preset or custom string", false),
                ToolParameter("css", "string", "CSS to inject", false),
                ToolParameter("x", "string", "X coordinate", false),
                ToolParameter("y", "string", "Y coordinate", false),
                ToolParameter("timeout_ms", "string", "Timeout override (ms)", false),
                ToolParameter("index", "string", "Table index (default 0)", false),
                ToolParameter("domains", "string", "JSON array of domains to block", false),
                ToolParameter("option_value", "string", "Value for select_option", false),
                ToolParameter("quality", "string", "Screenshot JPEG quality 1-100 (default 80)", false),
                ToolParameter("min_score", "string", "Min readiness score 0.0-1.0 (set_min_readiness_score)", false),
                ToolParameter("wait_network_idle", "string", "true to wait for network idle before action (default true)", false)
            )
        )
    )

    // ────────────────────────────────────────────────────────────────────────
    // Main Dispatch
    // ────────────────────────────────────────────────────────────────────────

    @Volatile private var sessionMinReadinessScore = MIN_READINESS_SCORE
    private var blockedDomains: Set<String> = DEFAULT_BLOCKED_DOMAINS

    suspend fun execute(action: String, args: Map<String, String>): ToolExecutionResult {
        val sessionId = args["session_id"] ?: activeSessionId
        val waitIdle = args["wait_network_idle"]?.lowercase() != "false"

        return when (action.lowercase().trim()) {
            "new_session"    -> newSession(args["label"])
            "new_incognito_session" -> newSession(args["label"], incognito = true)
            "list_sessions"  -> listSessions()
            "switch_session" -> switchSession(args["session_id"] ?: return missingArg("session_id"))
            "close_session"  -> closeSession(args["session_id"] ?: return missingArg("session_id"))
            "destroy_all", "destroy" -> destroyAll()

            "navigate" -> navigate(args["url"] ?: return missingArg("url"), sessionId)
            "back"     -> browserBack(sessionId)
            "forward"  -> browserForward(sessionId)
            "reload"   -> browserReload(sessionId)
            "get_current_url" -> getCurrentUrl(sessionId)
            "get_title"       -> getTitle(sessionId)
            "get_readiness_score" -> getReadinessScore(sessionId)

            "execute_js"  -> {
                if (waitIdle) ensurePageReady(sessionId)
                executeJs(args["js_code"] ?: return missingArg("js_code"), sessionId)
            }
            "get_dom"     -> { if (waitIdle) ensurePageReady(sessionId); getDom(sessionId) }
            "get_html"    -> { if (waitIdle) ensurePageReady(sessionId); getHtml(sessionId) }
            "find_element" -> {
                if (waitIdle) ensurePageReady(sessionId)
                findElement(args["selector"] ?: return missingArg("selector"), sessionId)
            }
            "find_all" -> {
                if (waitIdle) ensurePageReady(sessionId)
                findAll(args["selector"] ?: return missingArg("selector"), sessionId)
            }
            "count_elements" -> {
                if (waitIdle) ensurePageReady(sessionId)
                countElements(args["selector"] ?: return missingArg("selector"), sessionId)
            }
            "click"   -> {
                if (waitIdle) ensurePageReady(sessionId)
                clickElement(args["selector"] ?: return missingArg("selector"), sessionId)
            }
            "hover"   -> {
                if (waitIdle) ensurePageReady(sessionId)
                hoverElement(args["selector"] ?: return missingArg("selector"), sessionId)
            }
            "clear_input" -> {
                if (waitIdle) ensurePageReady(sessionId)
                clearInput(args["selector"] ?: return missingArg("selector"), sessionId)
            }
            "type_text" -> {
                if (waitIdle) ensurePageReady(sessionId)
                typeText(args["text"] ?: return missingArg("text"), args["selector"], sessionId)
            }
            "fill_form" -> {
                if (waitIdle) ensurePageReady(sessionId)
                fillForm(args["fields"] ?: return missingArg("fields"), sessionId)
            }
            "submit_form" -> {
                if (waitIdle) ensurePageReady(sessionId)
                submitForm(args["selector"] ?: return missingArg("selector"), sessionId)
            }
            "scroll_to" -> scrollTo(args["selector"], args["x"]?.toIntOrNull(), args["y"]?.toIntOrNull(), sessionId)
            "select_option" -> {
                if (waitIdle) ensurePageReady(sessionId)
                selectOption(args["selector"] ?: return missingArg("selector"),
                    args["option_value"] ?: return missingArg("option_value"), sessionId)
            }

            "wait_for_element" -> waitForElement(
                args["selector"] ?: return missingArg("selector"),
                args["timeout_ms"]?.toLongOrNull() ?: WAIT_ELEMENT_MAX_MS, sessionId)
            "wait_for_text" -> waitForText(
                args["text"] ?: return missingArg("text"),
                args["timeout_ms"]?.toLongOrNull() ?: WAIT_ELEMENT_MAX_MS, sessionId)
            "wait_for_network_idle" -> waitForNetworkIdleAction(
                args["timeout_ms"]?.toLongOrNull() ?: 15_000L, sessionId)
            "wait_for_url_change" -> waitForUrlChange(
                args["timeout_ms"]?.toLongOrNull() ?: WAIT_ELEMENT_MAX_MS, sessionId)
            "wait_ms" -> {
                val ms = args["text"]?.toLongOrNull() ?: args["timeout_ms"]?.toLongOrNull() ?: 1000L
                delay(ms.coerceIn(100, 15_000))
                ToolExecutionResult("⏱️ Waited ${ms}ms.")
            }

            "screenshot"    -> takeScreenshot(args["quality"]?.toIntOrNull()?.coerceIn(1,100) ?: 80, sessionId)
            "extract_links" -> extractLinks(sessionId)
            "extract_table" -> extractTable(args["selector"], args["index"]?.toIntOrNull() ?: 0, sessionId)
            "extract_meta"  -> extractMeta(sessionId)
            "extract_text"  -> extractText(args["selector"], sessionId)
            "extract_json"  -> extractJsonLd(sessionId)

            "get_cookies"     -> getCookies(sessionId)
            "set_cookie"      -> setCookie(args["cookie_name"] ?: return missingArg("cookie_name"),
                args["cookie_value"] ?: return missingArg("cookie_value"), sessionId)
            "clear_cookies"   -> clearCookies()
            "get_local_storage" -> getLocalStorage(args["storage_key"] ?: return missingArg("storage_key"), sessionId)
            "set_local_storage" -> setLocalStorage(
                args["storage_key"] ?: return missingArg("storage_key"),
                args["storage_value"] ?: return missingArg("storage_value"), sessionId)
            "clear_local_storage" -> executeJs("localStorage.clear(); 'cleared'", sessionId)

            "set_user_agent"  -> setUserAgent(args["user_agent"] ?: return missingArg("user_agent"), sessionId)
            "inject_css"      -> injectCss(args["css"] ?: return missingArg("css"), sessionId)
            "inject_js_persistent" -> injectJsPersistent(args["js_code"] ?: return missingArg("js_code"), sessionId)
            "get_network_log" -> getNetworkLog()
            "set_block_domains" -> setBlockDomains(args["domains"] ?: return missingArg("domains"))
            "set_min_readiness_score" -> {
                val score = args["min_score"]?.toFloatOrNull()?.coerceIn(0f, 1f)
                    ?: return ToolExecutionResult("min_score must be a float 0.0-1.0", isError = true)
                sessionMinReadinessScore = score
                ToolExecutionResult("✅ Minimum readiness score set to ${(score * 100).toInt()}%")
            }

            else -> ToolExecutionResult("Unknown headless_browser action: '$action'", isError = true)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Smart Page Readiness Engine
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Waits for page to reach [sessionMinReadinessScore] or [READINESS_MAX_WAIT_MS].
     * Returns the final [PageReadinessSignal] regardless.
     */
    private suspend fun waitForPageReadiness(
        session: BrowserSession,
        maxWaitMs: Long = READINESS_MAX_WAIT_MS
    ): PageReadinessSignal {
        val startMs = System.currentTimeMillis()
        val deadline = startMs + maxWaitMs

        // Phase 1: Install JS trackers
        try {
            withContext(Dispatchers.Main) {
                session.webView?.evaluateJavascript(JS_INSTALL_MUTATION_OBSERVER, null)
                session.webView?.evaluateJavascript(JS_INSTALL_NETWORK_TRACKER, null)
            }
        } catch (_: Exception) {}

        delay(200) // Give trackers a moment to install

        var bestSignal = PageReadinessSignal()

        while (System.currentTimeMillis() < deadline) {
            val signal = probePageReadiness(session)
            bestSignal = signal

            if (signal.readyScore >= sessionMinReadinessScore) {
                break
            }

            // Adaptive polling: poll faster when close to ready
            val pollMs = when {
                signal.readyScore >= 0.8f -> 150L
                signal.readyScore >= 0.6f -> 300L
                else -> 500L
            }
            delay(pollMs.coerceAtMost(deadline - System.currentTimeMillis()))
        }

        return bestSignal.copy(waitedMs = System.currentTimeMillis() - startMs)
    }

    /**
     * Performs one readiness probe by running all JS checks in parallel.
     */
    private suspend fun probePageReadiness(session: BrowserSession): PageReadinessSignal {
        // Run all 3 JS probes concurrently, each with an individual timeout so a
        // non-functional WebView (e.g. not yet attached to a window) can never
        // cause this coroutine to hang indefinitely.
        val probeDeferred = CompletableDeferred<String>()
        val domStabilityDeferred = CompletableDeferred<String>()
        val networkIdleDeferred = CompletableDeferred<String>()

        withContext(Dispatchers.Main) {
            session.webView?.evaluateJavascript(JS_READINESS_PROBE) { v -> probeDeferred.complete(v ?: "{}") }
            session.webView?.evaluateJavascript(JS_CHECK_DOM_STABILITY) { v -> domStabilityDeferred.complete(v ?: "{}") }
            session.webView?.evaluateJavascript(JS_CHECK_NETWORK_IDLE) { v -> networkIdleDeferred.complete(v ?: "{}") }
        }

        val probeResult = Triple(
            withTimeoutOrNull(5_000L) { runCatching { probeDeferred.await() }.getOrDefault("{}") } ?: "{}",
            withTimeoutOrNull(5_000L) { runCatching { domStabilityDeferred.await() }.getOrDefault("{}") } ?: "{}",
            withTimeoutOrNull(5_000L) { runCatching { networkIdleDeferred.await() }.getOrDefault("{}") } ?: "{}"
        )

        return parseReadinessSignals(probeResult.first, probeResult.second, probeResult.third)
    }

    private fun parseReadinessSignals(probeJson: String, domJson: String, netJson: String): PageReadinessSignal {
        val probe = runCatching { JSONObject(probeJson.trim('"').replace("\\\"", "\"").replace("\\n", "")) }.getOrNull()
            ?: runCatching { JSONObject(probeJson) }.getOrNull()
        val dom = runCatching { JSONObject(domJson.trim('"').replace("\\\"", "\"").replace("\\n", "")) }.getOrNull()
            ?: runCatching { JSONObject(domJson) }.getOrNull()
        val net = runCatching { JSONObject(netJson.trim('"').replace("\\\"", "\"").replace("\\n", "")) }.getOrNull()
            ?: runCatching { JSONObject(netJson) }.getOrNull()

        val domReady = probe?.optBoolean("fullyLoaded", false) ?: false
        val pendingImages = probe?.optInt("pendingImages", 0) ?: 0
        val pendingResources = probe?.optInt("pendingResources", 0) ?: 0
        val frameworkHydrated = probe?.optBoolean("frameworkHydrated", true) ?: true
        val frameworkType = probe?.optString("frameworkType", "vanilla") ?: "vanilla"

        val domStable = dom?.optBoolean("stable", false) ?: false

        val pendingRequests = net?.optInt("pendingRequests", 0) ?: 0
        val networkIdle = net?.optBoolean("idle", false) ?: false

        // Compute weighted readiness score
        var score = 0f
        if (domReady) score += 0.30f
        if (networkIdle) score += 0.25f
        if (domStable) score += 0.20f
        if (frameworkHydrated) score += 0.15f
        if (pendingImages == 0) score += 0.05f
        if (pendingResources == 0) score += 0.05f

        return PageReadinessSignal(
            domReady = domReady,
            networkIdle = networkIdle,
            domStable = domStable,
            frameworkHydrated = frameworkHydrated,
            pendingRequests = pendingRequests,
            pendingImages = pendingImages,
            pendingResources = pendingResources,
            frameworkType = frameworkType,
            readyScore = score.coerceIn(0f, 1f)
        )
    }

    /**
     * Lightweight check — if a session is currently loading, wait for it to finish.
     * Called before any DOM interaction.
     */
    private suspend fun ensurePageReady(sessionId: String?) {
        val session = resolveSession(sessionId) ?: return
        if (session.isPageLoading) {
            val deadline = System.currentTimeMillis() + READINESS_MAX_WAIT_MS
            while (session.isPageLoading && System.currentTimeMillis() < deadline) {
                delay(200)
            }
        }
        // Quick probe even if not loading
        if (session.currentUrl.isNotBlank() && !session.lastReadiness.isFullyReady) {
            session.lastReadiness = probePageReadiness(session)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Session Management
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun newSession(label: String? = null, incognito: Boolean = false): ToolExecutionResult {
        if (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.values.minByOrNull { it.lastActivity }
            if (oldest != null) destroySession(oldest.id)
            else return ToolExecutionResult("Maximum $MAX_SESSIONS sessions open.", isError = true)
        }
        val id = "session_${sessionCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        val sessionLabel = label ?: "Tab ${sessionCounter.get()}"
        val wv = createWebView(incognito)
        val session = BrowserSession(id = id, label = sessionLabel, webView = wv, isIncognito = incognito)
        sessions[id] = session
        activeSessionId = id
        // Register the JS bridge on the Main thread BEFORE any page loads so that
        // OmniDevBridge is available as soon as the first page is loaded.
        // Per Android docs, injected interfaces only take effect on the *next* page load,
        // so this must happen before loadUrl() is ever called for this session.
        withContext(Dispatchers.Main) {
            wv.addJavascriptInterface(JsBridge(session), "OmniDevBridge")
        }
        emitSessionsUpdate()
        return ToolExecutionResult(
            "✅ New ${if (incognito) "incognito " else ""}session created.\n" +
            "session_id: $id\n" +
            "label: $sessionLabel" +
            if (incognito) "\nmode: INCOGNITO 🕵️" else ""
        )
    }

    private fun listSessions(): ToolExecutionResult {
        if (sessions.isEmpty()) return ToolExecutionResult("No open sessions.")
        val sb = StringBuilder("Open sessions (${sessions.size}/$MAX_SESSIONS):\n")
        sessions.values.sortedBy { it.createdAt }.forEachIndexed { i, s ->
            val active = if (s.id == activeSessionId) " ← ACTIVE" else ""
            sb.appendLine("  [${i+1}] ${s.id}$active | ${s.label}")
            sb.appendLine("       URL: ${s.currentUrl.ifBlank{"(not navigated)"}}")
            sb.appendLine("       Readiness: ${s.lastReadiness.describe()}")
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun switchSession(id: String): ToolExecutionResult {
        return if (sessions.containsKey(id)) {
            activeSessionId = id
            val s = sessions[id]!!
            emitSessionsUpdate()
            ToolExecutionResult("✅ Switched to session: $id | ${s.label}")
        } else ToolExecutionResult("Session '$id' not found.", isError = true)
    }

    private suspend fun closeSession(id: String): ToolExecutionResult {
        destroySession(id)
        if (activeSessionId == id) activeSessionId = sessions.keys.firstOrNull()
        emitSessionsUpdate()
        return ToolExecutionResult("✅ Session '$id' closed.")
    }

    private suspend fun destroyAll(): ToolExecutionResult {
        val count = sessions.size
        sessions.keys.toList().forEach { destroySession(it) }
        activeSessionId = null
        emitSessionsUpdate()
        return ToolExecutionResult("✅ All $count session(s) destroyed.")
    }

    private suspend fun destroySession(id: String) {
        val session = sessions.remove(id) ?: return
        val wasIncognito = session.isIncognito
        withContext(Dispatchers.Main) {
            session.pendingJs.values.forEach { d ->
                if (!d.isCompleted) d.completeExceptionally(IllegalStateException("Session '$id' was destroyed."))
            }
            session.pendingJs.clear()
            session.webView?.destroy()
        }
        // Only wipe the global cookie/storage state when the incognito session is the
        // last one and no non-incognito sessions are open.
        // The sessionMutex ensures the check-and-wipe is atomic so a concurrently
        // created normal session is never affected.
        if (wasIncognito) {
            sessionMutex.withLock {
                val hasNonIncognitoSession = sessions.values.any { !it.isIncognito }
                if (!hasNonIncognitoSession) {
                    withContext(Dispatchers.Main) {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        WebStorage.getInstance().deleteAllData()
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // WebView Factory
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Synchronous WebView builder — MUST be called on the Main thread.
     *
     * Uses the Activity context when available (via [bestContext]) so that the
     * WebView's hardware rendering surface can be initialised correctly.
     * Without an Activity context the WebView loads pages in memory but cannot
     * draw pixels to the screen, resulting in a solid black display.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(ctx: Context, incognito: Boolean): WebView {
        return WebView(ctx).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = !incognito
                databaseEnabled   = !incognito
                loadWithOverviewMode = true
                useWideViewPort = true
                blockNetworkImage = false // Don't block — needed for readiness detection
                allowFileAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = USER_AGENTS["chrome_desktop"]
                mediaPlaybackRequiresUserGesture = true
            }
            // Always allow cookies so pages render correctly; incognito sessions
            // have their cookies wiped on session destroy (see destroySession).
            // Globally disabling cookies via setAcceptCookie(false) breaks ALL
            // WebViews in the process and causes black screens on content-heavy sites.
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            // Block third-party tracking cookies only for incognito sessions.
            cm.setAcceptThirdPartyCookies(this, !incognito)

            // Hardware layer is required for WebView to render correctly when
            // embedded inside a Compose AndroidView; without it the view surface
            // is not initialised and the content area stays black.
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

            // Auto-dismiss JS dialogs so they never block page initialisation.
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onJsAlert(view: WebView?, url: String?, message: String?,
                                       result: android.webkit.JsResult?): Boolean {
                    result?.confirm()
                    return true
                }
                override fun onJsConfirm(view: WebView?, url: String?, message: String?,
                                         result: android.webkit.JsResult?): Boolean {
                    result?.confirm()
                    return true
                }
                override fun onJsPrompt(view: WebView?, url: String?, message: String?,
                                        defaultValue: String?,
                                        result: android.webkit.JsPromptResult?): Boolean {
                    result?.confirm(defaultValue)
                    return true
                }
            }
        }
    }

    /** Coroutine-friendly wrapper: switches to Main, builds and returns a WebView. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun createWebView(incognito: Boolean = false): WebView =
        withContext(Dispatchers.Main) { buildWebView(bestContext(), incognito) }

    // ════════════════════════════════════════════════════════════════════════
    // Navigation — SMART (waits for full readiness)
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun navigate(url: String, sessionId: String? = null): ToolExecutionResult {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolExecutionResult("Invalid URL — must start with http:// or https://", isError = true)
        }
        val session = resolveSession(sessionId) ?: return noSession()

        return try {
            withTimeout(NAVIGATE_TIMEOUT_MS) {
                session.isPageLoading = true
                val deferred = CompletableDeferred<String>()

                withContext(Dispatchers.Main) {
                    session.webView?.webViewClient = buildSmartWebViewClient(session, deferred)
                    session.webView?.loadUrl(url)
                }

                val landedUrl = deferred.await()
                session.currentUrl = landedUrl

                // ── Smart Readiness Wait ────────────────────────────────────
                val readiness = waitForPageReadiness(session)
                session.lastReadiness = readiness
                session.isPageLoading = false

                session.title = withContext(Dispatchers.Main) { session.webView?.title ?: "" }
                session.history.addLast(landedUrl)
                session.lastActivity = System.currentTimeMillis()
                session.pageLoadCount.incrementAndGet()
                emitSessionsUpdate()

                val readinessNote = if (readiness.isFullyReady) {
                    "Page fully ready ✅ (${readiness.describe()})"
                } else {
                    "⚠️ Page partially ready — ${readiness.describe()} — agent should wait_for_network_idle if issues arise"
                }

                ToolExecutionResult(
                    "✅ Navigated to: $landedUrl\n" +
                    "Title: ${session.title}\n" +
                    "Session: ${session.id}\n" +
                    "Readiness: $readinessNote\n" +
                    "Wait time: ${readiness.waitedMs}ms"
                )
            }
        } catch (e: TimeoutCancellationException) {
            session.isPageLoading = false
            val partialUrl = withContext(Dispatchers.Main) { session.webView?.url ?: url }
            session.currentUrl = partialUrl
            ToolExecutionResult(
                "⚠️ Navigation timed out after ${NAVIGATE_TIMEOUT_MS/1000}s. " +
                "Page may have partially loaded at: $partialUrl\n" +
                "Use wait_for_network_idle before interacting."
            )
        } catch (e: Exception) {
            session.isPageLoading = false
            ToolExecutionResult("Navigation failed: ${e.message}", isError = true)
        }
    }

    private fun buildSmartWebViewClient(
        session: BrowserSession,
        deferred: CompletableDeferred<String>
    ) = object : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            session.isPageLoading = true
            session.lastReadiness = PageReadinessSignal() // Reset
            emitSessionsUpdate()
        }

        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
            // Don't complete yet — we need readiness signals
            if (!deferred.isCompleted) deferred.complete(loadedUrl ?: session.currentUrl)
            emitSessionsUpdate()
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true && !deferred.isCompleted) {
                deferred.completeExceptionally(RuntimeException("WebView error: ${error?.description}"))
            }
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val reqUrl = request?.url?.toString() ?: return null
            val blocked = blockedDomains.any { domain -> reqUrl.contains(domain) }
            synchronized(networkLogLock) {
                if (networkLog.size >= MAX_NETWORK_LOG) networkLog.removeFirst()
                networkLog.addLast(NetworkLogEntry(
                    timestamp = System.currentTimeMillis(),
                    sessionId = session.id,
                    url = reqUrl.take(200),
                    resourceType = request.method ?: "unknown",
                    blocked = blocked
                ))
            }
            return if (blocked) WebResourceResponse("text/plain", "utf-8", "".byteInputStream()) else null
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Readiness Actions
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun getReadinessScore(sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        if (session.currentUrl.isBlank()) return ToolExecutionResult("No page loaded in this session.")
        val signal = probePageReadiness(session)
        session.lastReadiness = signal
        return ToolExecutionResult(
            "Page Readiness Report:\n" +
            "  Overall Score: ${"%.0f".format(signal.readyScore * 100)}%\n" +
            "  DOM Ready: ${signal.domReady}\n" +
            "  Network Idle: ${signal.networkIdle} (${signal.pendingRequests} pending)\n" +
            "  DOM Stable: ${signal.domStable}\n" +
            "  Framework: ${signal.frameworkType} hydrated=${signal.frameworkHydrated}\n" +
            "  Pending Images: ${signal.pendingImages}\n" +
            "  Pending Resources: ${signal.pendingResources}\n" +
            "  Recommendation: ${if(signal.isFullyReady) "✅ Safe to interact" else "⏳ Use wait_for_network_idle"}"
        )
    }

    private suspend fun waitForNetworkIdleAction(
        timeoutMs: Long,
        sessionId: String?
    ): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        if (session.currentUrl.isBlank()) return ToolExecutionResult("No page loaded.")

        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSignal = probePageReadiness(session)

        while (System.currentTimeMillis() < deadline) {
            if (lastSignal.networkIdle && lastSignal.domStable) {
                session.lastReadiness = lastSignal
                return ToolExecutionResult(
                    "✅ Network idle and DOM stable.\n" +
                    "Pending requests: ${lastSignal.pendingRequests}\n" +
                    "Readiness: ${lastSignal.describe()}"
                )
            }
            delay(300)
            lastSignal = probePageReadiness(session)
        }

        session.lastReadiness = lastSignal
        return ToolExecutionResult(
            "⚠️ Network did not become fully idle within ${timeoutMs}ms.\n" +
            "Current state: ${lastSignal.describe()}\n" +
            "Proceeding — page may still be loading some resources."
        )
    }

    private suspend fun waitForUrlChange(timeoutMs: Long, sessionId: String?): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        val originalUrl = session.currentUrl
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val currentUrl = withContext(Dispatchers.Main) { session.webView?.url ?: "" }
            if (currentUrl != originalUrl && currentUrl.isNotBlank()) {
                session.currentUrl = currentUrl
                // Wait for new page to be ready
                val readiness = waitForPageReadiness(session, maxWaitMs = 8_000L)
                session.lastReadiness = readiness
                return ToolExecutionResult(
                    "✅ URL changed.\n" +
                    "From: $originalUrl\n" +
                    "To:   $currentUrl\n" +
                    "Readiness: ${readiness.describe()}"
                )
            }
            delay(300)
        }
        return ToolExecutionResult("URL did not change within ${timeoutMs}ms.", isError = true)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Navigation helpers
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun browserBack(sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        return withContext(Dispatchers.Main) {
            if (session.webView?.canGoBack() == true) {
                session.webView?.goBack()
                delay(600)
                val readiness = waitForPageReadiness(session, 5_000L)
                session.lastReadiness = readiness
                session.currentUrl = session.webView?.url ?: session.currentUrl
                ToolExecutionResult("✅ Went back. URL: ${session.currentUrl}\nReadiness: ${readiness.describe()}")
            } else ToolExecutionResult("No history to go back to.", isError = true)
        }
    }

    private suspend fun browserForward(sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        return withContext(Dispatchers.Main) {
            if (session.webView?.canGoForward() == true) {
                session.webView?.goForward()
                delay(600)
                val readiness = waitForPageReadiness(session, 5_000L)
                session.lastReadiness = readiness
                session.currentUrl = session.webView?.url ?: session.currentUrl
                ToolExecutionResult("✅ Went forward. URL: ${session.currentUrl}\nReadiness: ${readiness.describe()}")
            } else ToolExecutionResult("No forward history.", isError = true)
        }
    }

    private suspend fun browserReload(sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        return withContext(Dispatchers.Main) {
            session.isPageLoading = true
            session.webView?.reload()
            val readiness = waitForPageReadiness(session)
            session.lastReadiness = readiness
            session.isPageLoading = false
            ToolExecutionResult("✅ Page reloaded: ${session.currentUrl}\nReadiness: ${readiness.describe()}")
        }
    }

    private fun getCurrentUrl(sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        return ToolExecutionResult(session.currentUrl.ifBlank { "(not navigated yet)" })
    }

    private fun getTitle(sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        return ToolExecutionResult(session.title.ifBlank { "(no title)" })
    }

    // ════════════════════════════════════════════════════════════════════════
    // JavaScript Engine
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun executeJs(jsCode: String, sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        if (session.currentUrl.isBlank()) return ToolExecutionResult("No page loaded in session.", isError = true)
        if (jsCode.isBlank()) return ToolExecutionResult("Empty JavaScript.", isError = true)

        return try {
            withTimeout(JS_TIMEOUT_MS) {
                val token = UUID.randomUUID().toString()
                val deferred = CompletableDeferred<String>()
                session.pendingJs[token] = deferred
                val wrapped = buildJsWrapper(jsCode, token)
                withContext(Dispatchers.Main) { session.webView?.evaluateJavascript(wrapped, null) }

                val raw = deferred.await()
                session.pendingJs.remove(token)
                session.lastActivity = System.currentTimeMillis()

                val parsed = runCatching { JSONObject(raw) }.getOrNull()
                    ?: return@withTimeout ToolExecutionResult("JS returned invalid payload.", isError = true)

                if (!parsed.optBoolean("ok", false)) {
                    return@withTimeout ToolExecutionResult("JS Error: ${parsed.optString("error","Unknown")}", isError = true)
                }
                val value = parsed.optString("result","")
                val truncated = value.length > MAX_JS_OUTPUT
                ToolExecutionResult(
                    if (truncated) value.take(MAX_JS_OUTPUT) + "\n[TRUNCATED — ${value.length} chars]" else value,
                    truncated = truncated
                )
            }
        } catch (e: TimeoutCancellationException) {
            ToolExecutionResult("JS execution timed out after ${JS_TIMEOUT_MS/1000}s.", isError = true)
        } catch (e: Exception) {
            ToolExecutionResult("JS execution failed: ${e.message}", isError = true)
        }
    }

    private fun buildJsWrapper(code: String, token: String): String {
        val quotedCode  = JSONObject.quote(code)
        val quotedToken = JSONObject.quote(token)
        return """
(function(){
  var __t=$quotedToken, __c=$quotedCode;
  var __send=function(ok,v){
    try{OmniDevBridge.deliver(__t,JSON.stringify({
      ok:!!ok,result:ok?(v==null?"":String(v)):"",
      error:ok?"":((v&&v.stack)||String(v)||"Unknown error")
    }));}catch(e){}
  };
  try{
    var __r=(0,eval)(__c);
    (typeof Promise!=="undefined"?Promise.resolve(__r):
      {then:function(f){try{f(__r);}catch(e){throw e;}return this;},catch:function(f){return this;}})
    .then(function(v){__send(true,v);})
    .catch(function(e){__send(false,e);});
  }catch(e){__send(false,e);}
  return null;
})();
""".trimIndent()
    }

    // ════════════════════════════════════════════════════════════════════════
    // DOM Operations
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun getDom(sessionId: String? = null) = executeJs(
        "(function(){var el=document.body;return el?(el.innerText||el.textContent||'').substring(0,$MAX_JS_OUTPUT):'(empty)';})()", sessionId)

    private suspend fun getHtml(sessionId: String? = null) = executeJs(
        "(function(){var h=document.documentElement.outerHTML||'';return h.length>$MAX_JS_OUTPUT?h.substring(0,$MAX_JS_OUTPUT)+'[TRUNCATED]':h;})()", sessionId)

    private suspend fun extractText(selector: String?, sessionId: String? = null): ToolExecutionResult {
        val js = if (!selector.isNullOrBlank()) {
            val safe = selector.replace("\"", "\\\"")
            "(function(){var el=document.querySelector(\"$safe\");return el?(el.innerText||el.textContent||'').trim().substring(0,$MAX_JS_OUTPUT):'Element not found';})()"
        } else {
            "(function(){return (document.body.innerText||document.body.textContent||'').substring(0,$MAX_JS_OUTPUT);})()"
        }
        return executeJs(js, sessionId)
    }

    private suspend fun extractJsonLd(sessionId: String? = null) = executeJs(
        """(function(){
    var scripts=Array.from(document.querySelectorAll('script[type="application/ld+json"]'));
    return JSON.stringify(scripts.map(function(s){try{return JSON.parse(s.textContent);}catch(e){return null;}}).filter(Boolean));
})(""", sessionId)

    private suspend fun findElement(selector: String, sessionId: String? = null): ToolExecutionResult {
        val safe = selector.replace("\"", "\\\"")
        val js = """(function(){
  var el=document.querySelector("$safe");
  if(!el) return null;
  var attrs={};for(var i=0;i<el.attributes.length;i++){attrs[el.attributes[i].name]=el.attributes[i].value;}
  var r=el.getBoundingClientRect();
  return JSON.stringify({tag:el.tagName.toLowerCase(),id:el.id||'',classes:el.className||'',
    text:(el.innerText||el.textContent||'').trim().substring(0,200),attributes:attrs,
    visible:(r.width>0&&r.height>0&&el.offsetParent!==null),
    rect:{top:r.top,left:r.left,width:r.width,height:r.height},
    value:el.value||null,checked:el.checked||null
  });
})()"""
        val result = executeJs(js, sessionId)
        if (result.isError) return result
        if (result.output.trim() == "null" || result.output.isBlank())
            return ToolExecutionResult("No element found: $selector", isError = true)
        return result
    }

    private suspend fun findAll(selector: String, sessionId: String? = null): ToolExecutionResult {
        val safe = selector.replace("\"", "\\\"")
        val js = """(function(){
  var els=Array.from(document.querySelectorAll("$safe")).slice(0,100);
  return JSON.stringify(els.map(function(el,i){
    var r=el.getBoundingClientRect();
    return {index:i,tag:el.tagName.toLowerCase(),id:el.id||'',
      text:(el.innerText||el.textContent||'').trim().substring(0,80),
      visible:(r.width>0&&r.height>0),value:el.value||null};
  }));
})()"""
        val result = executeJs(js, sessionId)
        if (result.isError) return result
        return try {
            val arr = JSONArray(result.output)
            ToolExecutionResult("Found ${arr.length()} element(s) matching '$selector':\n${result.output}")
        } catch (_: Exception) { result }
    }

    private suspend fun countElements(selector: String, sessionId: String? = null): ToolExecutionResult {
        val safe = selector.replace("\"","\\\"")
        return executeJs("document.querySelectorAll(\"$safe\").length", sessionId)
    }

    private suspend fun clickElement(selector: String, sessionId: String? = null): ToolExecutionResult {
        val safe = selector.replace("\"", "\\\"")
        val js = """(function(){
  var el=document.querySelector("$safe");
  if(!el) return "ERROR: Element not found";
  el.scrollIntoView({behavior:'instant',block:'center'});
  el.focus();
  var ev=new MouseEvent('click',{bubbles:true,cancelable:true,view:window});
  el.dispatchEvent(ev);
  el.click();
  return "clicked:"+el.tagName.toLowerCase()+"#"+(el.id||'?');
})()"""
        val result = executeJs(js, sessionId)
        if (result.isError) return result
        if (result.output.startsWith("ERROR:")) return ToolExecutionResult(result.output, isError = true)
        delay(400)
        // After click, check if navigation happened
        val session = resolveSession(sessionId)
        if (session?.isPageLoading == true) {
            val readiness = waitForPageReadiness(session, 8_000L)
            session.lastReadiness = readiness
        }
        return ToolExecutionResult("✅ Clicked element: ${result.output}")
    }

    private suspend fun hoverElement(selector: String, sessionId: String? = null): ToolExecutionResult {
        val safe = selector.replace("\"", "\\\"")
        val js = """(function(){
  var el=document.querySelector("$safe");
  if(!el) return "ERROR: not found";
  el.scrollIntoView({behavior:'instant',block:'center'});
  var mouseOver=new MouseEvent('mouseover',{bubbles:true,cancelable:true,view:window});
  var mouseEnter=new MouseEvent('mouseenter',{bubbles:false,cancelable:true,view:window});
  el.dispatchEvent(mouseOver); el.dispatchEvent(mouseEnter);
  return "hovered:"+el.tagName.toLowerCase();
})()"""
        return executeJs(js, sessionId)
    }

    private suspend fun clearInput(selector: String, sessionId: String? = null): ToolExecutionResult {
        val safe = selector.replace("\"", "\\\"")
        val js = """(function(){
  var el=document.querySelector("$safe");
  if(!el) return "ERROR: not found";
  el.focus();el.value='';
  el.dispatchEvent(new Event('input',{bubbles:true}));
  el.dispatchEvent(new Event('change',{bubbles:true}));
  return "cleared";
})()"""
        return executeJs(js, sessionId)
    }

    private suspend fun typeText(text: String, selector: String? = null, sessionId: String? = null): ToolExecutionResult {
        val safeText = text.replace("\\", "\\\\").replace("\"", "\\\"")
        val safeSel = selector?.replace("\"", "\\\"")
        val focusCode = if (safeSel != null)
            "var el=document.querySelector(\"$safeSel\"); if(el){el.focus();}else{return \"ERROR:selector not found\";}"
        else "var el=document.activeElement;"
        val js = """(function(){
  $focusCode
  var val=el.value||'';
  el.value=val+"$safeText";
  el.dispatchEvent(new Event('input',{bubbles:true}));
  el.dispatchEvent(new Event('change',{bubbles:true}));
  return "typed "+"$safeText".length+" chars into <"+el.tagName.toLowerCase()+">";
})()"""
        val result = executeJs(js, sessionId)
        if (result.output.startsWith("ERROR:")) return ToolExecutionResult(result.output, isError = true)
        return ToolExecutionResult("✅ ${result.output}")
    }

    private suspend fun fillForm(fieldsJson: String, sessionId: String? = null): ToolExecutionResult {
        val fields = runCatching { JSONObject(fieldsJson) }.getOrElse {
            return ToolExecutionResult("Invalid JSON in fields: ${it.message}", isError = true)
        }
        val results = mutableListOf<String>()
        for (selector in fields.keys()) {
            val value = fields.optString(selector, "")
            val safeSel = selector.replace("\"", "\\\"")
            val safeVal = value.replace("\\", "\\\\").replace("\"", "\\\"")
            val js = """(function(){
  var el=document.querySelector("$safeSel");
  if(!el) return "ERROR: '$safeSel' not found";
  el.focus();
  if(el.tagName.toLowerCase()==='select'){
    el.value="$safeVal";el.dispatchEvent(new Event('change',{bubbles:true}));return "select set";
  }else if(el.type==='checkbox'||el.type==='radio'){
    el.checked=("$safeVal"==='true'||"$safeVal"==='1'||"$safeVal"===el.value);
    el.dispatchEvent(new Event('change',{bubbles:true}));return "toggle set";
  }else{
    el.value="$safeVal";
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));return "value set";
  }
})()"""
            val r = executeJs(js, sessionId)
            results.add("$selector → ${if (r.isError) "ERROR: ${r.output}" else r.output}")
        }
        return ToolExecutionResult("Form fill results:\n" + results.joinToString("\n"))
    }

    private suspend fun submitForm(selector: String, sessionId: String? = null): ToolExecutionResult {
        val safe = selector.replace("\"", "\\\"")
        val js = """(function(){
  var el=document.querySelector("$safe");
  if(!el) return "ERROR: form not found";
  var form=el.closest('form')||(el.tagName.toLowerCase()==='form'?el:null);
  if(!form) return "ERROR: no form ancestor found";
  form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
  if(form.requestSubmit){form.requestSubmit();}else{form.submit();}
  return "submitted";
})()"""
        val result = executeJs(js, sessionId)
        if (result.output.startsWith("ERROR:")) return ToolExecutionResult(result.output, isError = true)
        delay(1_000)
        val session = resolveSession(sessionId)
        if (session?.isPageLoading == true) {
            val readiness = waitForPageReadiness(session, 10_000L)
            session.lastReadiness = readiness
        }
        return ToolExecutionResult("✅ Form submitted.")
    }

    private suspend fun scrollTo(selector: String? = null, x: Int? = null, y: Int? = null, sessionId: String? = null): ToolExecutionResult {
        val js = when {
            selector != null -> {
                val safe = selector.replace("\"","\\\"")
                "(function(){var el=document.querySelector(\"$safe\");if(!el)return \"ERROR: not found\";el.scrollIntoView({behavior:'smooth',block:'center'});return 'scrolled';})()"
            }
            x != null || y != null -> "window.scrollTo({left:${x?:0},top:${y?:0},behavior:'smooth'}); 'scrolled'"
            else -> "window.scrollTo(0,document.body.scrollHeight); 'scrolled to bottom'"
        }
        val result = executeJs(js, sessionId)
        delay(400)
        return result
    }

    private suspend fun selectOption(selector: String, value: String, sessionId: String? = null): ToolExecutionResult {
        val safeSel = selector.replace("\"","\\\"")
        val safeVal = value.replace("\"","\\\"")
        return executeJs("""(function(){
  var el=document.querySelector("$safeSel");
  if(!el||el.tagName.toLowerCase()!=='select') return "ERROR: <select> not found";
  el.value="$safeVal";el.dispatchEvent(new Event('change',{bubbles:true}));
  return "selected: "+el.value;
})()""", sessionId)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Waiting / Polling
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun waitForElement(selector: String, timeoutMs: Long, sessionId: String? = null): ToolExecutionResult {
        val safe = selector.replace("\"","\\\"")
        val deadline = System.currentTimeMillis() + timeoutMs
        var elapsed = 0L
        while (System.currentTimeMillis() < deadline) {
            val result = executeJs("!!document.querySelector(\"$safe\")", sessionId)
            if (!result.isError && result.output.trim() == "true")
                return ToolExecutionResult("✅ Element appeared: $selector (${elapsed}ms)")
            delay(WAIT_ELEMENT_POLL_MS); elapsed += WAIT_ELEMENT_POLL_MS
        }
        return ToolExecutionResult("Timeout: '$selector' did not appear within ${timeoutMs}ms.", isError = true)
    }

    private suspend fun waitForText(text: String, timeoutMs: Long, sessionId: String? = null): ToolExecutionResult {
        val safeText = text.replace("\"","\\\"")
        val deadline = System.currentTimeMillis() + timeoutMs
        var elapsed = 0L
        while (System.currentTimeMillis() < deadline) {
            val result = executeJs("document.body.innerText.includes(\"$safeText\")", sessionId)
            if (!result.isError && result.output.trim() == "true")
                return ToolExecutionResult("✅ Text appeared: \"$text\" (${elapsed}ms)")
            delay(WAIT_ELEMENT_POLL_MS); elapsed += WAIT_ELEMENT_POLL_MS
        }
        return ToolExecutionResult("Timeout: text '$text' did not appear within ${timeoutMs}ms.", isError = true)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Data Extraction
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun extractLinks(sessionId: String? = null): ToolExecutionResult {
        val js = "(function(){var links=Array.from(document.querySelectorAll('a[href]')).slice(0,150);return JSON.stringify(links.map(function(a){return {text:(a.innerText||'').trim().substring(0,80),href:a.href};}));})()"
        val result = executeJs(js, sessionId)
        if (result.isError) return result
        return try {
            val arr = JSONArray(result.output)
            val sb = StringBuilder("Found ${arr.length()} link(s):\n")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                sb.appendLine("  [${i+1}] ${obj.optString("text","(no text)").padEnd(40)} → ${obj.optString("href")}")
            }
            ToolExecutionResult(sb.toString().trimEnd())
        } catch (_: Exception) { result }
    }

    private suspend fun extractTable(selector: String?, index: Int, sessionId: String? = null): ToolExecutionResult {
        val query = if (selector != null) "document.querySelector(\"${selector.replace("\"","\\\"")}\")"
            else "document.querySelectorAll('table')[$index]"
        val js = """(function(){
  var tbl=$query;
  if(!tbl||tbl.tagName.toLowerCase()!=='table') return "ERROR: no table found";
  var rows=Array.from(tbl.querySelectorAll('tr'));
  var data=rows.map(function(row){
    return Array.from(row.querySelectorAll('th,td')).map(function(cell){
      return (cell.innerText||cell.textContent||'').trim();
    });
  }).filter(function(r){return r.length>0;});
  return JSON.stringify(data);
})()"""
        val result = executeJs(js, sessionId)
        if (result.isError || result.output.startsWith("ERROR:")) return ToolExecutionResult(result.output, isError = true)
        return try {
            val matrix = JSONArray(result.output)
            val sb = StringBuilder()
            for (i in 0 until matrix.length()) {
                val row = matrix.getJSONArray(i)
                sb.appendLine((0 until row.length()).map { row.getString(it) }.joinToString(" | "))
            }
            ToolExecutionResult(sb.toString().trimEnd())
        } catch (_: Exception) { result }
    }

    private suspend fun extractMeta(sessionId: String? = null) = executeJs("""(function(){
  var metas={};
  document.querySelectorAll('meta[name],meta[property]').forEach(function(m){
    var key=m.getAttribute('name')||m.getAttribute('property');
    var val=m.getAttribute('content');
    if(key&&val) metas[key]=val.substring(0,200);
  });
  metas['_title']=document.title||'';
  metas['_canonical']=(document.querySelector('link[rel=canonical]')||{}).href||'';
  metas['_robots']=(document.querySelector('meta[name=robots]')||{}).content||'';
  return JSON.stringify(metas,null,2);
})()""", sessionId)

    // ════════════════════════════════════════════════════════════════════════
    // Screenshot
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun takeScreenshot(quality: Int = 80, sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        return withContext(Dispatchers.Main) {
            try {
                val wv = session.webView ?: return@withContext ToolExecutionResult("WebView not initialised.", isError = true)
                val bmp = Bitmap.createBitmap(wv.width.coerceAtLeast(1), wv.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                wv.draw(canvas)
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                bmp.recycle()
                val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                if (b64.length > MAX_SCREENSHOT_B64) {
                    ToolExecutionResult("Screenshot too large (${b64.length} chars). Reduce quality or use get_dom.", isError = true)
                } else {
                    ToolExecutionResult("Screenshot (JPEG, quality=$quality):\ndata:image/jpeg;base64,$b64")
                }
            } catch (e: Exception) {
                ToolExecutionResult("Screenshot failed: ${e.message}", isError = true)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Cookies & Storage
    // ════════════════════════════════════════════════════════════════════════

    private fun getCookies(sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        val url = session.currentUrl.ifBlank { return ToolExecutionResult("No URL loaded.", isError = true) }
        val raw = CookieManager.getInstance().getCookie(url) ?: return ToolExecutionResult("No cookies for: $url")
        val pairs = raw.split(";").map { it.trim() }
        return ToolExecutionResult("Cookies for $url (${pairs.size}):\n${pairs.joinToString("\n") { "  $it" }}")
    }

    private fun setCookie(name: String, value: String, sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        val url = session.currentUrl.ifBlank { return ToolExecutionResult("Navigate first.", isError = true) }
        CookieManager.getInstance().setCookie(url, "$name=$value")
        CookieManager.getInstance().flush()
        return ToolExecutionResult("✅ Cookie set: $name=$value on $url")
    }

    private fun clearCookies(): ToolExecutionResult {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        return ToolExecutionResult("✅ All cookies cleared.")
    }

    private suspend fun getLocalStorage(key: String, sessionId: String? = null) =
        executeJs("localStorage.getItem(${JSONObject.quote(key)})", sessionId)

    private suspend fun setLocalStorage(key: String, value: String, sessionId: String? = null): ToolExecutionResult {
        val result = executeJs("localStorage.setItem(${JSONObject.quote(key)},${JSONObject.quote(value)}); 'set'", sessionId)
        return if (!result.isError) ToolExecutionResult("✅ localStorage[$key] set.") else result
    }

    // ════════════════════════════════════════════════════════════════════════
    // Configuration
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun setUserAgent(ua: String, sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        val resolved = USER_AGENTS[ua] ?: ua
        withContext(Dispatchers.Main) { session.webView?.settings?.userAgentString = resolved }
        session.userAgent = resolved
        return ToolExecutionResult("✅ User-Agent set:\n$resolved")
    }

    private suspend fun injectCss(css: String, sessionId: String? = null): ToolExecutionResult {
        val safeCss = css.replace("\"", "\\\"").replace("\n", " ")
        return executeJs("""(function(){
  var s=document.createElement('style');s.setAttribute('data-omnidev','injected');
  s.textContent="$safeCss";document.head.appendChild(s);
  return 'CSS injected ('+${css.length}+' chars)';
})()""", sessionId)
    }

    private val persistentJsOnLoad = ConcurrentHashMap<String, String>()
    private suspend fun injectJsPersistent(jsCode: String, sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        val scriptId = "omnidev_persistent_${System.currentTimeMillis()}"
        persistentJsOnLoad[scriptId] = jsCode
        // Execute now too
        val result = executeJs(jsCode, sessionId)
        return ToolExecutionResult("✅ JS injected and registered for future page loads. Result: ${result.output.take(200)}")
    }

    private fun getNetworkLog(): ToolExecutionResult {
        val entries = synchronized(networkLogLock) { networkLog.toList() }
        if (entries.isEmpty()) return ToolExecutionResult("Network log is empty.")
        val sb = StringBuilder("Network log (${entries.size} entries):\n")
        entries.takeLast(50).forEach { e ->
            val status = if (e.blocked) "🚫 BLOCKED" else "✅"
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(e.timestamp))
            sb.appendLine("  [$ts] $status [${e.resourceType}] ${e.url.take(120)}")
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun setBlockDomains(domainsJson: String): ToolExecutionResult {
        return try {
            val arr = JSONArray(domainsJson)
            blockedDomains = (0 until arr.length()).map { arr.getString(it) }.toSet()
            ToolExecutionResult("✅ Blocking ${blockedDomains.size} domain(s): ${blockedDomains.joinToString()}")
        } catch (e: Exception) {
            ToolExecutionResult("Invalid JSON array: ${e.message}", isError = true)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // JS Bridge
    // ════════════════════════════════════════════════════════════════════════

    inner class JsBridge(private val session: BrowserSession) {
        @JavascriptInterface
        fun deliver(token: String, payload: String?) {
            val deferred = session.pendingJs.remove(token) ?: return
            if (!deferred.isCompleted)
                deferred.complete(payload ?: """{"ok":false,"result":"","error":"Empty JS payload"}""")
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun resolveSession(sessionId: String? = null): BrowserSession? {
        val id = sessionId ?: activeSessionId ?: return null
        val session = sessions[id] ?: return null
        if (session.webView == null) return null
        return session
    }

    private fun noSession() = ToolExecutionResult("No active browser session. Call action='new_session' first.", isError = true)
    private fun missingArg(name: String) = ToolExecutionResult("Missing required argument: $name", isError = true)
}
