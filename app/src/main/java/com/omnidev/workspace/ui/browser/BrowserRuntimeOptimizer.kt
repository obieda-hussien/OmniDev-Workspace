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
 * The headless engine stays autonomous for ordinary navigation and forms. Once a
 * session is visible, this layer upgrades it with browser-grade capabilities,
 * privacy-preserving semantic instrumentation, file uploads, and explicit human
 * takeover for credentials/high-trust steps.
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
     * This script is injected before page JavaScript whenever the WebView provider
     * supports DOCUMENT_START_SCRIPT. It exposes a lightweight semantic DOM index
     * that the agent can query with execute_js without scraping input values.
     *
     * It intentionally NEVER returns .value, textContent from password/OTP/payment
     * fields, cookies, localStorage, query strings, or fragments.
     */
    private val semanticBootstrap = """
(function(){
  if (window.__OMNI_DEV_SEMANTICS__) return;

  function visible(el){
    if(!el || !(el instanceof Element)) return false;
    var r=el.getBoundingClientRect(), s=getComputedStyle(el);
    return r.width>0 && r.height>0 && s.display!=='none' && s.visibility!=='hidden';
  }
  function clean(v,n){
    return String(v||'').replace(/\s+/g,' ').trim().slice(0,n||180);
  }
  function safeHref(el){
    try {
      if(!el.href) return '';
      var u=new URL(el.href, location.href);
      return u.origin+u.pathname;
    } catch(e){ return ''; }
  }
  function labelFor(el){
    var aria=el.getAttribute('aria-label');
    if(aria) return clean(aria);
    if(el.id){
      try { var l=document.querySelector('label[for="'+CSS.escape(el.id)+'"]'); if(l) return clean(l.innerText); } catch(e){}
    }
    var parent=el.closest('label');
    if(parent) return clean(parent.innerText);
    return clean(el.placeholder || el.title || '');
  }
  function kindOf(el){
    var tag=(el.tagName||'').toLowerCase();
    var type=(el.getAttribute('type')||'').toLowerCase();
    var ac=(el.getAttribute('autocomplete')||'').toLowerCase();
    if(type==='password') return 'password';
    if(ac==='one-time-code') return 'otp';
    if(ac==='cc-number'||ac==='cc-csc'||ac==='cc-exp') return 'payment';
    if(tag==='input'||tag==='textarea'||el.isContentEditable) return 'input';
    if(tag==='button'||el.getAttribute('role')==='button') return 'button';
    if(tag==='a') return 'link';
    if(tag==='select') return 'select';
    return tag || 'element';
  }
  function describe(el,index){
    var kind=kindOf(el);
    var sensitive=kind==='password'||kind==='otp'||kind==='payment';
    return {
      index:index,
      kind:kind,
      tag:(el.tagName||'').toLowerCase(),
      type:clean(el.getAttribute('type'),40),
      role:clean(el.getAttribute('role'),60),
      name:clean(el.getAttribute('name'),100),
      id:clean(el.id,100),
      label:labelFor(el),
      placeholder:sensitive?'':clean(el.getAttribute('placeholder'),140),
      autocomplete:clean(el.getAttribute('autocomplete'),80),
      text:sensitive?'':clean(el.innerText,180),
      href:kind==='link'?safeHref(el):'',
      disabled:!!el.disabled,
      checked:typeof el.checked==='boolean'?!!el.checked:undefined
    };
  }
  function candidates(){
    var selector='a[href],button,input,textarea,select,[role=button],[role=link],[contenteditable=true],[tabindex]';
    return Array.from(document.querySelectorAll(selector)).filter(visible);
  }
  function snapshot(limit){
    var max=Math.max(1,Math.min(Number(limit)||120,300));
    return candidates().slice(0,max).map(describe);
  }
  function find(query,limit){
    var q=clean(query,120).toLowerCase();
    var max=Math.max(1,Math.min(Number(limit)||30,100));
    if(!q) return snapshot(max);
    return candidates().map(function(el,i){
      var d=describe(el,i);
      var hay=[d.label,d.text,d.name,d.id,d.placeholder,d.role,d.type].join(' ').toLowerCase();
      var score=0;
      if(hay===q) score+=100;
      if(hay.indexOf(q)>=0) score+=50;
      q.split(/\s+/).forEach(function(t){if(t && hay.indexOf(t)>=0) score+=8;});
      return {score:score,item:d};
    }).filter(function(x){return x.score>0;})
      .sort(function(a,b){return b.score-a.score;})
      .slice(0,max).map(function(x){return x.item;});
  }
  function sensitiveKind(){
    var all=candidates();
    for(var i=0;i<all.length;i++){
      var k=kindOf(all[i]);
      if(k==='password'||k==='otp'||k==='payment') return k;
    }
    if(document.querySelector('iframe[src*="recaptcha" i],iframe[src*="hcaptcha" i],.g-recaptcha,.h-captcha,[data-sitekey]')) return 'captcha';
    return '';
  }

  Object.defineProperty(window,'__OMNI_DEV_SEMANTICS__',{
    configurable:false,
    enumerable:false,
    writable:false,
    value:Object.freeze({version:2,snapshot:snapshot,find:find,sensitiveKind:sensitiveKind})
  });
})();
""".trimIndent()

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

        installSemanticInstrumentation(webView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }

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

    private fun installSemanticInstrumentation(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    semanticBootstrap,
                    setOf("*")
                )
            }
        }
        // Also install into the document that may already be loaded when a
        // headless session first becomes visible.
        runCatching { webView.evaluateJavascript(semanticBootstrap, null) }
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

    /** Detect sensitive inputs without reading their values. */
    private fun maybeShowSensitiveStepHandoff(webView: WebView) {
        if (!webView.isAttachedToWindow) return
        val currentUrl = webView.url.orEmpty()
        if (currentUrl.isBlank() || classifyAuthIssue(currentUrl) != null) return

        val probe = """
(function(){
  try {
    if(window.__OMNI_DEV_SEMANTICS__) return window.__OMNI_DEV_SEMANTICS__.sensitiveKind();
  } catch(e){}
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
            "Your input is needed to sign in",
            "A password field was detected. Enter it directly on the page or use Android Password Manager. Omni does not need your password and you should not send it in chat."
        )
        "otp" -> SensitiveStep(
            "otp",
            "Your verification code is needed",
            "This page reached an OTP / 2FA step. Enter the code yourself here. Do not send verification codes to the agent; once you finish, the agent can continue the rest of the task."
        )
        "captcha" -> SensitiveStep(
            "captcha",
            "Human verification required",
            "The site requires a CAPTCHA or another human verification step. Take control and complete it yourself, then let the agent continue."
        )
        "payment" -> SensitiveStep(
            "payment",
            "Sensitive payment step",
            "Payment fields were detected. Enter the payment details yourself on the website. Omni will not ask for a card number or CVV in chat."
        )
        else -> null
    }

    private fun showSensitiveStepDialog(webView: WebView, step: SensitiveStep) {
        val activity = webView.context.findActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        AlertDialog.Builder(activity)
            .setTitle(step.title)
            .setMessage(step.message)
            .setPositiveButton("Take control") { _, _ ->
                focusSensitiveField(webView, step.kind)
            }
            .setNegativeButton("Later", null)
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
            .setPositiveButton("Open in secure browser") { _, _ ->
                openInSecureBrowser(activity, currentUrl)
            }
            .setNegativeButton("Continue here", null)
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
                message = "Google blocks sign-in from an embedded WebView for security reasons. Open the sign-in step in a Custom Tab or secure browser, then return to OmniDev after it succeeds. Changing the User-Agent is not a safe fix for this restriction."
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
                message = "GitHub passkeys require WebAuthn capabilities that can operate with third-party websites. Open this step in the secure browser, or enter a TOTP / recovery code yourself in the session. Omni will not ask for the code or your password in chat."
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
