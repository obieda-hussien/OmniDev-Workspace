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

In **Settings → Model Selection → Media generation**, choose independent image, video and music/song models from connected Providers. All three types start **Off**. Each can be disabled independently without changing Chat, Agent or Team text-model assignments. Disabled types are rejected in the tool and before worker execution; turning a type off cancels its pending local jobs. Removing a provider key never falls back to another account.

| Type | Implemented providers | Generation controls |
|---|---|---|
| Images | Gemini, OpenAI, xAI, OpenRouter image-output catalog | Aspect ratio; supported resolution/quality; PNG/JPEG/WebP, background and compression where supported |
| Video | Gemini Veo 3.1, xAI Grok Imagine | Aspect ratio, supported resolution/duration; optional audio for xAI |
| Music & songs | Gemini Lyria 3 Clip / Lyria 3.5, existing paid MiniMax music API accounts | MP3 or supported WAV, vocals/instrumental, lyrics/language, genre, mood, instruments, tempo and duration guidance |

Only implemented generation families are offered. Image/video input support on a text model does not imply generation support. Refresh OpenRouter to discover models declaring image **output**. Built-in IDs are suggestions, not a promise that an account has quota/access. MiniMax music requires an existing paid API account under its current availability rules. Advanced model IDs are validated within the selected provider's supported generation family.

Each type also saves creative direction, things to avoid, whether chat may override supported defaults, whether to announce readiness, and optional automatic device saving (Android 10+). Music length/tempo are prompt guidance rather than exact timing guarantees; Lyria Clip is a fixed 30-second MP3. Jobs snapshot their request settings; changing defaults affects new jobs. The text agent cannot enable a type or substitute its selected provider/model.

The `media_generation` tool supports `image`, `video`, `music`, `attach`, `status` and `cancel` under the existing tier/tool policy. Team workers share the same media selections and deliver their job/file cards through the same conversation handler as Chat and Agent. For example, after enabling a video model, ask **اعمل فيديو عن غروب الشمس فوق البحر** in ordinary Chat. The tool creates a persistent job and immediately attaches its card. WorkManager generates/polls/downloads; the card becomes playable only after the complete file is saved. A ready message is inserted once into the originating conversation, including while that conversation is closed; deleted conversations are never recreated. Optional auto-save sends images to Pictures/Omni, video to Movies/Omni and audio to Music/Omni. A save failure preserves the generated chat file and offers manual Save.

Provider operation IDs persist so video polling resumes without another billable create request. Job/card records remain available in old conversations. Cancellation prevents late completion from overriding a cancelled card; provider work already submitted may still incur usage. Queued/processing work is never announced as a completed output.

Creation is not automatically replayed after an ambiguous interruption. HTTP automatic connection retries and credential-bearing redirects are disabled. Only the exact Google file-download host/path receives its key, and external redirects do not receive it. Failed existing video operations can be checked again without generating a second video. Outputs and temporary writes are bounded; partial downloads are removed.

## Generation status and presentation

Every card distinguishes queued, requesting/generating, downloading, waiting, completed, failed and cancelled states. HTTP access/quota errors, invalid responses, unexpected exceptions and worker interruption persist a curated error code/detail before exit. Failed creation results still attach their cards in Chat, Agent and Team, and terminal failures are announced once in their original conversation. Raw provider responses and exception messages are not exposed.

Only existing video operations retry transient status-check failures, with a bounded consecutive failure budget; the card retains the last error while waiting/checking. Authentication/model refusals stop immediately. **Check existing job** is available for recoverable failed operations and never submits a second create request. A missing job/file or ended/missing WorkManager task renders an explicit failure rather than an indefinite generating indicator. Foreground lifecycle monitoring reconciles stale jobs and pauses in the background.

Interactive requests use expedited WorkManager scheduling, with a quota fallback and an Android 11 foreground notification. Legacy queued WorkSpecs are promoted in place, preserving their work ID without cancelling or replaying creation. While a card is visible, an unstarted request older than five minutes becomes `QUEUE_TIMEOUT`; its original prompt remains available through **Start now**. A restart retains the job/conversation ID and resets only its queue deadline. Provider requests with uncertain outcomes are never restarted through this action. Atomic snapshot checks keep a stale queue monitor or failure announcement from overwriting a worker that already claimed the request.

Media output cards appear below the current run console and below the saved console when reopening history. Stored message ordering is unchanged. The floating assistant shares this presentation. Cards use a bounded rounded preview, status chip, inline diagnostics, prominent Save and a secondary actions menu. Images expand into the existing zoom viewer; local videos have a sampled cover and explicit play action; audio has play/pause/seek/stop with formatted duration.

Active cards, including queued/waiting requests, display moving soft gradient light with distinct image sheets, a travelling video film strip, or a music equalizer and sound rings. These are decorative scenes rather than fabricated provider output or progress percentages. Failure/cancellation stops ambient animation. Reduced motion freezes the scene; compact/low-memory phones keep fewer primitives at 30 fps. Frame updates stop when the foreground lifecycle stops. No Android 12-only blur API or full-size blur bitmap is required. Card resizing uses a short interruptible transition when motion is enabled.

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

Media API contracts:
- https://ai.google.dev/gemini-api/docs/music-generation
- https://ai.google.dev/gemini-api/docs/video
- https://docs.x.ai/developers/model-capabilities/images/generation
- https://docs.x.ai/developers/model-capabilities/video/generation
- https://openrouter.ai/docs/guides/overview/multimodal/image-generation
- https://platform.minimax.io/docs/api-reference/music-generation
