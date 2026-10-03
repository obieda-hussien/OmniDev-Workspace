# Omni screen assistant

Hold Home or use Android's assistant gesture to open a compact OmniDev card over the foreground application. The card uses the **agent model and AgentPipeline**, existing tools, Markdown message renderer, reply/copy controls and Agent Live Console. It never auto-routes to text-only chat because a workspace folder was not selected.

## Set up and use

1. In **AI Settings → Omni on your screen → Set up assistant**, the result-aware setup bridge requests Android's assistant role. A refused/unavailable OEM chooser falls back to public default-app settings; an already-selected assistant opens default-app settings so its assistant/screen-access options can be reviewed. Allow screen content and screenshots there. Launch failures try additional public Settings routes and return readable manual instructions instead of crashing.
2. On Norm, Pro, OEM and Admin, enable OmniDev Accessibility to inspect and interact with application UI. Lite accepts attached screens and files but does not expose live app control or bubble setup. The separate Assistant capabilities page has been removed; configure permissions in Android settings as needed. Tool availability still follows app flavor, configured tool switches and Android permissions.
3. Invoke Omni. Type or tap the microphone; recording never starts automatically. Recognition uses the device language, partial transcription, an external recognizer excluding Omni's own bridge, bounded provider fallback and system voice input when the embedded service fails. Offline recognition is not forced. **+ → System voice input** also provides a manual fallback, including after a no-match error. Permission, network, recording, language and no-speech failures have distinct messages.
4. **Screen** attaches the screen supplied at invocation. **Select area** opens a frozen preview and supports reverse drags, portrait, landscape, letterboxing and RTL. Pixels stay in memory until an explicit send or expansion.
5. **+ → Files, photos & videos** opens Android's document picker directly from the floating card through a transparent result bridge. It returns to the same floating conversation, including when cancelled, without opening the full workspace or requesting broad storage access. Up to five files, 10 MB each and 15 MB combined, are copied to private storage with their actual accessible paths. Images go to vision-capable agent models. Other files, including video, are supplied as paths for tools: attaching a video does not imply that every frame or its audio was analyzed. **File path** is an inline editor in the card and adds an explicit path for existing accessible files. Existing storage, scope and tier policies continue to apply.
6. The transcript reuses the normal chat's Markdown and message components. **Activity** expands a timestamped track of actual phases, tool starts, completed/failed results and errors across the current session. Detailed parameters and output remain in the existing collapsible console, with credential redaction.
7. An explicit ordinary field-fill request can execute directly. An explanatory request such as “I don't know what to write here” cannot authorize typing. On Norm and Pro, ambiguous mutations, clicks, chains and submissions show an exact **Allow this action?** button; declining does not execute the action. OEM and Admin follow their existing audited automatic-approval policy. Lite denies device mutations. Existing privileged-action confirmations remain in force. Protected inputs use the existing user handoff.
8. On flavors supporting live app control, the **minus** button minimizes to a draggable OmniDev bubble. Tap to restore the same conversation; long press or use the notification's Close action to end it. Android's display-over-other-apps permission is needed only for the optional bubble. A foreground notification remains while minimized; no audio capture continues.
9. With bubble permission, device mutations temporarily minimize the assistant before interacting so it cannot intercept taps on the target app. Without that permission, semantic node actions remain available, while coordinate/force gestures explain how to minimize first.
10. **Close** stops the current run and resets the floating composer. Saved messages, partial checkpoints and execution traces remain in the application's normal chat history. Every fresh Home/assistant invocation starts a new conversation. Returning from the bubble, picker, voice input or screen-access settings resumes the current one. **Expand** opens its saved session in the full application and carries an unsent draft and attachments.

## Runtime

`WorkspaceChatRuntime` creates independent process-owned view models for the workspace and assistant, backed by the same Room chat repository. Starting a floating conversation cannot send or replace the main composer's draft. The assistant receives an app-private file scope and flavor-appropriate tool domains, respects configured tool disable switches, uses a bounded agent loop, and avoids self-reflection passes and forced deep thinking for short screen tasks.

`AssistantFlavorPolicy` derives capabilities and approval behavior from the installed `TierPolicy`. `TierToolGate` filters both native and MCP schemas and is checked again before either dispatch path, including unadvertised calls and capability changes during a run. The assistant never unlocks a privileged backend merely because Android permission was granted.

| Flavor | Screen-assistant capabilities | Proposed-action approval |
| --- | --- | --- |
| Lite | Research, memory, attached screens/files; no live UI control or bubble | Device mutations unavailable |
| Norm | Ordinary Accessibility actions and bubble; no Root/Shizuku | User button |
| Pro | Norm capabilities plus permitted Root/Shizuku/security tools; runtime authorization required | User button |
| OEM | System integration with device entitlement; no Shizuku bridge | Automatic, audited |
| Admin | Existing full policy capabilities, subject to runtime availability | Automatic, audited |

The native `VoiceInteractionSession` is a service-hosted window, not an Activity. Attachment choices, file-path input and action confirmations therefore render inside its existing card; they never create an `AlertDialog`, `DropdownMenu` or another application window from the session context. Confirmations reuse the normal chat's preview/diff body, have explicit Allow/Cancel controls, and temporarily replace the conversation controls so the review stays readable on small displays. The full workspace keeps its ordinary dialog.

`AssistantRuntime` owns temporary state across Activity-result handoffs and minimization. Result bridges carry the conversation generation, so a late picker/voice/permission result cannot modify a newer Home invocation. Missing external handlers and failed bubble starts return an actionable message; a gesture cannot proceed if minimization failed. Android binds the native assistant services through `BIND_VOICE_INTERACTION`. Only the optional user-started bubble uses `SYSTEM_ALERT_WINDOW` and a special-use foreground service; it is non-exported and non-sticky.

Accessibility reads application windows underneath the assistant rather than the assistant's own focused window. Native assistant screenshots are of the display at invocation, not scrolling page captures; protected applications can refuse them. Screen, file and page content are task evidence, never action authorization.

## Validation

`AssistantWindowControlsTest` exercises approval/cancellation and attachment/path controls with an application context (no Activity token), and intercepts a cancelled role request to verify the default-app fallback. Run it on an API-30 emulator or device:

```sh
./gradlew :app:connectedNormDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.omnidev.workspace.ui.assistant.AssistantWindowControlsTest
```


`ScreenSelectionTest` covers crop geometry. `AssistantActionPolicyTest` covers English/Arabic explicit intent, explanatory/negative requests, screenshot instruction isolation, read-only actions, submission boundaries, canonical browser aliases and credential fields in action previews. `AssistantFlavorPolicyTest` checks actual per-build capabilities and gates across all five flavors. `AssistantToolEligibilityTest` checks execution-time revocation and forged MCP calls.

```sh
./gradlew :app:testNormDebugUnitTest --tests 'com.omnidev.workspace.ui.assistant.ScreenSelectionTest' --tests 'com.omnidev.workspace.data.assistant.*' --tests 'com.omnidev.workspace.domain.engine.AssistantToolEligibilityTest'
```

CI compiles release Kotlin and runs unit tests across app flavors. Physical-device acceptance remains necessary for recognizer packages, Home/gesture invocation, bubble permission and drag/restore/close, picker cancellation/rotation, retained history versus new invocations, RTL/landscape/keyboard layouts, protected screens, and actual target-app field/tap behavior. No Android device is attached in the execution workspace.
