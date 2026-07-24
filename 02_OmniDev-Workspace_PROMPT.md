# OmniDev-Workspace — Integration Prompt
### Repo: obieda-hussien/OmniDev-Workspace · 8 phases, this repo only

> Fed second, after `01_OmniLinkSDK_PROMPT.md` has shipped a tagged release. This repo consumes that SDK via JitPack (transitional — see the SDK repo's migration note toward GitHub Packages) — it does not build the shared module itself anymore. **Guardrail for every phase below:** Workspace stays a pure orchestrator. It calls typed actions on each satellite app; it never re-implements a satellite app's business logic locally. The moment Workspace starts holding its own copy of note-search logic or photo-filtering logic, that's duplicated business logic waiting to drift out of sync — route around it, don't recreate it.

---

## Phase 1 — Consume `OmniLinkSDK`

1. Add the JitPack repository to `settings.gradle.kts`'s `dependencyResolutionManagement`. `OmniLinkSDK` is now a public repo, so this is a bare `maven { url = uri("https://jitpack.io") } }` entry — no credentials or token needed.
2. Add `implementation("com.github.obieda-hussien:OmniLinkSDK:v1.0.0")` (or whatever tag is current) to `app/build.gradle.kts`.
3. `com.omnilink.sdk.permission.BIND_EXTENSION` merges in automatically from the SDK's own `AndroidManifest.xml` (see `01_OmniLinkSDK_PROMPT.md` Phase 7) — nothing to manually declare here. Confirm it after syncing by checking the merged manifest (`app/build/intermediates/merged_manifest/.../AndroidManifest.xml`) actually contains it.

**Acceptance criteria:** project compiles against the published SDK artifact with no local module present; the SDK's public interfaces (`ActionRequest`, `ActionOutcome`, `AccessController`, `AuditLogger`, `CallerContext`) resolve correctly.

---

## Phase 2 — Workspace-specific adapters

The SDK only knows about generic infrastructure concepts — a caller, a request, a response, a permission decision. It has no idea what "TierPolicy" or "ConfirmationGate" are. This phase writes the Workspace-only glue that connects the two, without leaking Workspace concepts back into the SDK.

**Important distinction, since the same interface types get implemented on both sides of the connection:** each satellite app's own `ExtensionService` subclass has its own `AccessController`/`AuditLogger` (e.g. `NoteLinkService`'s), enforced server-side, deciding whether *that app* permits the call. `WorkspaceAccessController`/`WorkspaceAuditLogger` here are Workspace's *own, separate* instances, used client-side by `ExtensionConnectionManager` as a pre-flight check before Workspace even attempts a remote call — e.g. refusing to call any extension at all if the current `TierPolicy` flavor doesn't permit extensions. These are two independent policy decisions in two different processes that happen to reuse the same SDK interface shape; neither substitutes for the other.

