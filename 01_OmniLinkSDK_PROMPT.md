# OmniLinkSDK — Integration Prompt
### New standalone repo: obieda-hussien/OmniLinkSDK · 10 phases, all complete

> This is now its own GitHub repo, not a folder copied between projects. Every satellite app (and Workspace itself) consumes it via JitPack + a version tag.

> **Status note (verified against the actual repo, branch `jules-7068250831999581114-a54e1152`, latest commit `9ca0fa2`):** Phases 1–6 below are implemented and match this spec closely — `ActionOutcome` as a sealed `Success`/`Failure`, `CapabilityManifest` with `sdkVersion`/`supportsTicks`/`preferredTickIntervalSeconds`, `AccessController`/`AuditLogger` kept free of any Workspace-specific concept, version negotiation, and the `Flow<OmniEvent>` wrapper over the raw AIDL callback — all present and correct. The actual package ended up as **`com.omnilink.sdk`** and the AIDL interface as **`IExtensionService`** (not the `com.omnidev.link.ipc` / `IOmniExtensionInterface` names originally sketched here) — a reasonable naming choice that matches the repo name, and the one used below and in every consumer file from here on. The implementation also went beyond the original ask in two good ways not originally specified: a `SecurityValidator` interface (with a `SignatureSecurityValidator` computing the SHA-256 of the caller's signing certificate) and a true non-blocking `executeActionAsync` (`oneway` AIDL + callback). **Phases 7, 8, and 9 below are now also implemented and verified** — the manifest permission (`com.omnilink.sdk.permission.BIND_EXTENSION`, `signature`-level) and `OmniLinkConstants` object both exist exactly as specified; `IOmniLauncherInterface.aidl` matches the spec verbatim; `compileSdk` is `36`, AGP is `8.5.1`, Kotlin is `2.0.0`, coroutines `1.8.1` — the safer 8.x-line choice from Phase 9's two options, a reasonable call. Two more unprompted, good additions since: R8/consumer-proguard rules correctly keeping the `@Serializable` classes and AIDL stubs from being stripped or renamed in release builds, and — most relevant if this repo is private — **working JitPack private-repository configuration**, described below.

> **This repo was private and is now public — resolved, verified, and simpler as a result.** JitPack can build private GitHub repos, but that specific feature requires a paid JitPack subscription (confirmed via jitpack.io's own pricing: starts at $9/month for 3 private repos) — a real, non-obvious cost that only surfaced once a tag was actually pushed and the build attempted. Since this repo is pure protocol/infrastructure code with no business logic or secrets in it, making it public was the simpler, free, correct call — confirmed by cloning it with zero credentials and observing `v1.0.0` built successfully on JitPack. Phase 10 below documents this and simplifies the README accordingly; every consumer file (02–07) now uses the plain, credential-free JitPack setup.

---

## Phase 1 — Repo scaffolding

1. New repo, Android library module only (`com.android.library` plugin), Kotlin, min SDK matching the lowest of the five consumer apps, zero app module, zero UI dependency.
2. Add `kotlinx-serialization` plugin + dependency — this replaces hand-written JSON across every consumer.
3. Add the `maven-publish` plugin to the library module's `build.gradle.kts` with a publication block producing a valid AAR + POM — this is the only requirement for JitPack to be able to build it from a tag with no extra server-side config.

**Acceptance criteria:** `./gradlew :omni-link-sdk:assembleRelease` and `./gradlew :omni-link-sdk:publishToMavenLocal` both succeed locally with no other module present.

---

## Phase 2 — Typed protocol (replaces raw JSON strings)

1. Define `@Serializable` types. Model the response as a **sealed hierarchy**, not a boolean-plus-nullable-fields struct — the older pattern allows invalid states like `success = true` with a populated `error`, which a `when` block can't catch at compile time and a sealed type can:
   - `ActionRequest(name: String, payload: JsonElement)`
   - `sealed interface ActionOutcome { @Serializable data class Success(val data: JsonElement) : ActionOutcome; @Serializable data class Failure(val error: ActionError) : ActionOutcome }` — every caller handles this with an exhaustive `when`, so a new outcome variant added later becomes a compile error at every call site until handled, rather than a silently-ignored case.
   - `ActionError(code: String, message: String)`
   - `CapabilityDescriptor(name: String, destructive: Boolean = false, requiresConfirmation: Boolean = false)` — deliberately minimal. Do **not** add `cost`, `estimatedTime`, `streamingSupported`, or `offline` fields yet; note in a code comment that these are v2-candidate fields to add only when a real capability needs one, not speculatively.
   - `CapabilityManifest(protocolVersion: Int, sdkVersion: String, minSupportedVersion: Int, maxSupportedVersion: Int, capabilities: List<CapabilityDescriptor>, supportsTicks: Boolean = false, preferredTickIntervalSeconds: Int = 900)` — `protocolVersion` is the wire-compatibility contract (changes rarely, gates negotiation); `sdkVersion` (e.g. `"1.4.3"`) is the actual build/changelog version, so a bug/perf/security fix that doesn't touch the wire format still shows up somewhere traceable; `supportsTicks` declares whether this extension wants periodic wake-ups from Workspace's heartbeat (see `02_OmniDev-Workspace_PROMPT.md` Phase 6) — most extensions leave this `false`; `preferredTickIntervalSeconds` defaults to `900` (15 minutes) — see Phase 6 for why sub-15-minute cadence is the exception, not the default, on current Android versions.
   - `CallerContext(callingUid: Int, callingPackage: String)` — resolved once per bind, passed into every policy decision so implementations never call `Binder.getCallingUid()` themselves.
2. All serialization/deserialization lives in this module — extension authors in satellite apps write plain Kotlin functions with typed inputs/outputs; they never hand-construct JSON strings themselves.

**Acceptance criteria:** a round-trip test (serialize `ActionRequest` → AIDL string → deserialize) passes for at least one non-trivial payload (nested object, list).

---

## Phase 3 — `ExtensionService` base class with version negotiation

1. Add abstract `ExtensionService : Service()` (deliberately not prefixed "Omni" or "Link" — this is pure infrastructure, reusable outside this ecosystem entirely if needed). `onBind()` returns the interface's `Stub`. The stub's `executeAction`:
   - Deserializes the incoming `ActionRequest`.
   - Resolves `CallerContext` via `Binder.getCallingUid()` + package lookup.
   - Checks the caller-declared `protocolVersion` against this service's own `minSupportedVersion..maxSupportedVersion` range; if incompatible, returns `ActionError("version_mismatch", "...")` immediately rather than attempting the call.
   - Runs the request through an `AccessController` interface (`fun decide(caller: CallerContext, request: ActionRequest): AccessDecision` — allow/deny/requiresConfirmation). **This interface knows nothing about TierPolicy, ConfirmationGate, or any other Workspace-specific concept — those live in a Workspace-side adapter implementing this interface, not in the SDK.**
   - Dispatches to `abstract fun onAction(caller: CallerContext, request: ActionRequest): ActionOutcome` on a background coroutine dispatcher, never the binder thread.
   - Logs every attempt (denied or not) through an `AuditLogger` interface (`fun log(caller: CallerContext, request: ActionRequest, result: ActionOutcome)`) — again, purely generic; Workspace supplies an adapter forwarding into `OmniAuditLog`, satellite apps get a no-op default.
   - Catches all exceptions, converts to `ActionError` — a crash in one extension's `onAction` must never propagate as a crash to the caller.
2. **Design check for this whole phase:** grep the module for any import of `com.omnidev.workspace.*` or any Workspace-specific class name. There should be none. If the SDK ever needs to know what a "flavor" or a "tier" is, that's a sign the abstraction leaked — push it back into the consumer's adapter instead.
3. **Reserve the `_` action-name prefix for protocol-level system calls** (e.g. the heartbeat tick, `_tick`, added in Workspace's Phase 6). Document in `LINK_PROTOCOL.md` that satellite apps must never define their own action starting with `_` — this keeps a clean namespace between "the protocol talking to itself" and "the agent calling app-specific capabilities," with no new AIDL method required to add future system-level calls.

**Acceptance criteria:** a throwaway test service extending this class, with `minSupportedVersion=1, maxSupportedVersion=1`, correctly rejects a simulated caller declaring `protocolVersion=2` with a `version_mismatch` error.

---

## Phase 4 — Event bus (v2, optional, backward-compatible)

1. Add `IOmniEventCallback.aidl`: single method `onEvent(eventJson: String)` — this is the necessary AIDL primitive, but it's not what consumers should have to write against directly.
2. Add `registerEventListener(callback)` / `unregisterEventListener(callback)` to `IExtensionService` as new methods — bump `protocolVersion` to 2 for any service that implements them, but the base class must default to "not supported" so a v1 extension that hasn't opted in doesn't break.
3. On top of the raw AIDL callback, expose a `fun observeEvents(): Flow<OmniEvent>` extension function (built with `callbackFlow { ... awaitClose { unregisterEventListener(...) } }`) so consuming code writes idiomatic `flow.collect { ... }` inside a coroutine scope instead of implementing a manual callback interface and manually managing register/unregister lifecycle. The raw AIDL callback stays internal plumbing; `Flow<OmniEvent>` is the public surface.
4. This phase produces the interface and base-class support only. **No satellite app is required to implement event publishing in its first pass** — this ships as available-but-unused until a specific app's prompt explicitly adds it later.

**Acceptance criteria:** a v1 test extension (not implementing events) continues to bind and execute actions correctly against a Workspace client that supports v2 negotiation; a v2 test extension can register a listener and receive a published test event; a consumer using `observeEvents().collect { }` receives events without ever touching the raw `IOmniEventCallback` interface directly, and cancelling the collecting coroutine cleanly unregisters the listener via `awaitClose`.

---

## Phase 5 — CI

1. GitHub Actions workflow: on every push/PR, run `./gradlew build test`.
2. On tag push matching `v*.*.*`, run a publish-verification job (`./gradlew publishToMavenLocal` — JitPack does the real build remotely from the tag itself, so this step is just a local sanity check, not the actual publish mechanism).

**Acceptance criteria:** workflow passes on the initial commit; pushing a `v1.0.0` tag triggers the verification job successfully.

---

## Phase 6 — Consumption instructions (for the other five prompts)

Document in this repo's `README.md`:
```kotlin
// settings.gradle.kts (consumer)
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts (consumer)
dependencies {
    implementation("com.github.obieda-hussien:OmniLinkSDK:v1.0.0")
}
```
Bumping the SDK means: make the change here, tag a new version, then bump the version string in each of the four consumer `build.gradle.kts` files — a visible, reviewable diff instead of a silent copy-paste that's easy to forget.

**Migration note — add this to the README explicitly:** JitPack is the current, low-friction way to consume this repo, not the permanent architecture. It builds on demand from GitHub at resolution time, which means occasional slow or failed builds and a dependency on JitPack's own infrastructure staying healthy — acceptable for now, not ideal for something this central once the ecosystem is load-bearing. The concrete long-term target is **GitHub Packages** (not Maven Central — Maven Central's release ceremony, Sonatype account, and GPG signing exist for public library distribution, which this isn't): GitHub Packages publishes directly from the same Actions workflow already built in Phase 5, with no third-party build-on-demand step in between. Migrate once the ecosystem stabilizes; don't block current progress on it.

**Acceptance criteria:** a throwaway test project outside this repo can resolve and use the published artifact by tag alone, with no local Maven publish step on the consumer side.

---

## Phase 7 — Close the manifest-permission gap (blocking for every consumer's security model)

**This is the most important remaining phase.** Every downstream prompt (`02` through `07`) is written assuming a `signature`-level Android permission actually protects each extension service's `bindService()` call at the OS level, enforced before any Kotlin code runs. Today, `omni-link-sdk/src/main/AndroidManifest.xml` is empty — no such permission exists anywhere. The `SecurityValidator`/`SignatureSecurityValidator` built in the "advanced security" pass is a good defense-in-depth layer, but it's app-level (runs after `onBind()` has already handed a live Binder to the caller) and defaults to `null` — i.e., no protection at all — unless a subclass explicitly sets it. An OS-enforced permission and an opt-in application check are not substitutes for each other; this ecosystem needs both.

**Confirmed platform constraint that makes this non-optional, not just a security nice-to-have:** declaring `<permission android:protectionLevel="signature">` in this library means every consumer app's manifest merges in an *identical* permission declaration (same name, same protection level) — Android's own documented behavior is that **the system refuses to install a second app declaring a permission name already declared by a currently-installed app, unless both are signed with the same certificate** (`INSTALL_FAILED_DUPLICATE_PERMISSION` otherwise). Concretely: once `OmniLinkSDK` is a dependency of Workspace *and* Equalizer *and* Note *and* Memoria *and* PriceWatch, all five **must be signed with the same keystore**, or only the first one installed will succeed — every subsequent one will fail to install outright, not just fail some runtime check. This has been true since Android 5.0 (this ecosystem's own `minSdk`), so it applies across the whole supported range. Document this prominently — in this repo's own README and cross-referenced from every consumer prompt — since it's a build-breaking omission if missed, not a theoretical edge case.

*(Documented alternative, not adopted here: Android 12+'s `knownCerts`/`knownSigner` mechanism lets a signature permission grant to any of several declared certificates, avoiding the shared-keystore requirement — useful if independently-signed contributors ever join this ecosystem. For a single-developer suite of apps, one shared keystore is simpler and is itself Android's own standard recommendation for an app suite from one publisher; don't add the `knownSigner` complexity unless that constraint actually changes.)*

1. Declare the permission directly in `omni-link-sdk/src/main/AndroidManifest.xml`:
   ```xml
   <permission
       android:name="com.omnilink.sdk.permission.BIND_EXTENSION"
       android:protectionLevel="signature" />
   ```
   Because this lives in the SDK's own manifest, Android's manifest merger automatically folds it into every consuming app's final manifest — no consumer needs to redeclare this XML themselves, which is actually a cleaner outcome than the original per-app-copy plan.
2. Add a small `OmniLinkConstants` object to the SDK's Kotlin source so consumers reference constants instead of hand-typing raw strings across five separate repos:
   ```kotlin
   object OmniLinkConstants {
       const val PERMISSION_BIND_EXTENSION = "com.omnilink.sdk.permission.BIND_EXTENSION"
       const val ACTION_EXTENSION_BIND = "com.omnilink.sdk.action.EXTENSION_BIND"
   }
   ```
3. Update `LINK_PROTOCOL.md` to document: (a) this permission and action string, (b) that `SecurityValidator` is a required second layer, not optional — every consumer's `ExtensionService` subclass should set a real `SignatureSecurityValidator`, not leave the default `null`, (c) the sync-vs-async execution rule below, and (d) **the shared-keystore requirement in bold, at the top of the document** — every app consuming this SDK must be signed with the same certificate, or the second one installed will fail outright.
4. **Document the `executeAction` vs. `executeActionAsync` split explicitly, because the current sync path has a real footgun.** `executeAction` is a normal (non-`oneway`) AIDL method; internally, `ExtensionService.executeInternal()` calls `onAction` via `runBlocking(Dispatchers.Default) { ... }` — this occupies one of the callee process's own (finite) binder-thread-pool threads for the entire duration of `onAction`, and blocks the caller's calling thread until it returns, exactly like any synchronous IPC call. That's fine for a guaranteed-fast, non-blocking read (e.g. "give me your manifest"), but genuinely risky for anything doing DB, network, or file I/O — repeated slow synchronous calls can exhaust the callee's binder thread pool, and a caller invoking this from a thread it can't afford to block risks its own ANR. `executeActionAsync` (already correctly `oneway`, with `IOmniResultCallback`) has neither problem. Document in `LINK_PROTOCOL.md`, in bold, that **`executeAction` is reserved for actions a consumer explicitly documents as fast/non-blocking in its own manifest; anything else must be called via `executeActionAsync`** — and that this is a calling-convention rule for every consumer (Workspace's `ExtensionConnectionManager`, and every satellite app's own `onAction` implementation) to follow, not something the base class can fully enforce on its own.
5. **Document how `requiresConfirmation` actually resolves end to end — this was left ambiguous and needs one canonical answer.** No satellite app has any way to show Workspace's UI, so `AccessDecision.REQUIRES_CONFIRMATION` returned from a remote extension's own `onAction()` call is a dead end on its own — there's no field in `ActionRequest` for "the user already confirmed, proceed," and there shouldn't be one added just to patch this, since the real fix is architectural, not a new wire field:
   - **Primary mechanism, client-side:** the caller (Workspace) checks `CapabilityDescriptor.requiresConfirmation` from the *cached manifest* **before** calling `executeActionAsync` at all. If `true`, the caller shows its own confirmation UI first and only makes the call if the user agrees. The manifest field exists specifically for this — a caller never needs to dial out just to find out a call needs confirmation.
   - **Server-side `REQUIRES_CONFIRMATION` is a fallback, not a retry protocol.** If a remote extension's own `AccessController` returns `REQUIRES_CONFIRMATION` for a call the caller's cached manifest *hadn't* flagged (stale cache, or the extension being more conservative at runtime than its static manifest declared), the correct caller behavior is to treat the resulting `ActionOutcome.Failure(ActionError("requires_confirmation", ...))` as a hard abort — surface it to the agent/user as "this needs confirmation, try again" — never to silently retry assuming some implicit confirmation. There is deliberately no automatic retry-with-confirmation loop in this protocol.
   Add this as its own section in `LINK_PROTOCOL.md` so every consumer prompt can point to one canonical explanation instead of each re-describing it slightly differently.

**Acceptance criteria:** a test APK signed with a different key than the one declared in a test `SignatureSecurityValidator` allowlist fails to even successfully `bindService()` against a test extension (rejected at the OS permission level, never reaching `onBind()`), independent of and prior to whatever the `SecurityValidator` layer would separately have decided; `LINK_PROTOCOL.md` states the sync/async rule, the confirmation-flow resolution, and the shared-keystore requirement in a way a future contributor can't miss; two throwaway test apps both consuming this SDK, signed with different debug keystores, reproduce the actual `INSTALL_FAILED_DUPLICATE_PERMISSION` failure on a real device or emulator, confirming the constraint is real and not just theoretical.

---

## Phase 8 — Define `IOmniLauncherInterface` (referenced by the Launcher prompt, never actually created)

`06_Omni-launcher_PROMPT.md` says this interface "now lives in the OmniLinkSDK repo as the single source of truth" — but no phase in this file ever specified it, and it doesn't exist in the repo. Close that gap here. Launcher deliberately does **not** take this SDK as a Gradle dependency (its build is too large and fragile to add a new dependency casually — see its own prompt), so this phase produces only the interface **text** for it to copy, not a full base-class implementation.

1. Add `IOmniLauncherInterface.aidl` under `com.omnilink.sdk` (or a dedicated sub-package, e.g. `com.omnilink.sdk.launcher`, if keeping the core extension surface and the launcher-specific surface visually separate is preferred):
   ```aidl
   package com.omnilink.sdk;

   interface IOmniLauncherInterface {
       boolean performLauncherAction(String actionName);
       boolean renderOmniWidget(String widgetId, String composeJson);
       boolean removeOmniWidget(String widgetId);
       void clearAllOmniWidgets();
       boolean openWidgetPicker();
   }
   ```
2. This phase does not need a corresponding `Service` base class here — Launcher's own repo implements the `Stub` directly against its Lawnchair/Launcher3 internals, per its own prompt's Phase 1–2.
3. Add a short section to `LINK_PROTOCOL.md` noting this interface's existence and pointing to the Launcher repo as where it's actually implemented, so a reader of this repo alone isn't left wondering where its implementation lives.

**Acceptance criteria:** the `.aidl` file compiles standalone in this module (even with no consumer yet); its exact text is what `06_Omni-launcher_PROMPT.md` Phase 1 copies.

---

## Phase 9 — Dependency and target-SDK modernization

A few of the pinned versions were current when first scaffolded but have since moved, and one of them is tied to an actual external deadline, not just general freshness:

1. **`compileSdk`/`targetSdk`: bump from `34` to `36` (Android 16).** This isn't cosmetic — Google Play requires new apps and app updates to target API 36 by **August 31, 2026** (with a possible extension to November 1, 2026), and this deadline applies to every APK in this ecosystem, not just this library. Apply the same bump in every consumer repo's own `build.gradle.kts` (Workspace, Equalizer, Note, Memoria, PriceWatch, and Launcher's fork where feasible given its size).
2. **Kotlin: bump from `1.9.22` to the current stable 2.x release.** Verify the exact latest tag on kotlinlang.org at implementation time rather than trusting a hardcoded number here — Kotlin ships frequently enough that any specific patch version written today will likely be stale by the time this phase is actually implemented.
3. **`kotlinx-coroutines-core`/`-android`: bump from `1.7.3`.** Same verify-at-implementation-time approach — check the current release on Maven Central rather than assuming a fixed number.
4. **`kotlinx-serialization-json`: bump from `1.6.3`** similarly.
5. **AGP: this one needs a judgment call, not a blind bump.** AGP has moved to a 9.x line that includes a genuinely breaking change — built-in Kotlin support replacing the separate `kotlin-android` plugin — which is a real migration, not a drop-in version bump. Two honest options: (a) stay on a current **8.x** release (safer, no migration, still gets you a meaningful jump from 8.2.2) or (b) move to current **9.x** and do the built-in-Kotlin migration properly. Pick (a) if the priority is finishing the ecosystem's remaining phases without a build-tooling detour; pick (b) if this SDK is expected to be long-lived and the migration cost is worth paying once, early. Either way, don't silently stay on 8.2.2 without at least considering this — it's now a meaningfully old pin.
6. **Optional refinement, not required:** consider promoting the `requires_confirmation` case from an `ActionError` code string (`ActionOutcome.Failure(ActionError("requires_confirmation", ...))`) to its own `ActionOutcome.RequiresConfirmation` sealed variant. The whole point of making `ActionOutcome` sealed in Phase 2 was exhaustive compile-time handling instead of string-comparing error codes — collapsing this specific, structurally-distinct case back into a generic failure-with-a-magic-string partially undoes that benefit. This is a nice-to-have consistency fix, not a bug; don't treat it as blocking.

**Acceptance criteria:** every consumer repo's `compileSdk`/`targetSdk` reads `36`; the build succeeds with whichever AGP path was chosen, verified by actually running `./gradlew build` (this sandbox's own review could only read source, not compile it — actual verification is the implementing agent's job, since it has real network access to Google's and Maven Central's repositories).

---

## Phase 10 — Superseded: the repo is now public, simplify the README accordingly

**This phase originally addressed tightening the private-repo access token — moot now, since `OmniLinkSDK` was made public and JitPack has already built `v1.0.0` from it successfully (verified: the repo clones with no credentials at all, and the `jules-...` branch is merged into `main`).** Public was the right call here specifically because this repo is pure protocol/infrastructure code — AIDL interfaces, typed request/response wrappers, a base class — with no business logic, user data, or proprietary algorithm in it. That's a different situation from the other five repos in this ecosystem (Note, Memoria, Equalizer, Launcher, Workspace itself), which do have real reasons to stay private; this call shouldn't be read as "everything in this ecosystem should be public."

1. Simplify the README's consumption section back to the plain, credential-free form: bare `maven { url = uri("https://jitpack.io") } }` plus `implementation("com.github.obieda-hussien:OmniLinkSDK:v1.0.0")` — remove the "Consumption instructions (Private Repository)" section (GitHub PAT generation, JitPack authorization steps, `local.properties`/`authToken` setup) entirely, since none of it applies anymore.
2. Leave the JitPack-is-transitional migration note (toward GitHub Packages) as-is — that reasoning (occasional slow/failed on-demand builds, dependency on JitPack's own infrastructure) still applies regardless of public/private status; it just no longer needs a paid-subscription angle added to it, since public JitPack usage is free.

**Acceptance criteria:** the README shows only the plain, no-credentials consumption block; a fresh clone of a throwaway consumer project can resolve `com.github.obieda-hussien:OmniLinkSDK:v1.0.0` from JitPack with zero token configuration, matching what's already been verified to work.
