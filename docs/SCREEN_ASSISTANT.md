# Omni screen assistant

Omni now has a native Android assistant session: a compact floating Compose card above the current app, with the same chat and tool runtime as the workspace. It uses Omni's purple identity, short entrance motion, reduced-motion settings, and a width cap for tablets and landscape. It does not run a hotword listener.

## Set up and use

1. Open **AI Settings → Omni on your screen → Set up assistant**.
2. Select OmniDev Workspace as the default digital assistant. In Android's assistant settings, allow screen content and screenshots.
3. Hold Home, or use the device's configured assistant gesture. The exact gesture is controlled by Android and the launcher.
4. Type a question, or tap the microphone. Microphone access is requested only when needed. Voice recognition uses an installed external recognition provider and requests offline recognition where supported.
5. Tap **Screen** to attach the full visible display, or **Select area** to open a frozen screen preview. Drag in either direction, then confirm **Use selection**. Portrait, landscape, letterboxing, and RTL use the same source-pixel mapping.
6. Review the image thumbnail, type a question, and send. **Explain / Translate / Summarize** insert an editable prompt; they do not submit it automatically.
7. Read the streaming answer in the card, copy it, stop the request, or open the full conversation. Expanding carries an unsent question and its chosen image to the full composer. A running request uses the same process-owned `ChatViewModel` in both surfaces.

The **Try it** button and the `ACTION_ASSIST` activity fallback preview the same card. A plain activity invocation does not receive the system's screenshot; screen actions explain how to enable the native assistant session. Screen capture requires the native service, Android's screen-sharing settings, and a vision-capable selected model. Protected apps can return no screenshot. This is a screenshot of the visible display, not a scrolling screenshot of an entire page.

## Runtime and image handling

- `WorkspaceChatRuntime` builds the existing dependencies once and supplies the same runtime to the activity and the assistant session. UI state and active requests survive switching between those surfaces.
- `OmniVoiceInteractionService` and `OmniVoiceSessionService` are bound only through Android's `BIND_VOICE_INTERACTION` permission.
- Android requires a recognition service in assistant metadata. `OmniRecognitionService` delegates to an installed external provider, excluding Omni itself to prevent recursive recognition when Android selects Omni as the default recognizer.
- `onHandleScreenshot` receives the foreground app's image from Android. No MediaProjection service or draw-over-other-apps permission is needed for this entry point. Screen pixels are not attached or submitted automatically.
- Full screenshots stay in memory. Only the selected crop or explicitly attached screen is persisted privately when the user sends it or transfers it to the full composer. Sent image files support chat-history references and remain in app-private storage.
- Hiding the session clears screenshot and attachment state, disposes its composition, and stops microphone recognition. The submitted chat request continues in the shared runtime; tool confirmations and mode-switch requests remain explicit in the visible UI.
- An overlay submission preserves the workspace's separate unsent text, attachments, and reply draft. Vision requests cannot be routed to text-only Team mode.
- Lifecycle and saved-state owners are supplied to the native session window and its Compose view. No activity lifecycle is assumed inside a system assistant service.

## Validation

`ScreenSelectionTest` covers portrait mapping, reverse drags, horizontal and vertical letterboxing, bounds clamping, tiny/empty selections, and invalid coordinates.

```sh
./gradlew :app:testNormDebugUnitTest --tests 'com.omnidev.workspace.ui.assistant.ScreenSelectionTest'
```

The standard pull-request workflow compiles release Kotlin and runs unit tests across app flavors. Local XML parsing, diff checks, and the public-repository guard were checked while implementing this feature. A local Android build could not start because the Gradle distribution download was unavailable in the execution environment. Compilation and unit-test results must be confirmed in CI.

Device acceptance checks remain necessary: Android 11 with Home navigation, gesture navigation, Android 14+ with screen sharing disabled/enabled, a protected app, rotation during selection, RTL, keyboard-open landscape, denied microphone access, assistant dismissal during an active request, and expanding to the full app without restarting the run.
