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
import android.view.View
import android.view.autofill.AutofillManager
import android.view.inputmethod.InputMethodManager
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
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections
import java.util.WeakHashMap

/**
 * Runtime compatibility + human-handoff layer for Browser Viewer.
 *
 * The headless engine remains autonomous for ordinary navigation and forms, but
 * the visible browser becomes a real human-controlled browser surface whenever a
 * site reaches a credential/high-trust step. Password/OTP/payment values are not
 * collected by this layer and are never passed back to the agent.
 */
internal object BrowserRuntimeOptimizer {

    private data class AuthCompatibilityIssue(
        val key: String,
        val title: String,
        val message: String
    )

    private data class SensitiveStep(
        val kind: String,
        val title: String,
        val message: String
    )

    private val configuredViews = Collections.synchronizedMap(WeakHashMap<WebView, Boolean>())
    private val promptedIssues = Collections.synchronizedMap(WeakHashMap<WebView, MutableSet<String>>())
    private val sensitivePrompts = Collections.synchronizedMap(WeakHashMap<WebView, MutableSet<String>>())

    /**
     * Safe to call repeatedly. Capability setup runs once per WebView; auth and
     * human-takeover detection runs on every call because the URL/DOM can change
     * without the WebView instance changing.
     */
    fun configureAndCheck(webView: WebView, isIncognito: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            webView.post { configureAndCheck(webView, isIncognito) }
            return
        }

        if (configuredViews.put(webView, true) != true) {
            configureCapabilities(webView, isIncognito)
        }

