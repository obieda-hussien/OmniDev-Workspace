# 📦 OmniDev Workspace — Modularization Roadmap

> **Status:** Planning document for the follow-up PR that physically extracts
> `:core:shared`, `:core:ipc`, `:tools:lite`, `:tools:standard`, and
> `:tools:advanced` out of the `:app` module.
>
> **Prerequisite:** the 4-tier flavor system must be merged first (see the
> `genspark_ai_developer` PR that this file ships with).

---

## Why this is a separate PR

The in-sandbox Claude session that introduced the 4-tier flavors cannot run
the Android SDK, so physically relocating ~150 Kotlin files between Gradle
modules cannot be compile-verified here. That work should be done on a
workstation with the Android SDK installed so `./gradlew assembleProDebug`
(and all 3 other variants) pass before merge.

What IS safe to ship today:
- ✅ `flavorDimensions("tier")` + 4 product flavors — `lite` / `norm` / `pro` / `oem`
- ✅ Flavor-specific `AndroidManifest.xml` overlays (lite is Play-Store-clean)
- ✅ `TierPolicy` + `ConfirmationGate` + `OmniAuditLog` policy layer
- ✅ OEM zero-click execution (OemTierPolicy.confirmationGate auto-approves)
- ✅ `PrivilegedExecutionFacade` seam (4 flavor bootstraps)
- ✅ Tier-aware `wipeDeviceData()` guard
- ✅ Unit tests for the policy layer

What is still coupled (and needs this follow-up PR):
- ❌ `ShizukuCommandTool` is on the main classpath for all flavors
- ❌ `VulnResearchToolchain` / `AdvancedSecurityAnalyzer` are on the main classpath
- ❌ The 1,473-line `CompositeToolManager` still knows every tool directly

---

## Target module graph

```
OmniDevWorkspace/
├── settings.gradle.kts      (include :app, :core:*, :tools:*)
├── build.gradle.kts
│
├── core/
│   ├── shared/              ← :core:shared
│   │   src/main/java/com/omnidev/workspace/core/
│   │     ├── policy/         (already here; just move the files)
│   │     ├── privileged/     (already here; just move the files)
│   │     ├── tool/           ← ToolDefinition, ToolParameter, ToolManager,
│   │     │                     ToolContribution (new, see commit 7 scaffolding)
│   │     ├── db/             ← OmniDevDatabase, @Entity classes, DAOs
│   │     ├── model/          ← ChatMessage, AttachmentMeta, OmniMode, AIModel
│   │     ├── engine/         ← AgentPipeline, IntentClassifier, OmniMode,
│   │     │                     AutoHealBuildUseCase
│   │     └── repository/     ← *Repository interfaces (impls stay in :app for now)
│   │
│   └── ipc/                 ← :core:ipc
│       src/main/
│         ├── aidl/            (all IAidl*.aidl files)
│         └── java/com/omnidev/workspace/ipc/
│             ├── OmniCoreService
│             ├── OmniCoreAgentTool
│             ├── ExtensionConnectionManager
│             ├── LauncherConnectionManager
│             └── LauncherCommandRouter
│
├── tools/
│   ├── lite/                ← :tools:lite
│   │   ├── WebSearchTool
│   │   ├── WebScraperTool
│   │   └── LiteReadOnlyFileTool (subset of FileToolManager)
│   │
│   ├── standard/            ← :tools:standard  (depends on :tools:lite)
│   │   ├── SemanticUITool
│   │   ├── OmniAccessibilityService
│   │   ├── UIAutomationTool
│   │   ├── FileToolManager (full) + ConfirmationGate adapter
│   │   ├── GitManagerTool
│   │   ├── LogcatAnalyzerTool
│   │   ├── AppManagerTool / PackageInstallerTool (non-root)
│   │   ├── NotificationCaptureTool / TaskSchedulerTool
│   │   ├── Integration tools (Telegram/Discord/WhatsApp/Slack/SendGrid)
│   │   ├── TermuxEnvironmentBridge / PythonRuntimeManager
│   │   ├── MemoryManager / VectorMemoryManager
│   │   └── SystemAssistantTools subset (no root branches)
│   │
│   └── advanced/            ← :tools:advanced  (depends on :tools:standard + :core:ipc)
│       ├── ShizukuCommandTool                       ← only here
│       ├── RishShellManager                         ← only here
│       ├── PrivilegedExecutionManager (impl)        ← only here
│       ├── AdvancedSecurityAnalyzer
│       ├── VulnResearchToolchain
│       ├── AndroidSecurityResearchTool
│       ├── AndroidVulnResearchEngine
│       ├── AdvancedRootShellTool
│       ├── GodEyeProfilerTool
│       ├── UIReplicaPipelineTool (root-assisted)
│       ├── VPNControlTool (root-assisted)
│       └── SwarmOrchestrator (full multi-agent)
│
└── app/                     ← :app  (owns UI + Application + flavor wiring)
    ├── src/main/              — Compose UI, OmniDevApp, navigation, Settings
    ├── src/lite/java/core/privileged/PrivilegedExecutionFacadeBootstrap.kt
    ├── src/norm/java/core/privileged/PrivilegedExecutionFacadeBootstrap.kt
    ├── src/pro/java/core/privileged/PrivilegedExecutionFacadeBootstrap.kt
    └── src/oem/java/core/privileged/PrivilegedExecutionFacadeBootstrap.kt
```

