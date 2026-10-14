# Runtime stability and assistant permissions

## What remains in Lite

Lite retains BYOK Chat and bounded research, scoped files, memory, generated media, trusted OmniLink/MCP, a floating/default assistant, local voice and local inference. Agent/Team, JGit, Accessibility implementation, Shizuku/native command transports and Linked Accounts are excluded. Vosk/JNA serve offline speech; llama.cpp serves local inference. Room/DataStore, Compose, WorkManager, OkHttp, Jsoup and WebKit support active features. These are required runtime libraries, not optional agent SDKs.

Unused CameraX core/camera2/lifecycle/view, ML Kit text recognition, Retrofit/converter and AndroidX Window are removed from all builds. `:app:verifyLiteRuntimeDependencies` rejects prohibited direct or transitive SDKs and saves `app/build/reports/lite-runtime-dependencies.txt`. Removing declarations alone does not establish a measured APK-size saving; consult the release artifact.

## Assistant permissions

| Feature | Lite | OEM | Prerequisite |
|---|---|---|---|
| Default assistant panel | Available | Available | Select Omni as Android's digital assistant; gesture availability depends on the launcher/device |
| Optional minimized bubble | Available | Available | Draw-over-other-apps approval; foreground notification |
| Microphone/local wake | Available | Available | Microphone permission, explicit activation, selected assistant and installed/trained models |
| Accessibility UI automation | Excluded | Excluded | Neither flavor includes an Accessibility service |
| Lock-screen visibility | Conditional | Conditional | User lock-overlay/wake consent and Android window policy; protected windows can hide overlays |
| ROM system actions | Excluded | Conditional | Provisioned Omni System broker and matching OEM identity/signer |

Displaying a panel or bubble does not require Accessibility. It also does not authorize tapping arbitrary other applications. OEM hardware wake requires actual manufacturer SoundTrigger/DSP support and a manufacturer model; sideloading an APK cannot supply it.

The Accessibility service declaration lives only in Norm/Pro/Admin manifests, so Lite/OEM do not rely on removing a shared service declaration. The flavor guard verifies this boundary. Release builds use the [R8 9.1.29 compiler required for Kotlin 2.4 metadata](https://developer.android.com/build/kotlin-support), via the [documented compiler override](https://r8.googlesource.com/r8/+/refs/tags/9.1.29). Four optional Error Prone analysis annotations referenced by Tink are individually allowlisted; missing runtime crypto implementations still fail shrinking.

## What Omni System means

`oem-system` is a separate service application, paired with `oem-system-contract`. The manufacturer installs and grants it through the ROM configuration in [the OEM guide](../oem/README.md). The OEM assistant calls typed AIDL operations, while the broker checks its exact caller package, signing identity, signature permission, action allowlist, arguments, lock state and ROM provisioning before acting.

Implemented operations currently cover status, approved settings read/write, audio volume and Wi-Fi state. The assistant does not share `android.uid.system`, and the broker exposes no arbitrary shell. Hardware wake integration is separate. Omni System is not an Android replacement or a promise of universal privileged control; actual ROM signing, grants and device testing remain necessary.

## Startup, shutdown and diagnostics

Application startup now creates database/learning/repository/browser engines lazily. Lite does not initialize advanced ML/orchestration engines or scheduled-task infrastructure. Selecting Omni as the default assistant no longer eagerly assembles the whole brain. Launcher/extension discovery runs off Main. Remote event subscribe/unsubscribe also runs on IO, rejects stale callbacks and atomically reserves each service binding to avoid duplicate connections. Lite ignores old always-on sync preferences and cancels watchdog recovery for removed services; actual durable Chat work remains recoverable when the user reopens the app.

Microphone interruption uses a generation signal. The capture coroutine performs nonblocking frame reads with cancellation checks and a bounded no-audio deadline, then owns native stop/release on IO. TTS calls and connection cleanup run off Main rather than blocking service destruction on an audio/TTS Binder connection. This addresses identified blocking paths, but is not proof that a particular device's recurring ANR is fully resolved.

Crash reporting installs before providers and credential initialization. Logs are available in private device-protected storage, including direct boot; existing credential-storage logs remain readable. Android 11+ exit history records supplied ANR/native-crash/low-memory/other exit reasons, with bounded ANR traces when available. An uptime-based Main heartbeat reports one observed stall per blocked interval, ignores debugger pauses and never kills or restarts the process. Deep sleep does not count as a stall. An observed stall is labeled as a potential ANR, not a confirmed Android ANR.

The debug console refreshes when records arrive. Writes use a bounded queue, log count and record length, so diagnostics do not grow unbounded. Crash files are synchronous; caught failures and exit-history imports are best effort. Android/OEMs can omit exit records/traces; sudden native termination cannot execute a Java crash handler. Caught local voice failures now enter the console as well; private voice failures retain stage/type/frames without exception text or causes. All diagnostics remain local until the user exports them.

## Long Chat turns

Chat has no application-imposed token, tool-call or round ceiling in any flavor. Full loaded conversation history enters local rolling compaction off Main rather than a hard last-20-message cutoff; saved conversations remain intact. Per-response output uses the selected model's advertised output allowance, without the old 4,096-token application clamp. Known provider truncation (`length`/`max_tokens`) continues automatically in the same assistant response with a visible preserved prefix until completion or user Stop. Streaming UI updates are coalesced to avoid per-token layout pressure, with a final flush and durable checkpoint.

Usage/cost accounting remains active, with no run-budget denominator. Exact duplicate tools never execute again in the same run. A batch consisting entirely of rejected/duplicate requests enters final synthesis; repeated identical continuation text stops a stuck provider. Completed actions are not replayed during completion retries. Flavor authorization, confirmations, transport timeouts, provider context windows, quotas/rate limits and physical device memory still apply; removing an application cap cannot remove those constraints.

## Verification

Regression coverage includes stall deduplication/recovery/debugger behavior, long-history compaction without stored-history mutation, useful work beyond old run ceilings, same-response provider continuation/repetition/cancellation, non-serialized UI prefixes, private diagnostic file/console visibility, and ignoring obsolete Lite sync preferences. Android CI owns resolved dependency checks, flavor compilation/unit tests, API 30 UI tests, lint and release assembly. A physical Infinix/OEM device soak test is still required to validate the reported hours-apart behavior.
