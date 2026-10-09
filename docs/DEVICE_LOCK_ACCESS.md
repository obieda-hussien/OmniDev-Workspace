# Consented device and lock-screen access

Open **AI Settings → Omni on your screen → Device access → Lock screen and sensitive access**. Every new scope is disabled by default. Enabling a scope requires a local explanation and Android identity confirmation. Admin/OEM automatic confirmation does not grant these scopes. Disabling is immediate; revoking all also deletes the local PIN.

| Scope | Behavior | Limits |
|---|---|---|
| Wake the screen | A private Activity asks Android to turn the display on; the result checks `PowerManager.isInteractive` | Android can block background Activity starts; no unlock is implied |
| Request Android unlock | `KeyguardManager.requestDismissKeyguard`, with callback and locked-state verification | Secure devices require Android authentication; Device Admin is not unlock authority |
| Assistant on the lock screen | Native voice assistant/keyguard host or translucent Activity with a private locked panel | No existing messages, console, attachments or grant controls; local voice conversation is a separate opt-in; OEMs may hide application bubbles |
| Inspect the lock screen | Semantic controls exposed by Android's Accessibility service | General gestures, raw XML dumps, screenshots and credential field readers are blocked while locked |
| Inspect and operate Settings | Semantic/visual access and ordinary interaction with the device's Settings package | Android protected-window and password restrictions remain; existing action approval gates still apply |
| Use a local unlock PIN | Admin-only AES-GCM/AndroidKeyStore vault; choose a 15-minute one-shot permit or remembered authorization until revoked | Standard `com.android.systemui` PIN keypad only; no patterns/passwords, coordinates, shell input or guessing |
| Enter a spoken unlock code locally | Separate Android-authenticated opt-in; offline recognition of a PIN, explicitly spelled password, or numbered pattern; explicit confirmation and one submission per voice session | Supported native SystemUI controls only; no credential storage, chat transcript, cloud recognition or automatic retry; audible codes and recordings can be replayed |

## Private spoken unlock

Configure [Hi Omni local voice](HI_OMNI_LOCAL_WAKE.md), install an offline speech model and offline Android spoken voice, and enable **Local voice conversation after wake**. Grant **Request Android unlock**, **Assistant on the lock screen** and **Enter a spoken unlock code locally** through Android identity confirmation. Screen-off activation additionally needs the wake-screen permission and lock-screen listening enabled. Start listening while the phone is unlocked.

After the chosen phrase (default “Hi Omni”), request unlock or a task requiring the screen. The local session presents Android's own keyguard, asks for the credential privately, and waits for “confirm”. Say PIN digits individually; spell passwords with explicit letter case and symbol names; describe patterns with numbered points: top row 1–3, middle row 4–6, bottom row 7–9. Android's intermediate-point rule is applied. The code is never repeated or sent to the chat provider. Android validates the single submission. A pending task resumes only after keyguard reports unlocked. Unsupported controls, low recognition confidence, refusal, timeout or revocation require manual unlock.

`device_admin(action="request_unlock")` and screen-dependent tool preflight prefer an authorized saved PIN. Without that authorization, they use the private voice session when configured, otherwise open Android authentication and wait up to 60 seconds for a fingerprint or device code. A failed selected credential route never falls through into another one. `device_admin(action="status")` reports the first missing voice prerequisite: the separate device grants, selected assistant, local conversation, speech model, microphone, or connected Accessibility. Android Device Admin, Device Owner and Shizuku are not substitutes for keyguard authentication. The status also reports saved PIN authorization and any paused remembered attempts.

The assistant hides its window without cancelling the running conversation before handing the screen to Android. The native keyguard request starts only after the host is resumed and its window has focus. Concurrent hosts are refused. Android callbacks are reconciled with the actual locked state for up to 1.5 seconds: a cancellation racing with a biometric unlock can succeed, while a success callback with a still-locked device cannot. Consent revocation stops the pending host; recreation keeps the original deadline. A closed/unsupported native prompt stops private speech collection and never causes a second credential attempt. If authentication still fails, the checkpoint explicitly waits for manual user action before continuation.

