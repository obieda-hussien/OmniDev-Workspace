# Fluid UI and performance

Motion policy, rendering behavior and performance verification for the application interface.

## Interface behavior

- All navigation destinations inherit short, reversible horizontal transitions with a restrained outgoing offset. Direction mirrors in RTL. Repeated taps use `launchSingleTop`.
- Shared motion policy: 260 ms navigation / 180 ms response; compact mode uses 180 / 120 ms. Devices with up to 4 GiB physical RAM, Android low-RAM devices, and battery saver use compact mode. Disabling system animator duration scale removes custom movement. Power/animation-setting changes update while running. Nonzero system duration scaling remains Compose-managed.
- Icon actions keep Material ripple, semantics and immediate clicks, adding a 0.94 press scale on the visual content through `graphicsLayer`; ripple, touch target and semantics bounds stay fixed. Disclosure panels use one animation policy. Shapes share an application-wide radius scale; individually specified component shapes remain intentional overrides.
- Chat and console follow the measured remaining tail distance in coalesced 64 ms bursts instead of restarting an animated scroll on every chunk. Dragging pauses following. Reaching the bottom, sending a new user message, or tapping the chat's jump-to-latest button resumes it. Session changes reset following. Viewport changes, measured item count/last-item size and scroll completion also trigger alignment after the IME or content changes size.
- Chat consumes Scaffold insets before applying IME padding, keeping the composer above the keyboard.
- Message formatting, Markdown segments, code highlighting, code line splitting and console redaction are remembered by their input. Reply lookup uses an ID map. Message rows expose content types and the streaming row has a stable key.
- The console cursor animates only while running, in a small drawing-only node. Compact/reduced motion uses a static cursor. Removed per-row visibility wrappers, nested size/disclosure animations, and repeated thinking-trace computation for unchanged inputs.
- UI flows use `collectAsStateWithLifecycle`, pausing below STARTED without cancelling the agent. Main ViewModels now belong to the Activity's ViewModelStore; analytics belongs to its navigation entry instead of being reconstructed during composition.

- History filtering, sorting, pinned partitioning and date grouping are cached by their inputs, including locale and date/timezone for group labels. Rows declare a shared content type.
- Analytics uses keyed lazy sections, so offscreen charts are not all composed together. Provider expansion and the Top Models tab use saveable state when sections leave/re-enter the viewport. Provider breakdown is computed once per stats snapshot.
- Browser display preparation is triggered by Activity context and session structure changes, rather than every title, URL or loading update. This avoids repeated tab persistence/reattachment on page-progress events.
- Agent Brain's status pulse reads its alpha only in drawing and becomes static in compact/reduced mode. Knowledge-type counts are computed in one pass for all filters.

## Validation and limits

No FPS, startup-time, memory, or battery improvement has been measured yet. This change is shared motion and identified hot-path work, not a claim that every runtime bottleneck is eliminated. Streaming Markdown still parses when its text changes; very large messages need on-device profiling before adding a background/incremental renderer.

`TailFollowTest` covers history reading during incoming rows and explicit resume on an emulator or device. CI includes an API 30 emulator job covering history reading, growing replies, reopening the console, reduced-motion disclosure, button click semantics and fixed touch bounds while pressed, plus Arabic/English Markdown rendering, copy and update without stale cached content. Check the completed job results for the tested revision.

Validation commands:

```sh
./gradlew :app:compileLiteDebugKotlin :app:testLiteDebugUnitTest :app:lintLiteDebug
./gradlew :app:connectedLiteDebugAndroidTest
```

On the Infinix Hot 10S and one newer Android device, verify:

1. Stream a long Arabic/English response with code. Drag into older messages; new tokens must not pull the viewport down. Tap the jump button and confirm the actual bottom of a growing message remains visible.
2. Open/close the keyboard, rotate, switch sessions, and return from the background during a run. Check composer positioning, active-run retention and absence of duplicate collectors.
3. Navigate across settings/providers/memory/analytics/brain/browser; test quick repeated taps and back gestures in LTR and RTL.
4. Toggle battery saver and system animations off while running; no cursor loop or custom movement should remain in reduced mode.
5. Profile a release build with Perfetto/Android Studio frame timing: compare navigation, long-history scrolling, streaming and console expansion against a recorded baseline. Targets: 16.7 ms at 60 Hz / 8.3 ms at 120 Hz; these are budgets, not measured achievements.

Official guidance: [Compose performance](https://developer.android.com/develop/ui/compose/performance/bestpractices), [navigation animation](https://developer.android.com/develop/ui/compose/animation/quick-guide), [lifecycle-aware collection](https://developer.android.com/topic/libraries/architecture/compose).