### Per-flavor dependency matrix

```kotlin
// app/build.gradle.kts (target state)
dependencies {
    implementation(project(":core:shared"))
    implementation(project(":core:ipc"))

    "liteImplementation"(project(":tools:lite"))

    "normImplementation"(project(":tools:lite"))
    "normImplementation"(project(":tools:standard"))

    "proImplementation"(project(":tools:lite"))
    "proImplementation"(project(":tools:standard"))
    "proImplementation"(project(":tools:advanced"))
    "proImplementation"(libs.shizuku.api)
    "proImplementation"(libs.shizuku.provider)

    "oemImplementation"(project(":tools:lite"))
    "oemImplementation"(project(":tools:standard"))
    // OEM does NOT get :tools:advanced — it uses android.uid.system,
    // not Shizuku. The OemPrivilegedExecutionFacadeBootstrap uses
    // Runtime.exec() directly (already implemented).
}
```

---

## Migration checklist

Execute in this order, one atomic commit per step. After each step, `./gradlew
:app:assembleProDebug :app:assembleNormDebug :app:assembleLiteDebug
:app:assembleOemDebug` must stay green.

### Step 1 — `:core:shared`
- [ ] Create `core/shared/build.gradle.kts` (library module, no Compose, no AIDL)
- [ ] Move `com.omnidev.workspace.core.policy.*` (already in place)
- [ ] Move `com.omnidev.workspace.core.privileged.*` (already in place)
- [ ] Move `data/tools/ToolManager.kt`, `ToolDefinition.kt`, `ToolParameter.kt`
- [ ] Move `data/model/*` (ChatMessage, AIModel, AttachmentMeta, OmniMode, …)
- [ ] Move `domain/engine/AgentPipeline.kt` + `IntentClassifier.kt` + `AgentConfig.kt`
- [ ] Move `data/db/*` (Room entities + DAOs) — KSP config copied to module
- [ ] Run `./gradlew :core:shared:assemble` — must compile standalone
- [ ] Update `app/build.gradle.kts`: `implementation(project(":core:shared"))`

### Step 2 — `:core:ipc`
- [ ] Create `core/ipc/build.gradle.kts` with `aidl = true`
- [ ] Move `src/main/aidl/*` from :app to :core:ipc
- [ ] Move `data/ipc/{OmniCoreService, OmniCoreAgentTool, *ConnectionManager, LauncherCommandRouter}.kt`
- [ ] Keep `PrivilegedExecutionManager` + `RishShellManager` in `:app` temporarily
      (moved to `:tools:advanced` in step 4)
- [ ] `implementation(project(":core:ipc"))` in `:app`

### Step 3 — `:tools:lite`
- [ ] Create `tools/lite/build.gradle.kts`
- [ ] Move: `WebSearchTool.kt`, `WebScraperTool.kt`,
           `data/network/OkHttp*.kt` (network plumbing used by them),
           a small read-only `LiteReadOnlyFileTool` (new, derived from FileToolManager).
- [ ] Wire: `"liteImplementation"(project(":tools:lite"))` +
             `"normImplementation"(...)` + `"proImplementation"(...)` + `"oemImplementation"(...)`