With **Wake the screen** plus **Assistant on the lock screen** or **Request Android unlock** enabled, lock-screen activation holds the display for up to **120 seconds**. One monotonic deadline follows the session from the assistant to Android authentication; UI handoffs and recreation do not restart the timer. A timed display wake lock bridges the period when SystemUI covers the app, while attached app windows use `FLAG_KEEP_SCREEN_ON` as a fallback. It does not use wake-on-acquire flags. Unlocking, explicit screen-off, closing the conversation or revoking the grants releases the hold. Android screen-off/window dismissal while a locked task or voice session is active preserves the conversation instead of interpreting it as a user cancellation. Explicit Close still cancels it.

A standalone `wake_screen` or `request_unlock` starts a fresh two-minute lease when the request is accepted, including after an earlier lease was released or expired. Private voice requests capture this origin before showing their assistant host. Calls from an existing assistant session, subsequent credential hosts and Activity recreation continue the original deadline; they do not renew it or undo an explicit screen-off.

Lock-screen presence uses the selected Android voice assistant or an activity with `showWhenLocked`. Ordinary floating bubbles can appear over Settings when Android allows application overlays. Authentication windows and system pages using `HIDE_OVERLAY_WINDOWS` may hide them: neither Device Admin nor Shizuku grants an unrestricted window above every native credential/system screen. The assistant deliberately hands credential entry to SystemUI and hides conversation content during that phase.

Spoken unlock shares the local credential input gate with saved-PIN entry. Ordinary Accessibility observation, captures and actions are suspended throughout private code input. Direct PCM recognition alternates with offline TTS; it does not start Google's recognizer or its repeated tones. Speaking a real password aloud is susceptible to overhearing and replay, so voice matching is never treated as authentication. Protected windows and OEM restrictions remain in force. No source-level test establishes compatibility with a particular phone.

## Local PIN setup

1. Enable **Request Android unlock** and **Use a local unlock PIN**, confirming your identity in Android for each.
2. Enter the real device PIN into **Device PIN (local only)** and choose **Save local PIN**. The field belongs to a screenshot-protected, overlay-protected Activity and is not saved in Compose restoration state.
3. Choose **Remember PIN authorization** and confirm your identity again to keep authorization until you revoke it. It survives app process restart, device restart (after the first manual unlock), app updates and elapsed time. Clearing app data or uninstalling removes it. Existing installations and temporary permits are never silently upgraded to remembered authorization. Alternatively, **Authorize one PIN attempt** retains the in-memory, monotonic 15-minute option; choosing it replaces remembered authorization.
4. Ask Omni to unlock using `device_admin(action="request_unlock")` or `device_admin(action="unlock_with_saved_pin")`. These actions have no PIN parameter. The executor opens native keyguard and waits up to eight seconds for the supported empty SystemUI keypad, checks visible controls and consent, then authorizes exactly one input before the first digit. It verifies Android's locked state before reporting success.
5. Remembered authorization persists after a verified successful unlock, so the next requested unlock does not need another local authorization. Before any PIN decryption or input, the executor synchronously persists a paused state. Failed input, timeout or process interruption leaves attempts paused; another tool call or process restart cannot retry the credential or automatically switch to voice. Use **Resume saved PIN attempts**, with Android identity confirmation, after correcting the problem. Unsupported/absent keypads stop before input and do not consume a permit or pause an unused remembered grant.
6. **Revoke PIN authorization** stops its use without deleting the stored PIN. Saving a replacement PIN clears its previous authorization. Disabling **Request Android unlock** or **Use a local unlock PIN** revokes remembered authorization; **Delete PIN and revoke** or **Revoke all access above** also removes the credential and its key. These controls are available locally; model/tool requests cannot grant or resume permission.

The PIN is never sent to an LLM, echoed in tool results, put in a shell command/clipboard, recorded in a learned routine, or persisted in conversation history. Ciphertext lives in `noBackupFilesDir`; the key lives in AndroidKeyStore. User consent preferences are excluded from cloud backup and device transfer. The vault deliberately uses device-local encryption with a separate authenticated user authorization (temporary or remembered): a hardware key requiring authentication on every decryption could not be used while the device is subsequently locked. This feature cannot unlock credential-encrypted storage before the first manual unlock after boot. The saved PIN is not validated against Android at save time; Android validates the single actual keypad attempt.

