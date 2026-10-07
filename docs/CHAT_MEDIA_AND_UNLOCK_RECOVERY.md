# Private unlock recovery and chat media

`device_admin(action=request_unlock)` selects one authentication route: an authorized saved local PIN, a configured private offline voice session, private local code entry when voice is unavailable, or Android's own authentication prompt. A failed saved PIN input never falls through to another credential attempt. Android keyguard state, rather than a successful click or dismissal callback, proves that the device unlocked.

## Fixes for the reported stopped voice session

The old voice session initialized TTS before opening the keypad and replaced most preparation/capture exceptions with “Voice session stopped. Check the local model, offline voice and device permissions.” The tool could therefore stop before entering a digit even with microphone/accessibility permissions granted. The reported log alone cannot identify which preparation stage failed on the actual phone.

- A resumed private Activity now starts the microphone foreground service before handing focus to Android. Starting a microphone service is distinct from merely possessing RECORD_AUDIO permission.
- Missing offline speech output before credential capture can open the private local code dialog. No captured credential is retried. Codes are never put into chat, provider requests, intents, saved view state, logs, or clipboard.
- Preparation and capture failures have separate redacted model, TTS, microphone and native-prompt diagnostics. Exception messages are not interpolated into voice diagnostics.
- Saved and spoken/local entry share genuine SystemUI credential-window discovery. A notification or another SystemUI window cannot hide a supported keypad from discovery. Recognized OEM resource-ID aliases are supported; ambiguous controls remain unavailable.
- Empty PIN inputs include the AOSP PasswordTextView representation. Ten visible, enabled, clickable digit controls are required. Input is paced so SystemUI can consume each digit; keypads that auto-submit need no enter button. Success always requires Android to report unlocked.

Automatic PIN entry still requires the Admin edition, a saved local PIN and explicit authorization from Device access. Choose **Remember PIN authorization** to retain it until revoked, deleted, app data is cleared or the app is uninstalled. Failed/interrupted remembered input pauses further attempts until the user resumes it. Device Admin, root and Shizuku permissions do not provide or authenticate a missing device credential. After reboot, first unlock is still controlled by Android; credential-encrypted app data is unavailable before it.

The private dialog requires the private credential, unlock and lock-screen overlay grants. The user enters PIN, password, or 4–9 numbered pattern points and confirms one attempt. Unsupported keypads remain a manual handoff. Infinix/XOS keyguard behavior requires phone validation; no physical phone was controlled during development.

## Chat media

Both user and assistant messages use the same renderer, including the floating assistant's conversation. Uploaded files are imported into app storage so their URI grants do not expire when the original picker closes. Image payloads sent to models remain separately bounded; storing/previewing a video does not claim the text model can interpret it.

- Images: sampled inline previews, full-screen viewer, pinch zoom/pan, double-tap zoom/reset, close, gallery save and sharing.
- Video: explicit playback in a full-screen viewer with platform playback controls. Unsupported codecs have a visible error.
- Audio: play/pause, seek, duration, stop/close. A shared playback owner prevents overlapping chat audio. Playback pauses when the host stops and releases when the card/viewer leaves composition.
- Documents/other files: filename/type/size, open with another app, save, share.
- Phone paths: existing readable files under shared/external storage and app media/assistant attachment directories. Quoted or Markdown paths support spaces and Arabic names. Other private app data, including the credential vault, is not automatically rendered/exported; the file picker grants explicit access.
- Direct public HTTPS media links can be previewed/downloaded. Size limits, private-network DNS checks, bounded redirects and credential-free external downloads apply.
- Android 10+: saving uses MediaStore pending rows and publishes only after the complete streamed copy; failure removes the row. Older Android uses the document picker. Sharing uses scoped FileProvider directories and temporary read grants.

## Generation

The `media_generation` tool is available in Chat and Agent under the existing tier/tool policy. It supports `image`, `video`, `attach`, `status` and `cancel`. Disabled tools remain disabled.

Image providers: Gemini (default `gemini-3.1-flash-image`) or OpenAI (`gpt-image-1.5`). Video: Gemini Veo (`veo-3.1-fast-generate-preview`, 8 seconds, 720p). Explicit model IDs are supported. Keys come from Providers; media access/quota is independent of the conversation's selected text model. No retired OpenAI Sora endpoint is used.

The tool creates a persistent local job, and immediately attaches its card to the conversation. WorkManager performs generation/polling/download. Completed files are stored before a card becomes playable. A queued/processing operation is never presented as a generated file. Video operation IDs persist so process recreation can poll the existing operation. Job/card records remain available for old conversations. Cancellation prevents late completion from overriding the cancelled card; already-submitted provider work may still incur usage.

Creation is not automatically replayed after an ambiguous interruption. HTTP automatic connection retries and credential-bearing redirects are disabled. Only the exact Google file-download host/path receives its key, and external redirects do not receive it. Failed existing video operations can be checked again without generating a second video. Outputs and temporary writes are bounded; partial downloads are removed.

## Validation limits

Local validation compiles the changed Android/private-unlock/media implementation and Compose media renderer against Android/AndroidX APIs, with fixtures isolating unrelated app services. JVM regressions cover route precedence and cancellation, keyguard verification, voice policy/diagnostics, file-reference extraction, Chat tool-policy execution, and provider HTTP contracts. Provider contract fixtures do not call billable APIs. Instrumented provider tests are included for Android CI/device execution.

This validation is not a full all-flavor Gradle/APK build or a physical keyguard/playback/gallery test. Verify on the target phone: saved PIN with remembered authorization; missing/offline TTS fallback; native/biometric unlock; one failed/cancelled input; image zoom and gallery save; MP3 seek/stop; MP4 close/background pause; attachment retention after restart; queued video completion and cancellation.

Primary contracts:
- https://developer.android.com/about/versions/11/privacy/foreground-services
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/training/data-storage/shared/media
- https://developers.openai.com/api/reference/resources/images/methods/generate
- https://ai.google.dev/gemini-api/docs/image-generation
- https://ai.google.dev/gemini-api/docs/veo
