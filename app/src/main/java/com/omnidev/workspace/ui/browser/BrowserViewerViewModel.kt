package com.omnidev.workspace.ui.browser

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.tools.HeadlessBrowserManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Browser Viewer screen.
 *
 * Wraps [HeadlessBrowserManager] and exposes reactive session state + user actions
 * (navigate, back, forward, reload, cookie management, session lifecycle).
 */
class BrowserViewerViewModel(
    private val manager: HeadlessBrowserManager
) : ViewModel() {

    /** Live list of all open browser sessions (UI-safe snapshots). */
    val sessions: StateFlow<List<HeadlessBrowserManager.BrowserSessionInfo>> =
        manager.sessionsFlow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    // ─── WebView access ───────────────────────────────────────────────────────

    /** Returns the live WebView for the given session ID (or active session if null). */
    fun getWebView(sessionId: String? = null): WebView? =
        manager.getWebViewForSession(sessionId)

    /** Returns the currently active session ID. */
    fun getActiveSessionId(): String? = manager.getActiveSessionId()

    /**
     * Passes the current Activity context to the manager so that WebViews can
     * use it for hardware-accelerated rendering.  Should be called from the UI
     * on every composition (idempotent, cheap).
     */
    fun updateActivityContext(ctx: android.content.Context) {
        manager.updateActivityContext(ctx)
    }

    /**
     * Recreates any existing WebViews that were created before the Activity
     * context was available, so they render correctly on screen.
     * Call once after [updateActivityContext] when the Browser Viewer opens.
     */
    fun refreshWebViewsForDisplay() = viewModelScope.launch(Dispatchers.IO) {
        manager.refreshWebViewsForDisplay()
    }

    // ─── Navigation ───────────────────────────────────────────────────────────

    fun navigate(url: String) = viewModelScope.launch(Dispatchers.IO) {
        val normalized = url.trim().trimStart('/')
        val safeUrl = if (normalized.startsWith("http://") || normalized.startsWith("https://")) normalized
                      else "https://$normalized"
        manager.execute("navigate", mapOf("url" to safeUrl))
    }

    fun back() = viewModelScope.launch(Dispatchers.IO) {
        manager.execute("back", emptyMap())
    }

    fun forward() = viewModelScope.launch(Dispatchers.IO) {
        manager.execute("forward", emptyMap())
    }

    fun reload() = viewModelScope.launch(Dispatchers.IO) {
        manager.execute("reload", emptyMap())
    }

    // ─── Session management ───────────────────────────────────────────────────

    fun newSession(label: String = "") = viewModelScope.launch(Dispatchers.IO) {
        val args = if (label.isNotBlank()) mapOf("label" to label) else emptyMap()
        manager.execute("new_session", args)
    }

    fun newIncognitoSession(label: String = "") = viewModelScope.launch(Dispatchers.IO) {
        val args = buildMap {
            if (label.isNotBlank()) put("label", label)
        }
        manager.execute("new_incognito_session", args)
    }

    fun switchSession(id: String) = viewModelScope.launch(Dispatchers.IO) {
        manager.execute("switch_session", mapOf("session_id" to id))
    }

    fun closeSession(id: String) = viewModelScope.launch(Dispatchers.IO) {
        manager.execute("close_session", mapOf("session_id" to id))
    }

    // ─── Cookies ──────────────────────────────────────────────────────────────

    /**
     * Returns a parsed list of (name, value) cookie pairs for the active session's URL.
     * Returns an empty list if no session is active or no page is loaded.
     */
    fun getCookies(): List<Pair<String, String>> {
        val active = sessions.value.firstOrNull { it.isActive } ?: return emptyList()
        val url = active.currentUrl.ifBlank { return emptyList() }
        val raw = CookieManager.getInstance().getCookie(url) ?: return emptyList()
        return raw.split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { pair ->
                val eq = pair.indexOf('=')
                if (eq >= 0) pair.substring(0, eq).trim() to pair.substring(eq + 1).trim()
                else pair to ""
            }
    }

    fun setCookie(name: String, value: String) = viewModelScope.launch(Dispatchers.IO) {
        manager.execute("set_cookie", mapOf("cookie_name" to name, "cookie_value" to value))
    }

    fun clearCookies() = viewModelScope.launch(Dispatchers.IO) {
        manager.execute("clear_cookies", emptyMap())
    }

    // ─── Storage ──────────────────────────────────────────────────────────────

    fun clearLocalStorage() = viewModelScope.launch(Dispatchers.IO) {
        manager.execute("clear_local_storage", emptyMap())
    }

    // ─── ViewModel factory ────────────────────────────────────────────────────

    companion object {
        fun factory(manager: HeadlessBrowserManager) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return BrowserViewerViewModel(manager) as T
            }
        }
    }
}