## Tools and privacy enforcement

```text
device_admin(action="status")
device_admin(action="consent_settings")
device_admin(action="wake_screen")
device_admin(action="request_unlock")
device_admin(action="unlock_with_saved_pin")
device_admin(action="lock_screen")
device_admin(action="audit_log")
```

Existing password-length and timeout policy actions now validate bounded numeric inputs and report Android policy denial correctly. Unlock reports `UNLOCKED` only after Android's keyguard state verifies it; launching setup or a prompt reports user action required. The full agent and floating assistant share the same checks. The structured tool dispatcher, Accessibility snapshot/actions, assistant image attachments, live capture and learned-event recorder enforce the new boundaries. A Settings consent grants no new Android entitlement and never disables `FLAG_SECURE` or `HIDE_OVERLAY_WINDOWS`. Raw privileged terminal commands retain their existing explicitly authorized root/shell policy; these scopes constrain the structured device tools, not a sandbox around arbitrary root scripts.

## Verification

`SavedPinAuthorizationTest` covers persisted grants across process recreation, repeated successful requests, failed/interrupted input, authenticated resume, write failures, revocation with late callbacks and removed app data. `DeviceConsentPolicyTest` covers independent scopes, denial of lock-screen screenshots/mutations, normal-app compatibility, one-shot consumption, exact expiry, revocation and process restart. `DevicePinVaultTest` runs on a real Android device/emulator and checks ciphertext, tamper rejection, zeroing (including callback failure) and deletion. `ProtectedSemanticObservationTest` checks password/PIN privacy in the semantic tree, executive summary and form placeholders.

`KeyguardUnlockSessionTest` covers lifecycle readiness, one prompt per host, delayed biometric state after cancellation, false success callbacks, revocation, deadline boundaries and stale callbacks. `VoiceSessionPolicyTest` checks every private voice unlock prerequisite and keeps credentials out of ordinary task routing. These JVM tests cover policy/state transitions; they do not simulate OEM SystemUI or establish physical-device support.

```sh
./gradlew :app:testAdminDebugUnitTest --tests '*DeviceConsentPolicyTest'
./gradlew :app:compileNormDebugKotlin :app:compileAdminDebugKotlin
./gradlew :app:connectedAdminDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.omnidev.workspace.data.admin.DevicePinVaultTest
```

Device checks: denied-by-default scopes in Admin; enable/deny Android identity confirmation; revoke while assistant is visible; lock during an open conversation; unsupported OEM keypad; wrong PIN (one attempt only); manually unlock after failure; background Activity launch denial; first boot before manual unlock; Android 11 and Android 12+ protected Settings windows. Source checks and JVM policy tests do not establish OEM keypad compatibility.

References: [KeyguardManager](https://developer.android.com/reference/android/app/KeyguardManager), [showWhenLocked](https://developer.android.com/reference/android/app/Activity#setShowWhenLocked(boolean)), [secure sensitive activities](https://developer.android.com/security/fraud-prevention/activities), [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

## Assistant screenshots and attachment context

The unlocked floating/native assistant permits ordinary user screenshots. Its host applies `FLAG_SECURE` while locked or entering a private credential and refreshes on screen-off/unlock; enrollment, device-consent and native unlock activities retain their own protection. This does not change capture restrictions of the foreground app.

Context admission and fallback token accounting share a media estimate. Base64 image transport is never counted as plain-text tokens; each inline image uses a conservative 4,000-token proxy, independently of encoded length, while native provider usage remains authoritative. A file path alone is not an inline image. The newest message and its image payloads remain intact during trimming. Three images and a selected file path no longer trigger a multi-million-token local rejection. Up to ten attachments, including the selected screen, are accepted; the 10 MiB individual and 15 MiB combined byte limits still apply. These byte limits are distinct from provider image counts and context windows. Actual oversized text is still rejected rather than silently changed. See [Google’s token-counting documentation](https://ai.google.dev/gemini-api/docs/tokens) for provider-native multimodal counts.