### Step 4 — `:tools:advanced` (MOST RISKY — do this last)
- [ ] Create `tools/advanced/build.gradle.kts`
      - `implementation(libs.shizuku.api)` / `libs.shizuku.provider` live HERE.
- [ ] Move: all 19 Shizuku-using files listed in the audit below.
- [ ] Delete Shizuku deps from `:app` (now ONLY in `:tools:advanced`).
- [ ] Wire: `"proImplementation"(project(":tools:advanced"))` — NOT lite/norm/oem.
- [ ] Update callers to route through `PrivilegedExecutionFacadeHolder.current`
      instead of `ShizukuCommandTool` directly.

#### Files that import Shizuku today (must all move to `:tools:advanced`)

```
app/src/main/java/com/omnidev/workspace/MainActivity.kt                          (use facade)
app/src/main/java/com/omnidev/workspace/data/accessibility/SemanticUITool.kt
app/src/main/java/com/omnidev/workspace/data/integration/TelegramPollingService.kt (use facade)
app/src/main/java/com/omnidev/workspace/data/ipc/LauncherCommandRouter.kt
app/src/main/java/com/omnidev/workspace/data/ipc/PrivilegedExecutionManager.kt    ★ move
app/src/main/java/com/omnidev/workspace/data/ipc/RishShellManager.kt              ★ move
app/src/main/java/com/omnidev/workspace/data/tools/AndroidIntentTool.kt
app/src/main/java/com/omnidev/workspace/data/tools/AndroidSecurityResearchTool.kt ★ move
app/src/main/java/com/omnidev/workspace/data/tools/AndroidVulnResearchEngine.kt   ★ move
app/src/main/java/com/omnidev/workspace/data/tools/GodEyeProfilerTool.kt          ★ move
app/src/main/java/com/omnidev/workspace/data/tools/LauncherControlTool.kt
app/src/main/java/com/omnidev/workspace/data/tools/OmniExecutionDiagnostics.kt
app/src/main/java/com/omnidev/workspace/data/tools/OmniNativeToolsManager.kt
app/src/main/java/com/omnidev/workspace/data/tools/ShizukuCommandTool.kt          ★ move
app/src/main/java/com/omnidev/workspace/data/tools/SystemAssistantTools.kt
app/src/main/java/com/omnidev/workspace/data/tools/UIAutomationTool.kt
app/src/main/java/com/omnidev/workspace/data/tools/UIReplicaPipelineTool.kt       ★ move
app/src/main/java/com/omnidev/workspace/data/tools/VisualInspectorTool.kt
app/src/main/java/com/omnidev/workspace/data/tools/VulnResearchToolchain.kt       ★ move
```

`★ move` = the file's primary purpose is privileged — move the whole file to `:tools:advanced`.
Unmarked = the file USES Shizuku as one of several backends. Refactor to go
through `PrivilegedExecutionFacadeHolder.current` so the file itself can stay
in `:tools:standard`.

### Step 5 — Slim `CompositeToolManager`

Replace the 1.4k LOC `when`/`registerAll` with per-module `ToolContribution`
registrars. Each module provides its tools via:

```kotlin
// in :tools:standard
class StandardToolsContribution(...) : ToolContribution {
    override fun definitions(): List<ToolDefinition> = listOf(
        SemanticUITool.getToolDefinitions(),
        GitManagerTool.getToolDefinitions(),
        …
    ).flatten()

    override fun handledNames(): Set<String> = setOf("semantic_ui_action", "git_*", …)

    override suspend fun execute(name: String, args: Map<String, Any?>, scope: String?,
                                 tier: TierPolicy): String { … }
}
```

`CompositeToolManager` becomes a 100-line dispatcher that loops over a
`List<ToolContribution>` injected in `OmniDevApp`.

---

## Testing strategy

- **Unit tests** (`app/src/test`) remain flavor-agnostic. The existing
  `TierPolicyTest` uses stub policies so it runs on every variant.
- **Instrumentation tests** per flavor under
  `app/src/androidTest{Lite,Norm,Pro,Oem}/` for tier-specific behaviour.
- **Manifest merger smoke test**: a CI job that runs
  `./gradlew processLiteDebugMainManifest` and greps the merged manifest
  for any of the removed permissions (should return 0 hits).
- **APK size regression**: track `lite-debug.apk` size in CI — should stay
  under 15 MB (vs. ~60 MB for pro-debug).
