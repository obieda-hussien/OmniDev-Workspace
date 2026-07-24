# Omni Launcher — Integration Prompt
### Repo: obieda-hussien/Omni-launcher (Lawnchair 15 fork) · 5 phases, this repo only

> This is the largest and riskiest repo — a full AOSP-adjacent Launcher3/Lawnchair fork (quickstep, systemUI, wmshell modules), not a small custom app. The `IOmniLauncherInterface` AIDL is defined in the `OmniLinkSDK` repo (`01_OmniLinkSDK_PROMPT.md` Phase 8) as the single source of truth for its text, but this app deliberately does **not** take a JitPack dependency on the whole SDK — pulling a new Gradle dependency into a build this large and fragile is its own risk. Instead, copy just the one `.aidl` file's contents directly into the new package below. This app also uses its own, self-owned permission namespace (`com.omnidev.launcher.*`) rather than the SDK's `com.omnilink.sdk.*` namespace, since it doesn't depend on the SDK as a library and shouldn't imply that it does. **Prioritize the smallest possible diff at every phase** — do not refactor existing Launcher3/Lawnchair internals to "fit" this integration; wrap them instead.

---

## Phase 1 — Add the IPC package with zero changes to existing code

1. Add a new, isolated package `com.omnidev.launcher.ipc` containing only: a copy of the `IOmniLauncherInterface.aidl` contents from the `OmniLinkSDK` repo's Phase 8 (plain file copy, not a Gradle dependency) and a new bound `Service` class implementing its `Stub`.
2. Do not touch any existing Launcher3/Lawnchair/quickstep/systemUI file in this phase — this is purely additive.
3. Define `com.omnidev.launcher.permission.BIND_LAUNCHER` (a separate permission from the SDK's `BIND_EXTENSION`, both in namespace and in mechanism, since this app doesn't depend on the SDK library at all) as `signature`-level, declared directly in this app's own manifest. **This still requires this app to be signed with the same certificate as Workspace** — a `signature`-level permission only grants to a caller signed with the same key as the app that declared it, so Workspace's `bindService()` call against this permission will fail unless both apps share the ecosystem's one signing keystore (established in `02_OmniDev-Workspace_PROMPT.md` Phase 8). Unlike the SDK's merged `BIND_EXTENSION` permission, this one isn't declared by multiple independently-installed apps, so there's no `INSTALL_FAILED_DUPLICATE_PERMISSION` risk here specifically — but the shared-keystore requirement applies all the same, just via the ordinary signature-check mechanism rather than an install-time conflict.

**Acceptance criteria:** the new service class compiles and the app builds unchanged in every other respect; `git diff` touches only the new package and the manifest.

---

## Phase 2 — Wire read/navigation actions (lowest risk)

1. `goToHomeScreen()` → call into the existing home-intent handling already present in `QuickstepLauncher`.
2. `openAppDrawer()` / `closeAppDrawer()` → call the existing `AllAppsContainerView` show/hide methods already used by the drawer's own gesture/click handling.
3. `launchPackage(packageName)` → reuse the exact launch-intent dispatch path the drawer's click handler already uses — don't write a new launch mechanism.
4. `performLauncherAction(actionName)` → a small dispatch table mapping known action-name strings to the above, returning `false` for anything unrecognized rather than throwing.
5. `openWidgetPicker()` → trigger the existing system widget-picker intent the launcher already supports.

**Acceptance criteria:** each method, called from a debug test harness, produces the correct visible on-device behavior; no regression in normal manual use of the launcher (drawer, home, app launch, widget picker all still work exactly as before when not driven via IPC).

---

## Phase 3 — Widget rendering surface (new functionality, keep it minimal)

1. Build a lightweight overlay `ComposeView` host that can be added to/removed from the workspace, driven by a small custom JSON schema — support **only** text, image, and button primitives in the first version. Do not attempt to support arbitrary Compose trees from JSON.
2. `renderOmniWidget(widgetId, composeJson)` parses the JSON against that minimal schema and renders/updates the corresponding overlay view.
3. `removeOmniWidget(widgetId)` and `clearAllOmniWidgets()` tear down the corresponding overlay view(s).
4. Malformed or unsupported JSON returns `false` from the AIDL call rather than crashing the launcher process — a launcher crash is much worse than a failed IPC call.

**Acceptance criteria:** a widget with a text label, an image, and a button renders correctly on the home screen from an external `renderOmniWidget` call, updates in place on a second call with the same `widgetId`, and disappears cleanly on `removeOmniWidget`.

---

## Phase 4 — Manifest & permission wiring

1. Declare the new service in the manifest with `android:permission="com.omnidev.launcher.permission.BIND_LAUNCHER"` and an intent-filter action `com.omnidev.launcher.action.LAUNCHER_BIND` (a distinct permission and namespace from the SDK's `BIND_EXTENSION`/`EXTENSION_BIND` used by the other three apps, since Workspace's `LauncherConnectionManager` already expects a dedicated binding path and this app declares everything itself rather than inheriting it from a manifest-merged dependency). **This app must be signed with the same shared ecosystem keystore documented in `02_OmniDev-Workspace_PROMPT.md` Phase 8** — a `signature`-level permission only grants access to a caller signed with the same certificate as the app that declared it, so Workspace (the caller) and this app (the declarer) need to match, independent of and in addition to the separate shared-keystore requirement the other four apps have via the SDK's own merged permission.
2. Confirm the permission is `signature`-level and rejects a differently-signed test caller.

**Acceptance criteria:** a mismatched-signature test APK attempting to bind is rejected; a same-signature test caller binds successfully.

---

## Phase 5 — On-device verification

1. From a real OmniDev-Workspace build, exercise every method: home, drawer open/close, launch a known package, open widget picker, render/update/remove a widget.
2. Kill the launcher process manually (`adb shell am force-stop`) mid-session and confirm Workspace's reconnect-with-backoff logic (built in the Workspace repo's own Phase 3) successfully rebinds once the launcher restarts.
3. Confirm normal manual use of the launcher (no IPC involved at all) is completely unaffected — this is the most important regression check given how much existing behavior this fork carries.

**Acceptance criteria:** full method coverage verified on-device; zero regression in manual launcher use; reconnect after process death works without a Workspace app restart.

---

**Cross-cutting note:** like every other app in this ecosystem, this fork's `compileSdk`/`targetSdk` should move toward API 36 (Android 16) given Google Play's August 31, 2026 target-API deadline (see `01_OmniLinkSDK_PROMPT.md` Phase 9) — but treat this as lower priority and higher risk here specifically, since a Lawnchair/Launcher3 fork this large may have upstream dependencies or AOSP-adjacent code that don't move cleanly to a new target SDK on the same timeline as the smaller apps. Don't let this deadline pressure a rushed change to the highest-risk repo in the set; the other five apps are the ones to prioritize for that specific bump.