        webView.post {
            maybeShowAuthCompatibilityDialog(webView)
            // SPA login forms often appear after hydration. Probe once now and
            // once shortly afterwards so React/Vue/Next transitions are caught.
            maybeShowSensitiveStepHandoff(webView)
            webView.postDelayed({ maybeShowSensitiveStepHandoff(webView) }, 700L)
        }
    }

    private fun configureCapabilities(webView: WebView, isIncognito: Boolean) {
        val settings = webView.settings

        val legacyDesktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        if (settings.userAgentString == legacyDesktopUa) {
            settings.userAgentString = WebSettings.getDefaultUserAgent(webView.context)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            val supportLevel = if (canUseBrowserWebAuthn(webView.context)) {
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
            } else {
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP
            }
            runCatching {
                WebSettingsCompat.setWebAuthenticationSupport(settings, supportLevel)
            }.recoverCatching {
                WebSettingsCompat.setWebAuthenticationSupport(
                    settings,
                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP
                )
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) {
            runCatching { WebSettingsCompat.setBackForwardCacheEnabled(settings, true) }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }

        // Browser-grade visible-mode features. Keep file:// disabled while
        // content:// remains available for SAF file/image uploads.
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadsImagesAutomatically = true
        settings.textZoom = 100
        settings.allowContentAccess = true
        settings.allowFileAccess = false
        settings.setGeolocationEnabled(true)
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        val cookieManager = cookieManagerFor(webView, isIncognito)
        cookieManager.setAcceptCookie(true)
        runCatching {
            cookieManager.setAcceptThirdPartyCookies(webView, !isIncognito)
        }

        webView.context.findActivity()?.let { activity ->
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

    /**
     * Detects sensitive inputs without reading their values. The JS only returns
     * a classification string; no password/OTP/card contents cross the bridge.
     */
    private fun maybeShowSensitiveStepHandoff(webView: WebView) {
        if (!webView.isAttachedToWindow) return
        val currentUrl = webView.url.orEmpty()
        if (currentUrl.isBlank() || classifyAuthIssue(currentUrl) != null) return

        val probe = """
(function(){
  function visible(el){
    if(!el) return false;
    var r=el.getBoundingClientRect(), s=getComputedStyle(el);
    return r.width>0 && r.height>0 && s.display!=='none' && s.visibility!=='hidden' && !el.disabled;
  }
  var inputs=Array.from(document.querySelectorAll('input')).filter(visible);
  if(inputs.some(function(i){return (i.type||'').toLowerCase()==='password';})) return 'password';
  if(inputs.some(function(i){
    var a=(i.autocomplete||'').toLowerCase();
    var n=((i.name||'')+' '+(i.id||'')+' '+(i.getAttribute('aria-label')||'')+' '+(i.placeholder||'')).toLowerCase();
    return a==='one-time-code' || /(^|\W)(otp|2fa|mfa|verification|security)[-_ ]?(code)?(\W|$)/.test(n);
  })) return 'otp';
  if(inputs.some(function(i){
    var a=(i.autocomplete||'').toLowerCase();
    return a==='cc-number' || a==='cc-csc' || a==='cc-exp';
  })) return 'payment';
  if(document.querySelector('iframe[src*="recaptcha" i], iframe[src*="hcaptcha" i], .g-recaptcha, .h-captcha, [data-sitekey]')) return 'captcha';
  return '';
})()
""".trimIndent()

        runCatching {
            webView.evaluateJavascript(probe) { raw ->
                val kind = decodeJavascriptString(raw)?.trim().orEmpty()
                if (kind.isBlank()) return@evaluateJavascript
                val step = sensitiveStep(kind) ?: return@evaluateJavascript
                val key = "${currentUrl.substringBefore('#')}|${step.kind}"
                val prompted = sensitivePrompts.getOrPut(webView) { mutableSetOf() }
                if (!prompted.add(key)) return@evaluateJavascript
                showSensitiveStepDialog(webView, step)
            }
        }
    }

    private fun sensitiveStep(kind: String): SensitiveStep? = when (kind) {
        "password" -> SensitiveStep(
            "password",
            "محتاج تدخلك لتسجيل الدخول",
            "لقيت خانة كلمة مرور. اكتبها بنفسك داخل الصفحة أو استخدم مدير كلمات المرور في أندرويد. Omni مش محتاج كلمة السر ومش المفروض تبعتها في الشات."
        )
        "otp" -> SensitiveStep(
            "otp",
            "محتاج رمز التحقق منك",
            "الصفحة وصلت لخطوة OTP / 2FA. اكتب الكود بنفسك هنا. متبعتش رمز التحقق للوكيل، وبعد ما تخلص يقدر يكمل باقي المهمة."
        )
        "captcha" -> SensitiveStep(
            "captcha",
            "محتاجك تحل خطوة التحقق",
            "الموقع طالب CAPTCHA أو تحقق بشري. استلم التحكم وحلّه بنفسك، وبعدها سيب الوكيل يكمل."
        )
        "payment" -> SensitiveStep(
            "payment",
            "خطوة دفع حساسة",
            "لقيت حقول بيانات دفع. أدخل بياناتك بنفسك داخل الموقع. Omni مش هيطلب رقم البطاقة أو CVV في الشات."
        )
        else -> null
    }

    private fun showSensitiveStepDialog(webView: WebView, step: SensitiveStep) {
        val activity = webView.context.findActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        AlertDialog.Builder(activity)
            .setTitle(step.title)
            .setMessage(step.message)
            .setPositiveButton("استلم التحكم") { _, _ ->
                focusSensitiveField(webView, step.kind)
            }
            .setNegativeButton("لاحقًا", null)
            .setCancelable(true)
            .show()
    }

    private fun focusSensitiveField(webView: WebView, kind: String) {
        val selector = when (kind) {
            "password" -> "input[type=password]"
            "otp" -> "input[autocomplete=one-time-code],input[name*=otp i],input[id*=otp i],input[name*=code i],input[id*=code i]"
            "payment" -> "input[autocomplete=cc-number],input[autocomplete=cc-csc],input[autocomplete=cc-exp]"
            else -> "input,button"
        }
        val js = """
(function(){
  var el=document.querySelector(${JSONObject.quote(selector)});
  if(!el) return false;
  el.scrollIntoView({block:'center',behavior:'smooth'});
  el.focus();
  return true;
})()
""".trimIndent()
        webView.evaluateJavascript(js, null)
        webView.requestFocus(View.FOCUS_DOWN)

        if (kind == "password" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                webView.context.getSystemService(AutofillManager::class.java)?.requestAutofill(webView)
            }
        }
        webView.postDelayed({
            val imm = webView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
        }, 250L)
    }

    private fun decodeJavascriptString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching { JSONTokener(raw).nextValue() }
            .getOrNull()
            ?.let { if (it is String) it else it.toString() }
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
                message = "Google بيرفض تسجيل الدخول من WebView مضمّن لأسباب أمان. افتح خطوة تسجيل الدخول في Custom Tab/المتصفح الآمن، وبعد نجاحها ارجع لـ OmniDev. تغيير User-Agent مش حل آمن للمشكلة دي."
            )
        }

        if (host == "github.com" && (
                path.startsWith("/sessions/two-factor") ||
                    path.contains("webauthn") ||
                    path.contains("passkey")
                )
        ) {
            return AuthCompatibilityIssue(
                key = "github-third-party-passkey",
                title = "GitHub 2FA / Passkey",
                message = "Passkey على GitHub محتاج صلاحيات WebAuthn لمتصفح يتعامل مع مواقع طرف ثالث. افتحها في المتصفح الآمن، أو استخدم TOTP / recovery code بنفسك داخل الجلسة. Omni مش هيطلب منك الكود أو كلمة المرور في الشات."
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
