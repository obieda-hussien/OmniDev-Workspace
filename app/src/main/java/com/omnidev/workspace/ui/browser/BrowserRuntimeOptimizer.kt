package com.omnidev.workspace.ui.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.omnidev.workspace.BuildConfig
import java.util.Collections
import java.util.WeakHashMap

/**
 * Runtime compatibility layer for the Browser Viewer.
 *
 * A WebView is not a full browser. In particular, identity providers can reject
 * embedded sign-in and third-party passkeys require browser-level WebAuthn
 * privileges. This layer enables every safe WebView capability available on the
 * device and detects flows that need to leave the embedded renderer instead of
 * trying to spoof a browser identity.
 */
internal object BrowserRuntimeOptimizer {

    private data class AuthCompatibilityIssue(
        val key: String,
        val title: String,
        val message: String
    )

    private val configuredViews = Collections.synchronizedMap(WeakHashMap<WebView, Boolean>())
    private val promptedIssues = Collections.synchronizedMap(WeakHashMap<WebView, MutableSet<String>>())

    /**
     * Safe to call repeatedly. The expensive configuration path runs once per
     * WebView, while auth-flow detection runs on every call because the URL can
     * change without the WebView instance changing.
     */
    fun configureAndCheck(webView: WebView, isIncognito: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            webView.post { configureAndCheck(webView, isIncognito) }
            return
        }

        if (configuredViews.put(webView, true) != true) {
            configureCapabilities(webView, isIncognito)
        }

