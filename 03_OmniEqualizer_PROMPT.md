# OmniEqualizer — Integration Prompt
### Repo: obieda-hussien/OmniEqualizer · 3 phases, this repo only

> Prerequisite: `01_OmniLinkSDK_PROMPT.md` has shipped a tagged release. This is the smallest of the four satellite apps — treat it as the proof-of-concept.

---

## Phase 1 — Add the JitPack dependency and wire the manifest

1. Add the JitPack repository to `settings.gradle.kts` (bare `maven { url = uri("https://jitpack.io") } }` — `OmniLinkSDK` is public, no credentials needed), then `implementation("com.github.obieda-hussien:OmniLinkSDK:v1.0.0")` (current tag) to `app/build.gradle.kts`.
2. `com.omnilink.sdk.permission.BIND_EXTENSION` merges in automatically from the SDK's own manifest (see `01_OmniLinkSDK_PROMPT.md` Phase 7) — no `<uses-permission>` needed here, since this app is the one being *protected by* that permission, not the one requesting it. In `AndroidManifest.xml`, add the new service entry (Phase 2) with `android:permission="com.omnilink.sdk.permission.BIND_EXTENSION"` and an intent-filter for `OmniLinkConstants.ACTION_EXTENSION_BIND` (`"com.omnilink.sdk.action.EXTENSION_BIND"`).
3. Do **not** touch the existing `EqualizerService` (foreground, `onBind()` returns `null`) — its lifecycle is media-session-driven and shouldn't be mixed with IPC binding lifecycle.

**Acceptance criteria:** project compiles with the new module present; manifest has no functional change to existing components yet.

---

## Phase 2 — Implement `EqualizerLinkService`

1. Create `EqualizerLinkService : ExtensionService` in package `com.omni.equalizer.link`. Set `override val securityValidator = SignatureSecurityValidator(setOf(<shared ecosystem signing certificate's SHA-256 — the canonical value documented in `02_OmniDev-Workspace_PROMPT.md` Phase 8>))` explicitly — **this app must be signed with that same shared keystore, or it will fail to install (`INSTALL_FAILED_DUPLICATE_PERMISSION`) once any other app consuming this SDK is already on the device** — the base class defaults this to `null` (no application-level check at all) unless a subclass sets it, so this line is required, not optional.
2. `manifest()` returns a `CapabilityManifest` (`schemaVersion`/`protocolVersion: 1`) listing capabilities: `getState`, `setBandLevel(bandIndex, level)`, `setPreset(name)`, `setBassBoost(strength)`, `toggleEnabled(bool)`.
3. `onAction()` dispatches each capability to the existing `android.media.audiofx` wrapper classes already controlling the global session-0 engine (Equalizer/BassBoost/Virtualizer/LoudnessEnhancer) — no new audio logic, this is a typed shim over what's already built. These reads/writes are fast in-process calls (no DB/network/disk I/O), so — unlike Note, Memoria, or PriceWatch — it's reasonable for Workspace to call these via the synchronous `executeAction` if it chooses; document this in the manifest/README as an explicit "these actions are fast" note per the SDK's Phase 7 guidance, rather than leaving it implicit.
4. For the settings already identified as genuine Android API stubs (BassBoost frequency/dB parameters), return `ActionOutcome.Failure(ActionError("unsupported_by_platform", "..."))` rather than silently no-op-ing or lying about success.
5. Declare the service in the manifest per Phase 1, with the intent-filter action `com.omnilink.sdk.action.EXTENSION_BIND`.

**Acceptance criteria:** on a real device, binding from a Workspace test harness and calling `executeAction(1, ActionRequest("setPreset", {"name":"Bass"}))` produces an audible change in output; `getState` reflects the true current values, not cached/stale ones; a bind attempt from a differently-signed test caller is rejected at the OS permission level before `onBind()` is ever reached.

---

## Phase 3 — On-device verification

1. Build a minimal debug-only bind harness (an `Activity` or test in `app/src/debug`) that binds to `EqualizerLinkService` directly, calls `getExtensionManifest()`, and prints the JSON.
2. Manually verify from OmniDev-Workspace (once its Phase 3 discovery logic is in place) that it can discover, bind to, and successfully call this service without any hardcoded package name.
3. Confirm the app behaves correctly if the Workspace process dies mid-call (the equalizer app shouldn't crash or leak the binder connection).
4. Build this app's own minified `release` variant and confirm a real `ActionRequest`/`ActionOutcome` round-trip against a live bind still works — this app's own R8 rules for its audio-effect wrapper classes are a separate concern from the SDK's own `consumer-rules.pro`.

**Acceptance criteria:** end-to-end call from a real OmniDev-Workspace build to a real OmniEqualizer build, both installed on the same device, succeeds and audibly changes equalizer output; the minified release build produces the same result.