1. Implement `WorkspaceAccessController : AccessController` — consults this app's existing `TierPolicy` (flavor gating) and `ConfirmationGate` (per-action user confirmation) to decide allow / deny / requiresConfirmation for a given `CallerContext` + `ActionRequest`, checked before `ExtensionConnectionManager` attempts the remote call.
2. Implement `WorkspaceAuditLogger : AuditLogger` — forwards every logged attempt into the existing `OmniAuditLog`, at the same granularity already used for privileged shell commands. This is what makes `OmniAuditLog` the one unified, user-visible trail across every extension Workspace talks to, regardless of what each satellite app's own local logger separately records.
3. Register both adapters with `ExtensionConnectionManager` so every extension call Workspace initiates goes through them before the remote call is made.
4. **Implement the confirmation flow as specified in `01_OmniLinkSDK_PROMPT.md` Phase 7's clarification.** Before calling any `ext_` tool, `ExtensionConnectionManager` checks the target action's `CapabilityDescriptor.requiresConfirmation` from the already-cached `CapabilityManifest` (from Phase 3's discovery cache). If `true`, show `ConfirmationGate` first and only make the remote call if the user agrees — no satellite app can show Workspace's UI, so this client-side check is the primary mechanism, not an afterthought. If a remote call unexpectedly comes back with `ActionOutcome.Failure(ActionError("requires_confirmation", ...))` despite the cached manifest not having flagged it, treat this as a hard abort and surface it to the agent as "this action needs confirmation" rather than attempting any automatic retry.

**Acceptance criteria:** a throwaway test extension, bound from this app, with `moveToTrash`-style `requiresConfirmation: true` in its manifest, triggers `ConfirmationGate` *before* any remote call is made, verified by confirming the remote service's `onAction` is never invoked until the user confirms; every attempt (allowed, denied, or confirmed) shows up in `OmniAuditLog` — verified from Workspace's own client-side adapters, independent of whatever the test extension's own server-side `AuditLogger` separately does.

---

## Phase 3 — Discovery & connection management

1. Update `ExtensionConnectionManager` to discover extensions via `PackageManager.queryIntentServices()` against `OmniLinkConstants.ACTION_EXTENSION_BIND` (`"com.omnilink.sdk.action.EXTENSION_BIND"`), instead of any hardcoded package list.
2. Add reconnect-with-backoff: on `onServiceDisconnected` (process death of the satellite app), retry binding with exponential backoff (e.g. 1s, 2s, 4s... capped) rather than treating disconnect as permanent.
3. Cache each connected extension's `CapabilityManifest` (including `protocolVersion` and `sdkVersion`) after first successful bind; invalidate cache on reconnect.

**Acceptance criteria:** killing a bound extension's process from `adb shell am force-stop` results in automatic reconnection within the backoff window without a Workspace restart.

---

## Phase 4 — Unify extension tools with the existing MCP tool pipeline

1. Extend the existing `McpRegistry` translation pattern (from `mcp_plan.md`) to also enumerate currently-bound extensions from `ExtensionConnectionManager`.
2. Prefix locally-bound extension tools `ext_[app_name]_`, mirroring the `mcp_[server_name]_` convention already used, so `ToolDefinition` objects are source-agnostic to the LLM.
3. In `AgentPipeline.kt`, merge `McpRegistry.fetchAllAvailableTools()` and the new extension-tool list into one `toolDefs` array before each completion call.
4. Route any tool call starting with `ext_` through `ExtensionConnectionManager.execute(appName, action, payload)`, parallel to how `mcp_` calls already route through `McpHttpClient`. **Implement `execute()` as a suspend function calling `executeActionAsync` + awaiting the `IOmniResultCallback` result, not the synchronous `executeAction`.** Per the SDK's Phase 7 guidance, the sync path blocks a binder thread in the *callee's* process for the full duration of the call — since agent tool calls routinely trigger real work (DB queries, network fetches, file I/O) in the satellite app, defaulting to the async path here is what actually delivers on the "never block the binder thread" guarantee the SDK's base class was designed around.

**Acceptance criteria:** a single agent prompt that should touch two different bound extensions resolves to two tool calls in one ReAct loop, with no code path above the registry layer distinguishing MCP from local extension origin; inspecting `ExtensionConnectionManager.execute()`'s implementation confirms it calls `executeActionAsync`, not `executeAction`.

---

## Phase 5 — Tier gating verification

1. Confirm `WorkspaceAccessController` (Phase 2) actually denies extension binding on `lite`/`norm` flavors — extension control sits alongside Shizuku/root as `pro`/`oem`/`admin`-only.
2. Confirm every `AccessController` denial and every successful `ext_` tool call produces an `OmniAuditLog` entry via `WorkspaceAuditLogger`.

**Acceptance criteria:** a `lite`-flavor build cannot bind any extension (verified by observing an access-controller denial, not a crash); querying `OmniAuditLog` after a test session shows every extension call attempted, success or denied.

---

## Phase 6 — Heartbeat: one shared periodic tick, not one service per extension

If every extension that wants periodic checks (price watchers, future ideas, anything else) runs its own foreground service, every user of Workspace — not just you, anyone installing this app on their own phone — ends up with multiple persistent notifications and multiple battery drains stacking up. Centralize it once, here, so every extension benefits.

**This phase is built around a real platform constraint, not the older assumption of an indefinitely-running foreground service:** Android 15 (API 35) introduced a hard 6-hour-per-24-hour runtime cap on `dataSync` and `mediaProcessing` foreground service types — after 6 cumulative hours, the system calls `onTimeout()` and force-stops the service; it does not restart until the app is brought to the foreground again. An "always-on background service ticking forever" design — which used to be the practical workaround for `WorkManager`'s 15-minute floor — no longer holds up on current Android versions. Design around the platform's actual current behavior, not the older assumption.

1. Use `WorkManager` periodic work as the primary, durable mechanism — this is Google's actually-sanctioned pattern for recurring background checks, survives reboots correctly, and isn't subject to the `dataSync` FGS timeout at all. Register one periodic worker at its floor interval (15 minutes); every satellite extension's `_tick` gets driven from this worker's execution, not from a custom loop.
2. On each `WorkManager` execution, iterate currently-bound extensions whose cached `CapabilityManifest.supportsTicks == true`, and check each against its own `preferredTickIntervalSeconds` (default `900` = 15 minutes, matching the floor — see `01_OmniLinkSDK_PROMPT.md` Phase 2). Only call `executeActionAsync` with the reserved action name `_tick` on an extension whose own interval has actually elapsed since its last tick — **never the synchronous `executeAction` for ticks**, since a tick handler doing real work (e.g. `OmniPriceWatch` fetching a product page over the network) is exactly the slow-call case the SDK's Phase 7 guidance warns about; using the sync path here would block a binder thread in the satellite app for the duration of a network request, on every tick, for every product due.
3. **Sub-15-minute cadence is the exception, not the default.** If a specific extension genuinely needs tighter-than-15-minute responsiveness, that can only be offered honestly while Workspace's own UI/agent session is actively in the foreground (e.g. an active `Activity` or a short-lived, correctly-typed foreground service tied to that session) — never as an indefinite background promise, since the platform itself no longer allows that pattern to run unbounded.
4. **Don't let ticks flood the audit log.** Log tick failures and denials at normal verbosity; log successful ticks at a low-verbosity/debug level only, or roll them up into a periodic summary.
5. If any future extension needs true exact-alarm-level precision independent of `WorkManager`'s scheduling, that requires the separate `SCHEDULE_EXACT_ALARM` permission (Android 12+) — out of scope for this phase, note it as a future option rather than building it now.

**Acceptance criteria:** a test extension declaring `supportsTicks: true` and the default `preferredTickIntervalSeconds` receives `_tick` calls roughly every 15 minutes via `WorkManager`, correctly resuming after both a process death and a full device reboot without requiring the app to be foregrounded; the audit log after a day of running shows a small number of entries, not one per tick per extension at full verbosity; no component in this phase is declared as a `dataSync`-type foreground service expected to run unbounded.

---

## Phase 7 — Verification

1. Write an instrumented test using a second, deliberately differently-signed test APK that advertises the `EXTENSION_BIND` intent-filter — confirm the bind is rejected.
2. Write an instrumented test that force-kills a bound extension mid-session and confirms Phase 3's reconnect logic recovers within the backoff window.
3. Write a test confirming a `requiresConfirmation` result actually surfaces a real `ConfirmationGate` prompt rather than silently proceeding or silently failing.
4. Write a test confirming a `supportsTicks: false` extension never receives `_tick` calls, and a `supportsTicks: true` extension does, at roughly the configured cadence.

**Acceptance criteria:** all four tests pass in CI (`android-ci.yml`), and none of them require a real satellite app to be installed — a synthetic test service is enough.

---

## Phase 8 — Final SDK integration: shared signing, event bus consumption, and release-build verification

`OmniLinkSDK` is now finished (10 phases, public repo, `v1.0.0` tagged and verified building on JitPack). This phase is the remaining work that only makes sense once the SDK is actually final — closing out pieces the earlier phases above assumed would exist but didn't yet have a concrete answer for.

1. **Establish and document the shared signing keystore — this is the single most consequential item in this phase.** Because `com.omnilink.sdk.permission.BIND_EXTENSION` merges into every consumer's manifest from the same library (per `01_OmniLinkSDK_PROMPT.md` Phase 7), Android's own platform behavior means **every app consuming this SDK — Workspace, Equalizer, Note, Memoria, and PriceWatch — must be signed with the same certificate**, or the second one installed fails outright with `INSTALL_FAILED_DUPLICATE_PERMISSION` (confirmed Android platform behavior, true since API 21, this ecosystem's own `minSdk`). Concretely:
   - Generate one release keystore for the whole ecosystem (if one doesn't already exist) and use it to sign Workspace's release builds going forward.
   - Compute its SHA-256 fingerprint (`keytool -list -v -keystore <path> | grep SHA256`, or `apksigner`) and publish it in this repo's own documentation (e.g. a `SIGNING.md` or a section of the main README) as the canonical value every satellite-app repo's `SignatureSecurityValidator` allowlist references — replacing the `<SHA-256 of Workspace's release signing certificate>` placeholder used in files `03`, `04`, `05`, and `07` with this concrete, checked-in value.
   - This also resolves Omni-launcher's (file `06`) separate `BIND_LAUNCHER` permission check: Workspace (the caller) and Launcher (the callee) must independently also share this same certificate for that signature check to succeed — same keystore, same requirement, different permission.
2. **Consume the SDK's event bus.** Subscribe to `observeEvents(): Flow<OmniEvent>` (from `01_OmniLinkSDK_PROMPT.md` Phase 4) for every currently-bound extension whose `CapabilityManifest.protocolVersion >= 2` indicates event support. **Treat every incoming event as data the agent may become aware of, never as something that directly triggers a privileged action** — the same rule already applied to webpage content in the payment vault (file 08) and to agent tool-calling generally: an event like "price dropped on tracked product X" surfaces as a proactive notice (e.g. through the existing notification system, or as context the agent can choose to act on in its next turn), but any actual *action* the agent then takes in response still goes through the normal `ext_` tool-call path — `AccessController`, `ConfirmationGate`, and audit logging all still apply. An event is a tip, not a command; nothing about receiving one bypasses the gates every other action already goes through.
3. **Verify R8/consumer-proguard compatibility in a real release build.** The SDK ships `consumer-rules.pro` protecting its `@Serializable` classes and AIDL stubs from being stripped or renamed. Build Workspace's own `release` variant (which will have its own R8 configuration, potentially aggressive given this app's size) and confirm nothing from the SDK's protocol layer breaks — a quick way to check is exercising a real `ActionRequest`/`ActionOutcome` round-trip against a live extension in a minified release build, not just a debug build.
4. **Confirm the public, credential-free JitPack setup resolves cleanly end to end** in this actual app, now that both sides of the equation (the SDK repo, and this app's own `build.gradle.kts`) are final — a quick, final sanity check rather than a redo of Phase 1's original setup.

**Acceptance criteria:** two real signed builds — Workspace and any one satellite app — both built with the same release keystore, install side by side on one device without a `INSTALL_FAILED_DUPLICATE_PERMISSION` error; a test extension publishing an event through `observeEvents()` results in the event surfacing to the user/agent without any corresponding action executing automatically — the agent's subsequent tool call (if any) still requires going through the standard `AccessController`/`ConfirmationGate` path; a minified `release` build variant successfully completes a real `ActionRequest`/`ActionOutcome` round-trip against a live bound extension.