        // getWebView() is called from Compose composition. Defer UI work until
        // after the current frame instead of showing a platform dialog as a
        // synchronous composition side-effect.
        webView.post { maybeShowAuthCompatibilityDialog(webView) }
    }

    private fun configureCapabilities(webView: WebView, isIncognito: Boolean) {
        val settings = webView.settings

        // HeadlessBrowserManager historically used a frozen desktop Chrome 124
        // UA for every tab. That quickly becomes suspicious to identity providers
        // and makes feature detection inaccurate. Only replace that exact legacy
        // default; explicit agent/user custom UAs remain untouched.
        val legacyDesktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        if (settings.userAgentString == legacyDesktopUa) {
            settings.userAgentString = WebSettings.getDefaultUserAgent(webView.context)
        }

        // WebAuthn is disabled by default. FOR_APP is safe for normal apps and
        // works for sites associated to OmniDev with Digital Asset Links.
        // A genuine privileged/system browser build can request browser-wide
        // support; normal APKs never pretend to have that privilege.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            val supportLevel = if (canUseBrowserWebAuthn(webView.context)) {
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
            } else {
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP
            }
            runCatching {
                WebSettingsCompat.setWebAuthenticationSupport(settings, supportLevel)
            }.recoverCatching {
                // A device/provider can still reject browser-wide privilege.
                // Fall back to app-associated WebAuthn rather than disabling it.
                WebSettingsCompat.setWebAuthenticationSupport(
                    settings,
                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP
                )
            }
        }

        // Back/forward restores become nearly instant on compatible WebView APKs.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) {
            runCatching { WebSettingsCompat.setBackForwardCacheEnabled(settings, true) }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true) }
        }

        // Keep platform protections enabled explicitly. This does not bypass TLS
        // or provider security checks.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }

        // Browser-grade navigation ergonomics.
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadsImagesAutomatically = true
        settings.textZoom = 100

        val cookieManager = cookieManagerFor(webView, isIncognito)
        cookieManager.setAcceptCookie(true)
        // Normal browsing needs third-party cookies for a number of legitimate
        // federated-login flows. Private sessions keep the stricter policy.
        runCatching {
            cookieManager.setAcceptThirdPartyCookies(webView, !isIncognito)
        }

        webView.context.findActivity()?.let { activity ->
            // Replace the headless client's auto-confirming ChromeClient only
            // while the WebView is visible to a human.
            webView.webChromeClient = BrowserChromeClient(activity)
            installDownloadHandler(webView, activity, cookieManager, isIncognito)
        }

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    private fun cookieManagerFor(webView: WebView, isIncognito: Boolean): CookieManager {
        if (isIncognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            return runCatching { WebViewCompat.getProfile(webView).cookieManager }
                .getOrElse { CookieManager.getInstance() }
        }
        return CookieManager.getInstance()
    }

    private fun installDownloadHandler(
        webView: WebView,
        activity: Activity,
        cookieManager: CookieManager,
        isIncognito: Boolean
    ) {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.isNullOrBlank()) return@setDownloadListener
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return@setDownloadListener
            if (uri.scheme !in setOf("http", "https")) {
                Toast.makeText(activity, "Blocked unsupported download scheme", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }

            val startDownload = {
                runCatching {
                    val request = DownloadManager.Request(uri)
                        .setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                        .setDescription(uri.host.orEmpty())
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(false)

                    if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
                    if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
                    cookieManager.getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                        request.addRequestHeader("Cookie", it)
                    }

                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    request.setDestinationInExternalFilesDir(
                        activity,
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )

                    val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    manager.enqueue(request)
                    Toast.makeText(activity, "Download started", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(activity, "Download failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }

            if (isIncognito) {
                AlertDialog.Builder(activity)
                    .setTitle("Private download")
                    .setMessage("The downloaded file will remain on this device after the private tab is closed.")
                    .setPositiveButton("Download") { _, _ -> startDownload() }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                startDownload()
            }
        }
    }

    private fun canUseBrowserWebAuthn(context: Context): Boolean {
        if (!BuildConfig.ALLOW_SYSTEM_INTEGRATION) return false
        val appInfo = context.applicationInfo
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return appInfo.flags and systemFlags != 0
    }

    private fun maybeShowAuthCompatibilityDialog(webView: WebView) {
        val currentUrl = webView.url.orEmpty()
        val issue = classifyAuthIssue(currentUrl) ?: return
        val alreadyPrompted = promptedIssues.getOrPut(webView) { mutableSetOf() }
        if (!alreadyPrompted.add(issue.key)) return

        val activity = webView.context.findActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        AlertDialog.Builder(activity)
            .setTitle(issue.title)
            .setMessage(issue.message)
            .setPositiveButton("فتح في المتصفح الآمن") { _, _ ->
                openInSecureBrowser(activity, currentUrl)
            }
            .setNegativeButton("متابعة هنا", null)
            .show()
    }

    private fun classifyAuthIssue(url: String): AuthCompatibilityIssue? {
        if (url.isBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()

        if (host == "accounts.google.com" || host.endsWith(".accounts.google.com")) {
            return AuthCompatibilityIssue(
                key = "google-embedded-signin",
                title = "Google Sign-in",
                message = "Google بيرفض تسجيل الدخول من WebView مضمّن لأسباب أمان، وده مش خطأ نقدر نحلّه بتغيير User-Agent. افتح خطوة تسجيل الدخول في Custom Tab/المتصفح الآمن. لاحظ إن Cookies المتصفح الخارجي منفصلة عن جلسة WebView الحالية."
            )
        }

        // GitHub currently serves the passkey challenge from the broader
        // /sessions/two-factor flow, so do not rely only on a literal /webauthn
        // path. The user can stay embedded and choose TOTP/recovery if desired.
        if (host == "github.com" && (
                path.startsWith("/sessions/two-factor") ||
                path.contains("webauthn") ||
                path.contains("passkey")
            )) {
            return AuthCompatibilityIssue(
                key = "github-third-party-passkey",
                title = "GitHub 2FA / Passkey",
                message = "Passkey على GitHub محتاج صلاحيات WebAuthn لمتصفح يتعامل مع مواقع طرف ثالث. OmniDev فعّل WebAuthn الآمن للمواقع المرتبطة بالتطبيق، لكن APK عادي مش مسموح له ينتحل صلاحيات متصفح كامل. افتحها في المتصفح الآمن، أو اختار More options واستخدم TOTP / recovery code داخل الجلسة الحالية."
            )
        }

        return null
    }

    private fun openInSecureBrowser(context: Context, url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val customTabResult = runCatching {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, uri)
        }
        if (customTabResult.isSuccess) return

        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            )
        }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        repeat(32) {
            when (current) {
                is Activity -> return current as Activity
                is ContextWrapper -> {
                    val next = (current as ContextWrapper).baseContext
                    if (next === current) return null
                    current = next
                }
                else -> return null
            }
        }
        return null
    }
}
