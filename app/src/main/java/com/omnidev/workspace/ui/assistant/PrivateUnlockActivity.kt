package com.omnidev.workspace.ui.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.omnidev.workspace.data.admin.*
import com.omnidev.workspace.data.voice.SpokenCredential
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

/** A code provided and confirmed locally; never saved, logged, placed in an Intent, or sent to chat. */
class PrivateUnlockActivity : ComponentActivity() {
    private var id: String? = null
    private var input: EditText? = null
    private var submitted = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("request")
        if (id?.let { pending[it]?.isActive } != true || !allowed(this)) { finish(); return }
        id?.let { hosts[it] = this }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        layout.addView(TextView(this).apply { text = "Private Android unlock\nEnter your code here to let Omni enter it once. It stays on this device and is not saved in chat." })
        val types = SpokenCredential.Kind.entries
        val choice = Spinner(this).apply { adapter = ArrayAdapter(this@PrivateUnlockActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("PIN", "Password", "Pattern points 1–9")) }
        layout.addView(choice)
        input = EditText(this).apply {
            hint = "Device code · local only"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false; isLongClickable = false; setSingleLine(true)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextIsSelectable(false)
        }
        layout.addView(input)
        val error = TextView(this); layout.addView(error)
        layout.addView(Button(this).apply {
            text = "Confirm one unlock attempt"
            setOnClickListener {
                if (submitted || !allowed(this@PrivateUnlockActivity)) return@setOnClickListener
                val chars = CharArray(input!!.length()) { input!!.text[it] }
                val kind = types[choice.selectedItemPosition]
                val valid = when (kind) {
                    SpokenCredential.Kind.PIN -> chars.size in 4..16 && chars.all { it in '0'..'9' }
                    SpokenCredential.Kind.PASSWORD -> chars.size in 4..64
                    SpokenCredential.Kind.PATTERN -> chars.size in 4..9 && chars.all { it in '1'..'9' } && chars.toSet().size == chars.size
                }
                if (!valid) { chars.fill('\u0000'); error.text = "Check the code format. Pattern: enter 4–9 different points without spaces."; return@setOnClickListener }
                submitted = true; isEnabled = false; input!!.text.clear()
                val credential = SpokenCredential(kind, chars)
                lifecycleScope.launch {
                    try {
                        // Remove this private dialog from view before inspecting the real keypad.
                        window.decorView.visibility = android.view.View.INVISIBLE
                        coroutineScope {
                            val prompt = async(start = CoroutineStart.UNDISPATCHED) { DeviceUnlockActivity.request(this@PrivateUnlockActivity, true, voiceSession = true) }
                            try {
                                val ready = withTimeoutOrNull(8_000) {
                                    while (DeviceConsentStore(this@PrivateUnlockActivity).locked() && !prompt.isCompleted &&
                                        LocalSpokenUnlock.visibleKind() != kind && allowed(this@PrivateUnlockActivity)) delay(150)
                                    LocalSpokenUnlock.visibleKind() == kind
                                } == true
                                val success = !DeviceConsentStore(this@PrivateUnlockActivity).locked() ||
                                    (ready && allowed(this@PrivateUnlockActivity) && LocalSpokenUnlock.attempt(this@PrivateUnlockActivity, credential))
                                complete(if (success && !DeviceConsentStore(this@PrivateUnlockActivity).locked()) "UNLOCKED: verified with Android keyguard state."
                                    else "USER_ACTION_REQUIRED: private code input could not unlock this SystemUI keypad. No automatic retry; unlock manually.")
                            } finally { withContext(NonCancellable) { prompt.cancelAndJoin() } }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { complete("USER_ACTION_REQUIRED: private code input was interrupted. No automatic retry; unlock manually.") }
                    finally { credential.close() }
                }
            }
        })
        layout.addView(Button(this).apply { text = "Cancel"; setOnClickListener { complete("USER_ACTION_REQUIRED: private code entry cancelled. Nothing entered.") } })
        setContentView(ScrollView(this).apply { addView(layout) })
    }
    private fun complete(result: String) { id?.let { pending[it]?.complete(result) }; input?.text?.clear(); finish() }
    override fun onDestroy() {
        input?.text?.clear(); input = null
        id?.let { if (hosts[it] === this) { hosts.remove(it); if (!isChangingConfigurations || submitted) pending[it]?.complete("USER_ACTION_REQUIRED: private code entry closed. No automatic retry.") } }
        super.onDestroy()
    }
    companion object {
        private val pending = mutableMapOf<String, CompletableDeferred<String>>()
        private val hosts = mutableMapOf<String, PrivateUnlockActivity>()
        private val gate = Mutex()
        private fun allowed(context: Context) = DeviceConsentStore(context).let {
            it.enabled(DeviceConsentPolicy.Scope.UNLOCK) && it.enabled(DeviceConsentPolicy.Scope.VOICE_CREDENTIAL) && it.enabled(DeviceConsentPolicy.Scope.LOCK_OVERLAY)
        }
        suspend fun request(context: Context): String = withContext(Dispatchers.Main.immediate) {
            if (!allowed(context)) return@withContext "USER_ACTION_REQUIRED: enable private code entry and lock-screen assistant in Device access, or unlock manually."
            if (!gate.tryLock()) return@withContext "USER_ACTION_REQUIRED: another private code entry is active."
            val id = UUID.randomUUID().toString(); val result = CompletableDeferred<String>()
            pending[id] = result
            val generation = com.omnidev.workspace.data.assistant.AssistantRuntime.get(context).sessionGeneration
            try {
                com.omnidev.workspace.data.admin.LockScreenAwake.hold(context)
                com.omnidev.workspace.data.assistant.AssistantRuntime.hideForUnlock?.invoke()
                context.startActivity(Intent(context, PrivateUnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("request", id))
                withTimeoutOrNull(90_000) { result.await() } ?: "USER_ACTION_REQUIRED: private code entry timed out. Unlock manually."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "USER_ACTION_REQUIRED: Android blocked private code entry. Open Omni in the foreground and unlock manually." }
            finally {
                pending.remove(id); hosts.remove(id)?.finish(); gate.unlock()
                if (currentCoroutineContext().isActive && !com.omnidev.workspace.data.voice.LocalVoiceSessionService.handoff &&
                    com.omnidev.workspace.data.assistant.AssistantRuntime.targetingScreen &&
                    com.omnidev.workspace.data.assistant.AssistantRuntime.get(context).sessionGeneration == generation)
                    runCatching { com.omnidev.workspace.data.assistant.OmniVoiceInteractionService.resume(context) }
            }
        }
    }
}
