# Consented device and lock-screen access

Open **AI Settings → Omni on your screen → Device access → Lock screen and sensitive access**. Every new scope is disabled by default. Enabling a scope requires a local explanation and Android identity confirmation. Admin/OEM automatic confirmation does not grant these scopes. Disabling is immediate; revoking all also deletes the local PIN.

| Scope | Behavior | Limits |
|---|---|---|
| Wake the screen | A private Activity asks Android to turn the display on; the result checks `PowerManager.isInteractive` | Android can block background Activity starts; no unlock is implied |
| Request Android unlock | `KeyguardManager.requestDismissKeyguard`, with callback and locked-state verification | Secure devices require Android authentication; Device Admin is not unlock authority |
| Assistant on the lock screen | Native voice assistant/keyguard host or translucent Activity with a private locked panel | No existing messages, console, attachments or grant controls; local voice conversation is a separate opt-in; OEMs may hide application bubbles |
| Inspect the lock screen | Semantic controls exposed by Android's Accessibility service | General gestures, raw XML dumps, screenshots and credential field readers are blocked while locked |
| Inspect and operate Settings | Semantic/visual access and ordinary interaction with the device's Settings package | Android protected-window and password restrictions remain; existing action approval gates still apply |
| Use a local unlock PIN | Admin-only AES-GCM/AndroidKeyStore vault; one locally authorized attempt for 15 minutes | Standard `com.android.systemui` PIN keypad only; no patterns/passwords, coordinates, shell input or guessing |
| Enter a spoken unlock code locally | Separate Android-authenticated opt-in; offline recognition of a PIN, explicitly spelled password, or numbered pattern; explicit confirmation and one submission per voice session | Supported native SystemUI controls only; no credential storage, chat transcript, cloud recognition or automatic retry; audible codes and recordings can be replayed |

## Private spoken unlock

Configure [Hi Omni local voice](HI_OMNI_LOCAL_WAKE.md), install an offline speech model and offline Android spoken voice, and enable **Local voice conversation after Hi Omni**. Grant **Request Android unlock**, **Assistant on the lock screen** and **Enter a spoken unlock code locally** through Android identity confirmation. Screen-off activation additionally needs the wake-screen permission and lock-screen listening enabled. Start listening while the phone is unlocked.

After “Hi Omni”, request unlock or a task requiring the screen. The local session presents Android's own keyguard, asks for the credential privately, and waits for “confirm” / “تأكيد”. Say PIN digits individually; spell passwords with explicit letter case and symbol names; describe patterns with numbered points: top row 1–3, middle row 4–6, bottom row 7–9. Android's intermediate-point rule is applied. The code is never repeated or sent to the chat provider. Android validates the single submission. A pending task resumes only after keyguard reports unlocked. Unsupported controls, low recognition confidence, refusal, timeout or revocation require manual unlock.

Spoken unlock shares the local credential input gate with saved-PIN entry. Ordinary Accessibility observation, captures and actions are suspended throughout private code input. Direct PCM recognition alternates with offline TTS; it does not start Google's recognizer or its repeated tones. Speaking a real password aloud is susceptible to overhearing and replay, so voice matching is never treated as authentication. Protected windows and OEM restrictions remain in force. No source-level test establishes compatibility with a particular phone.

## Local PIN setup

1. Enable **Request Android unlock** and **Use a local unlock PIN**, confirming your identity in Android for each.
2. Enter the real device PIN into **Device PIN (local only)** and choose **Save local PIN**. The field belongs to a screenshot-protected, overlay-protected Activity and is not saved in Compose restoration state.
3. Choose **Authorize one PIN attempt** and confirm your identity again. This is an in-memory, monotonic 15-minute permission; restarting the process or device removes it.
4. Open the standard Android PIN keypad with an empty entry and request `device_admin(action="unlock_with_saved_pin")` during a task. This action has no PIN parameter. The local executor verifies the package, visible keypad controls, empty protected entry and active consent, then consumes the permit **before** the first digit. It checks Android's locked state before reporting success.
5. If the OEM keypad is unsupported, any click fails, the user revokes access, Android rejects the PIN, or verification times out, complete unlock yourself. There is no automatic retry. Use **Delete PIN and revoke** to remove the credential and its key.

The PIN is never sent to an LLM, echoed in tool results, put in a shell command/clipboard, recorded in a learned routine, or persisted in conversation history. Ciphertext lives in `noBackupFilesDir`; the key lives in AndroidKeyStore. User consent preferences are excluded from cloud backup and device transfer. The vault deliberately uses device-local encryption with a separate one-shot user authorization: a hardware key requiring authentication on every decryption could not be used while the device is subsequently locked. This feature cannot unlock credential-encrypted storage before the first manual unlock after boot. The saved PIN is not validated against Android at save time; Android validates the single actual keypad attempt.

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

`DeviceConsentPolicyTest` covers independent scopes, denial of lock-screen screenshots/mutations, normal-app compatibility, one-shot consumption, exact expiry, revocation and process restart. `DevicePinVaultTest` runs on a real Android device/emulator and checks ciphertext, tamper rejection, zeroing (including callback failure) and deletion. `ProtectedSemanticObservationTest` checks password/PIN privacy in the semantic tree, executive summary and form placeholders.

```sh
./gradlew :app:testAdminDebugUnitTest --tests '*DeviceConsentPolicyTest'
./gradlew :app:compileNormDebugKotlin :app:compileAdminDebugKotlin
./gradlew :app:connectedAdminDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.omnidev.workspace.data.admin.DevicePinVaultTest
```

Device checks: denied-by-default scopes in Admin; enable/deny Android identity confirmation; revoke while assistant is visible; lock during an open conversation; unsupported OEM keypad; wrong PIN (one attempt only); manually unlock after failure; background Activity launch denial; first boot before manual unlock; Android 11 and Android 12+ protected Settings windows. Source checks and JVM policy tests do not establish OEM keypad compatibility.

References: [KeyguardManager](https://developer.android.com/reference/android/app/KeyguardManager), [showWhenLocked](https://developer.android.com/reference/android/app/Activity#setShowWhenLocked(boolean)), [secure sensitive activities](https://developer.android.com/security/fraud-prevention/activities), [Android Keystore](https://developer.android.com/privacy-and-security/keystore).
