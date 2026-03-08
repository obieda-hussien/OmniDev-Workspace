# OmniDev Workspace — Master System Blueprint

[![Android CI](https://github.com/obieda-hussien/DevSwarm/actions/workflows/android-ci.yml/badge.svg)](https://github.com/obieda-hussien/DevSwarm/actions/workflows/android-ci.yml)

> **AI AGENT CONTEXT MAP** — This document is the authoritative reference for all future LLM agents, Copilot sessions, and developers. Read it first. It maps every file, flow, pattern, and decision in the repository so you can operate with full context in a single pass — saving thousands of context window tokens.

**OmniDev / DevSwarm** is a **God-Mode Autonomous AI Software Engineer** for Android. It orchestrates cloud and on-device LLMs into a multi-agent swarm that can write code, run terminals, control the OS, manage files, speak and listen, browse the web, and self-heal build failures — fully autonomously.

> Package: `com.omnidev.workspace` · Min SDK: 24 · Target SDK: 35 · Language: Kotlin 2.0 · UI: Jetpack Compose + Material 3

---

## Table of Contents

1. [System Overview & Four Core Pillars](#1-system-overview--four-core-pillars)
2. [Core Execution Flows — The Brain](#2-core-execution-flows--the-brain)
3. [LLM Routing — ModelRegistry](#3-llm-routing--modelregistry)
4. [Tool Directory — The Hands](#4-tool-directory--the-hands)
5. [Multi-Modal & Senses — Eyes & Ears](#5-multi-modal--senses--eyes--ears)
6. [Persistence Layer](#6-persistence-layer)
7. [UI Layer](#7-ui-layer)
8. [System Services & Receivers](#8-system-services--receivers)
9. [Project Directory Tree — File Map](#9-project-directory-tree--file-map)
10. [Tech Stack](#10-tech-stack)
11. [Getting Started](#11-getting-started)
12. [CI/CD Pipeline](#12-cicd-pipeline)
13. [Android Permissions Reference](#13-android-permissions-reference)
14. [Architecture Conventions & Agent Rules](#14-architecture-conventions--agent-rules)

---

## 1. System Overview & Four Core Pillars

```
┌─────────────────────────────────────────────────────────────────────┐
│                     OmniDev Workspace                               │
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌────────┐ │
│  │   Shizuku    │  │  Llama.cpp   │  │    Swarm     │  │ Duplex │ │
│  │  OS-Level    │  │  Local Edge  │  │ Multi-Agent  │  │ Voice  │ │
│  │  Execution   │  │  Inference   │  │ Orchestration│  │   UI   │ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └───┬────┘ │
│         │                 │                 │               │      │
│         └─────────────────┴─────────────────┴───────────────┘      │
│                               │                                     │
│                    ┌──────────▼──────────┐                         │
│                    │  AgentPipeline      │                         │
│                    │  (ReAct Loop)       │                         │
│                    │  CompletionService  │                         │
│                    │  CompositeToolMgr   │                         │
│                    └─────────────────────┘                         │
└─────────────────────────────────────────────────────────────────────┘
```

### Pillar 1 — Shizuku (OS-Level Execution)
`ShizukuCommandTool` + `AdvancedSystemTools` execute privileged Android shell commands via the Shizuku binder bridge without requiring a fully rooted device. This enables: full terminal access (`adb shell`-equivalent), package install/uninstall, system-settings writes, `screencap`/`screenrecord`, and arbitrary root shell commands when Shizuku has ADB or root privileges. Falls back to `/data/local/tmp/` execution when Shizuku is unavailable.

### Pillar 2 — Llama.cpp (Local Edge GGUF Inference)
`LlamaCppInferenceEngine` wraps the llama.cpp C++ library via JNI (`llama_jni.cpp`). It loads any quantized `.gguf` model from a SAF `content://` URI — bytes are copied to `context.cacheDir/llm_active_model.gguf` to bypass `/proc/self/fd` symlink failures on certain GGUF variants. Token streaming delivers decoded pieces to Compose in real-time. The engine is plugged into `CompletionService` as a first-class provider alongside all cloud APIs.

**Current llama.cpp version:** `b8233` (fetched via CMake `FetchContent` from `ggml-org/llama.cpp`). Key API calls: `llama_init_from_model`, `llama_memory_clear(llama_get_memory(ctx), true)`, `llama_vocab_is_eog`.

### Pillar 3 — Swarm Architecture (Multi-Agent Orchestration)
`SwarmOrchestrator` implements a **Plan → Delegate → Synthesize** loop:
- **Plan**: The Orchestrator model decomposes the user's goal into a JSON task graph with `id`, `description`, `priority`, `dependencies[]`, and `requiredPersona`.
- **Delegate**: Tasks execute in topological waves — all tasks whose dependencies are satisfied run **concurrently** via `coroutineScope { async { } }.awaitAll()`.
- **Synthesize**: All worker results are assembled into a final answer by the Orchestrator model.

Each Worker is a full `AgentPipeline` instance with its own `MemoryManager` access and real-time SSE streaming.

### Pillar 4 — Duplex Voice UI
`VoiceAssistantService` is the **single source of truth** for all STT and TTS. It runs as an always-on foreground service listening for the wake-word. `VoiceManager` is a pure facade — it has zero internal `SpeechRecognizer` or `TextToSpeech` instances; every call delegates to `VoiceAssistantService` static API. This prevents `ERROR_RECOGNIZER_BUSY` micro-collisions.

---

## 2. Core Execution Flows — The Brain

### 2.1 AgentPipeline — ReAct Loop

**File:** `domain/engine/AgentPipeline.kt`

```
User Prompt
     │
     ▼
┌─────────────────────────────────────────────────────────────────┐
│  ITERATION N (max 25 by default, 50 for THOROUGH preset)        │
│                                                                 │
│  1. REASON  ──► Send [system + history + tools] to LLM         │
│               ◄── Response: text OR tool_calls[]               │
│                                                                 │
│  2. ACT     ──► If tool_calls[] → execute via CompositeToolMgr │
│               ◄── ToolCallResult (success + output string)      │
│                                                                 │
│  3. OBSERVE ──► Append ASSISTANT(toolCalls) + TOOL(results)    │
│               ──► Loop back to step 1                          │
│                                                                 │
│  4. TERMINAL ─► No tool_calls → emit final text answer         │
└─────────────────────────────────────────────────────────────────┘
```

**Key constants & behaviors:**
- `INTER_CALL_DELAY_MS = 500L` — 500 ms pause between iterations (except first) to prevent rate-limit bursting on free-tier providers.
- `RATE_LIMIT_MAX_RETRIES = 4` with 15 s–60 s progressive delays for HTTP 429 responses.
- `AgentConfig.BUDGET` / `.THOROUGH` / `.INLINE` — three behavioral presets.
- **Context Window Trimming** (`trimMessagesForContextWindow`): evicts old messages when the running token estimate exceeds `contextWindowBuffer`. CRITICAL: ASSISTANT messages with `toolCalls` are always evicted **together with** all immediately-following TOOL messages — never leave orphaned tool results (causes `400 Bad Request` from OpenAI/Anthropic).
- **Memory Injection**: `MemoryManager.getRelevantMemories(query)` is called before each iteration; facts are prepended to the system prompt.
- **`<think>` / `<thinking>` tag stripping**: the streaming response strips `<(?:thinking|think)>(.*?)</(?:thinking|think)>` from clean text, surfacing it as a collapsible "🧠 Thought Process" card in the UI via `MessageFormatter.THINKING_RE`.

**AgentEvent sealed class** (emitted on the `Flow<AgentEvent>`):
| Event | Meaning |
|-------|---------|
| `Thinking(text)` | Model reasoning / chain-of-thought |
| `ToolCall(name, args)` | Tool about to be executed |
| `ToolResult(name, result)` | Tool execution outcome |
| `FinalAnswer(text)` | Agent loop complete |
| `Error(message)` | Unrecoverable failure |
| `StreamChunk(text)` | SSE streaming delta |

### 2.2 SwarmOrchestrator — Plan/Delegate/Synthesize

**File:** `domain/engine/SwarmOrchestrator.kt`

```
User Prompt
     │
     ▼
 Orchestrator LLM (ORCHESTRATOR tier model)
     │  ──► JSON task graph  [{"id","desc","priority","dependencies","requiredPersona"},...]
     │
     ▼
 Topological Wave Executor
 ┌──────────────────────────────────────────────────────────┐
 │  Wave 0: tasks with no dependencies  → async { ... }    │
 │  Wave 1: tasks whose deps ⊆ Wave0   → async { ... }    │
 │  ...                                                    │
 │  (awaitAll() per wave)                                  │
 └──────────────────────────────────────────────────────────┘
     │  Each task → Worker AgentPipeline (full ReAct loop)
     │  Workers emit SwarmEvent.WorkerStreamChunk for real-time UI
     ▼
 Synthesizer LLM pass → final answer

SwarmEvent types: WorkerStarted, WorkerCompleted, WorkerFailed,
                  WorkerStreamChunk, OrchestratorPlanning, Synthesizing,
                  FinalResult, Error
```

**parseTasks** uses `indexOf('[')` / `lastIndexOf(']')` to extract the JSON array from any surrounding markdown prose. Falls back to single-task wrapper when no parseable array is found.

**Dependency-cycle detection**: tasks with all-failed/skipped dependencies are evicted immediately so the wave loop never stalls.

### 2.3 AutoHealBuildUseCase

**File:** `domain/engine/AutoHealBuildUseCase.kt`

Detects compilation / Gradle errors from terminal output and loops the `AgentPipeline` to automatically patch source files and retry the build. Used by the `advanced_terminal` tool when `./gradlew assembleDebug` exits non-zero.

---

## 3. LLM Routing — ModelRegistry

**File:** `registry/ModelRegistry.kt`

All AI models are catalogued in `ModelRegistry` (verified as of Feb 2026). Models carry metadata: `id`, `displayName`, `provider`, `tier` (`ORCHESTRATOR` / `EXECUTOR` / `FAST`), `contextWindow`, `maxOutputTokens`, `supportsVision`, `supportsThinking`, `supportsFunctionCalling`, `costPer1MInputTokens/OutputTokens`.

**Routing Assignment (4 roles in AI Preferences):**
| Role | Default Tier | Used By |
|------|-------------|---------|
| **Chat Model** | Any | Single chat Q&A |
| **Agent Model** | EXECUTOR | Single-agent ReAct loop |
| **Swarm Orchestrator** | ORCHESTRATOR | SwarmOrchestrator planning/synthesis |
| **Swarm Worker** | EXECUTOR | Worker AgentPipeline inside swarm |

**GitHub Copilot models** (`copilot/` prefix, 19 models across 3 tiers):
- Fast: `gpt-5-mini`, `grok-code-fast-1`, `gemini-3-flash`
- Versatile: `claude-sonnet-4-6/4-5/4`, `claude-haiku-4-5`, `gpt-5.1`, `gpt-5.2`, `gpt-4.1`, `gpt-4o`
- Powerful: `claude-opus-4-6/4-5`, `gemini-3.1-pro`, `gemini-3-pro`, `gemini-2.5-pro`, `gpt-5.2-codex`, `gpt-5.3-codex`, `gpt-5.4`, `gpt-5.1-codex-max`

**CompletionService** (`data/network/CompletionService.kt`) — handles all provider dispatch:
- OpenAI-compatible: `callOpenAiCompatible` / `streamOpenAiCompatible` (accumulates `tool_call` deltas from SSE)
- Anthropic: `callAnthropic` / `streamAnthropic` (accumulates `tool_use` blocks + `input_json_delta`; sends `anthropic-beta: prompt-caching-2024-07-31` header; marks last message with `cache_control: {type: ephemeral}`)
- Google Gemini: native Gemini API
- Local LLM: `LlamaCppInferenceEngine`
- **`withRetry(maxRetries=3)`**: wraps every provider call with exponential backoff (1s→2s→4s) retrying on 429/500/502/503 / `IOException`.
- **GitHub Copilot token exchange**: `getCopilotSessionToken()` — `GET https://api.github.com/copilot_internal/v2/token` with `Authorization: token <oauth>` + `Copilot-Integration-Id: vscode-chat` header; short-lived session token cached via `refresh_in` seconds.

---

## 4. Tool Directory — The Hands

**File:** `data/tools/CompositeToolManager.kt`

All tools are registered in `CompositeToolManager.getToolDefinitions()` and routed in `executeTool()`.

### Category A — OS / Device Tools (Require Shizuku or Android permissions)

| Tool ID | Class / File | Description |
|---------|-------------|-------------|
| `advanced_terminal` | `AdvancedSystemTools` | Full shell via Shizuku + God Mode fallback |
| `screenshot_tool` | `AdvancedSystemTools` | `screencap` → `/data/local/tmp/screenshot.png` |
| `system_settings_tool` | `AdvancedSystemTools` | Get/put `system`/`secure`/`global` settings |
| `package_installer_tool` | `AdvancedSystemTools` | `pm install -r -g`, uninstall, list packages |
| `root_shell_tool` | `ShizukuCommandTool` | Arbitrary shell (6000 char output limit) |
| `ui_automation` | `UIAutomationTool` | Dump screen XML, tap, swipe, input text, keycodes |
| `semantic_ui` | `SemanticUITool` | `OmniAccessibilityService` semantic screen reader |
| `hardware_toggle_tool` | `SystemAssistantTools` | WiFi, BT, mobile data, flashlight, auto-rotate |
| `get_device_info` | `SystemAssistantTools` | Battery, RAM, storage, build, ABI, locale |
| `get_current_location` | `SystemAssistantTools` | GPS coordinates |
| `app_manager_tool` | `SystemAssistantTools` | List, launch, force-stop, clear data |
| `communicate_tool` | `SystemAssistantTools` | SMS, email, in-app channels |
| `read_notifications` | `NotificationCaptureTool` | Capture active Android notifications |
| `search_contacts` | `SystemContactsTool` | Query contacts by name / phone |
| `call_log_tool` | `AdvancedSystemTools` | Read recent calls |
| `sms_reader_tool` | `AdvancedSystemTools` | Read SMS inbox/sent, search |
| `read_incoming_sms` | `SmsCaptureBuffer` | Real-time buffer of incoming SMS (50 entries) |
| `media_control` | `OmniMediaSessionService` | Play/pause/stop/next/previous any media app |
| `device_admin` | `OmniDeviceAdminReceiver` | Lock screen, password policy, wipe |
| `ime_tool` | `OmniInputMethodService` | Inject text into any app's input field |
| `sync_service` | `OmniSyncService` | Trigger periodic task scheduler sync |
| `request_github_auth` | `RequestGitHubAuthenticationTool` | Agentic Device Flow OAuth (lazily constructed) |

**`OmniAccessibilityService`** + **`SemanticTreeParser`** provide semantic screen reading — the agent calls `semantic_ui` to get a structured JSON description of the current screen's interactive elements without needing screenshot+vision.

### Category B — Codebase / Dev Tools

| Tool ID | Class / File | Description |
|---------|-------------|-------------|
| `read_file_lines` | `FileToolManager` | Read specific line ranges (token-efficient) |
| `search_codebase` | `FileToolManager` | Regex search → path + line + snippet |
| `patch_file_content` | `FileToolManager` | Surgical find-and-replace |
| `create_file` | `FileToolManager` | Create new files |
| `delete_file` | `FileToolManager` | Delete files |
| `web_search` | `WebScraperTool` / `HeadlessBrowserManager` | Search the web + headless JS rendering |
| `git_manager` | `GitManagerTool` | init, status, add, commit, push, pull, branch, diff |
| `github_manager` | `GitHubManagerTool` | Create/update issues and PRs via GitHub REST |
| `analyze_logcat` | `LogcatAnalyzerTool` | Filter Logcat by tag/level/package |
| `visual_inspector` | `VisualInspectorTool` | Screenshot + UI layout analysis |
| `god_eye_profiler` | `GodEyeProfilerTool` | CPU, memory, frame rate profiling |
| `app_manifest_analyzer` | `AppManifestAnalyzerTool` | Read and analyse AndroidManifest.xml |
| `setup_build_environment` | `EnvironmentSetupManager` | Scaffold build environments |
| `planner_tool` | `TaskSchedulerTool` | Create/manage scheduled agent tasks |
| `telegram_publish` | `TelegramPublisherTool` | Post to Telegram channel/bot |
| `publish_to_discord` | `DiscordPublisherTool` | Send rich embeds to Discord webhook |
| `create_notion_page` | `NotionPublisherTool` | Create pages in Notion database |

### Memory Tools

| Tool ID | Class / File | Description |
|---------|-------------|-------------|
| `remember_fact` | `MemoryManager` | Store a long-term fact in Room `KnowledgeSnippet` |
| `search_knowledge` | `MemoryManager` | Keyword/vector search over stored facts |
| `update_memory` | `MemoryManager` | Edit an existing snippet |
| `delete_memory` | `MemoryManager` | Remove a snippet |
| Vector search | `VectorMemoryManager` | Semantic similarity search over embeddings |

**Tool Routing Rule:** `CompositeToolManager.executeTool()` tries tools in this order: Memory → System assistant → Logcat/Git → Environment/Terminal → Notification/Scheduler → Visual/Telegram/GitHub → File tools (default fallback).

---

## 5. Multi-Modal & Senses — Eyes & Ears

### 5.1 VoiceAssistantService — Always-On Duplex STT/TTS

**File:** `data/voice/VoiceAssistantService.kt`

- Runs as a foreground service with `FOREGROUND_SERVICE_TYPE_MICROPHONE`.
- Maintains a `voiceState: StateFlow<VoiceState>` exposed statically.
- Companion exposes: `startListening()`, `stopListening()`, `speak(text)`, `stopSpeaking()`, `requestBatteryOptimizationBypass(context)`.
- `vibrate()` produces a 50 ms haptic click on wake-word detection.
- WakeLock released safely: `if (wakeLock?.isHeld == true) wakeLock?.release()`.
- `transcriptFlow` exposes final transcriptions as `SharedFlow<String>`.

**VoiceManager** (`data/voice/VoiceManager.kt`) — pure facade:
- Zero internal `SpeechRecognizer` / `TextToSpeech` — prevents `ERROR_RECOGNIZER_BUSY`.
- `sttState`, `ttsState`, `partialTranscript` are `Flow<>` mapped from `VoiceAssistantService.voiceState`.
- `ChatViewModel.initVoice()` subscribes once via `VoiceAssistantService.transcriptFlow`.

### 5.2 AttachmentProcessor — Multi-Modal File Engine

**File:** `domain/attachment/AttachmentProcessor.kt`

- `resolveAttachmentMeta()` uses extension-aware `getMimeType()` (not `ContentResolver.getType()`) → `.kt`/`.py`/`.js` correctly resolve to `text/plain`.
- `MAX_TEXT_CONTENT_CHARS = 48_000` — caps text/code at ~12k tokens before sending to LLM APIs.
- `readTextWithTruncation()` — enforces the cap with a `[TRUNCATED]` marker.
- `classifyMediaType()` takes a non-nullable `String`.
- Safety limits: max 5 files, 15 MB total per message.
- Vision models receive `base64`-encoded images; Gemini receives video natively.

---

## 6. Persistence Layer

### Room Database — `OmniDevDatabase`

**File:** `data/db/OmniDevDatabase.kt`

| Entity | DAO | Purpose |
|--------|-----|---------|
| `ChatMessageEntity` | `ChatMessageDao` | Individual messages with role, content, toolCalls, attachments |
| `ChatSessionEntity` | `ChatSessionDao` | Session metadata (title, created, lastUpdated) |
| `KnowledgeSnippet` | `KnowledgeDao` | Long-term memory facts with keyword + embedding search |

### DataStore Preferences

| Repository | Key Data |
|-----------|----------|
| `ApiKeyRepository` | Provider API keys (Anthropic, OpenAI, Gemini, Groq, OpenRouter, GitHub Models, GitHub Copilot) |
| `SettingsRepository` | Model role assignments, system prompt, Shizuku mode, local model URI, voice settings |

### GitHubDeviceFlowManager

**File:** `data/auth/GitHubDeviceFlowManager.kt`

- `HTTP_TIMEOUT_MS = 30_000` on all `HttpsURLConnection` calls.
- `DEFAULT_COMPREHENSIVE_SCOPE = "repo workflow gist read:user user:email"`.
- `startDeviceFlowAndPoll(overrideScope: String? = null)` — scope is overridable by `RequestGitHubAuthenticationTool`.
- `pollForToken`: `conn.responseCode` wrapped in `try-catch(IOException)` with fallback to `errorStream` for Android < API 29 (400 response throws instead of returning code).
- `VERIFICATION_URL` is `public` for use by the agent tool.

---

## 7. UI Layer

**Pattern:** MVI with `StateFlow` / `SharedFlow`, Jetpack Compose, Material 3 Expressive.

| Screen | ViewModel | Key Composables |
|--------|-----------|----------------|
| `ChatScreen` | `ChatViewModel` | `AgentLiveConsole`, `MarkdownText`, `ExpandableBlock` (think-tag cards) |
| `AISettingsScreen` | `AISettingsViewModel` | Model role pickers for 4 roles |
| `LocalModelManagerScreen` | — | SAF file picker, engine status card |
| `MemoryExplorerScreen` | — | Browse/search/delete `KnowledgeSnippet` |
| `ScheduledTasksScreen` | — | Live reactive task list |
| `SystemPromptEditorScreen` | — | Full-screen prompt editor per role |
| `ToolRegistryScreen` | — | 34+ tools grouped by category, searchable |
| `IntegrationsScreen` | — | GitHub Models PAT input, OAuth buttons |
| `ProvidersScreen` | `ProvidersViewModel` | API key management |
| `DebugScreen` | `DebugViewModel` | Live log viewer with level filter |

**MessageFormatter** (`ui/chat/MessageFormatter.kt`):
- `THINKING_RE = Regex("<(?:thinking|think)>(.*?)</(?:thinking|think)>", DOTALL)` — strips both Anthropic `<thinking>` and DeepSeek-R1/o1 `<think>` variants.
- Stripped content → collapsible `ExpandableBlock("🧠 Thought Process", ...)` card above the answer.

**OmniBubbleService** (`ui/overlay/OmniBubbleService.kt`): floating chat bubble overlay (`SYSTEM_ALERT_WINDOW`) that stays visible over other apps.

**Navigation:** `ui/navigation/AppNavigation.kt` — Compose Navigation host with bottom navigation.

---

## 8. System Services & Receivers

| Component | File | Role |
|-----------|------|------|
| `VoiceAssistantService` | `data/voice/` | Always-on STT/TTS singleton |
| `OmniBubbleService` | `ui/overlay/` | Floating overlay bubble |
| `AgentNotificationService` | `data/tools/` | Foreground notification for active agent tasks |
| `OmniSyncService` | `data/sync/` | 60s poll loop for `TaskSchedulerTool` ready tasks |
| `OmniMediaSessionService` | `data/media/` | `MediaSessionManager` — play/pause/next any media app |
| `OmniInputMethodService` | `data/input/` | IME for agent-driven text injection |
| `OmniAccessibilityService` | `data/accessibility/` | Semantic screen reading via AccessibilityNodeInfo |
| `BootReceiver` | `data/system/` | Auto-starts Voice + Sync services on BOOT_COMPLETED |
| `OmniSmsReceiver` | `data/communication/` | Captures incoming SMS into `SmsCaptureBuffer` (50 entries max) |
| `OmniDeviceAdminReceiver` | `data/admin/` | Device admin: lockScreen, password policy, wipe |

**XML Configs:**
- `res/xml/device_admin_config.xml` — force-lock, limit-password, wipe-data, expire-password, watch-login, reset-password policies.
- `res/xml/input_method_config.xml` — en_US keyboard subtype.
- `res/xml/accessibility_service_config.xml` — `OmniAccessibilityService` event types.

---

## 9. Project Directory Tree — File Map

<details>
<summary><strong>📁 app/src/main/ — Click to expand full file map</strong></summary>

```
app/src/main/
│
├── AndroidManifest.xml          ← All permissions, services, receivers declared here
│
├── cpp/
│   ├── CMakeLists.txt           ← Two-tier: git submodule → FetchContent b8233 fallback
│   ├── llama_jni.cpp            ← REAL JNI bridge using llama.cpp C API
│   │                               llama_init_from_model, llama_memory_clear,
│   │                               llama_vocab_is_eog, streaming token callback
│   └── llama_jni_stub.cpp       ← No-op stub when llama.cpp submodule absent
│
└── java/com/omnidev/workspace/
    │
    ├── MainActivity.kt          ← Single-activity host; wires all ViewModels + services
    ├── OmniDevApp.kt            ← Application class, CrashHandler init
    │
    ├── data/
    │   ├── accessibility/
    │   │   ├── OmniAccessibilityService.kt  ← A11y service; semantic node tree
    │   │   ├── SemanticTreeParser.kt        ← Parse AccessibilityNodeInfo → JSON
    │   │   ├── SemanticUITool.kt            ← Tool wrapper for semantic_ui
    │   │   ├── GodModeAccessibility.kt      ← High-privilege a11y actions
    │   │   └── AccessibilityStateManager.kt ← StateFlow of a11y service status
    │   │
    │   ├── admin/
    │   │   └── OmniDeviceAdminReceiver.kt   ← DeviceAdminReceiver; lock/password/wipe
    │   │
    │   ├── auth/
    │   │   ├── GitHubDeviceFlowManager.kt   ← GitHub OAuth Device Flow polling
    │   │   └── OAuthManager.kt              ← Generic OAuth 2.0 helper
    │   │
    │   ├── communication/
    │   │   └── OmniSmsReceiver.kt           ← BroadcastReceiver + SmsCaptureBuffer
    │   │
    │   ├── db/
    │   │   ├── OmniDevDatabase.kt           ← Room DB (v1, exportSchema=false)
    │   │   ├── dao/
    │   │   │   ├── ChatMessageDao.kt
    │   │   │   ├── ChatSessionDao.kt
    │   │   │   └── KnowledgeDao.kt
    │   │   └── entities/
    │   │       ├── ChatMessageEntity.kt
    │   │       ├── ChatSessionEntity.kt
    │   │       └── KnowledgeSnippet.kt      ← Long-term memory fact unit
    │   │
    │   ├── debug/
    │   │   ├── CrashHandler.kt              ← UncaughtExceptionHandler → structured report
    │   │   └── DebugLogManager.kt           ← Ring-buffer log with timestamps + tags
    │   │
    │   ├── input/
    │   │   └── OmniInputMethodService.kt    ← IME; commitText, delete, cursor read
    │   │
    │   ├── localllm/
    │   │   ├── LlamaCppInferenceEngine.kt   ← SAF URI → cacheDir copy → nativeLoadModel
    │   │   │                                   @Volatile tempModelFile deleted in safeFreeCurrent()
    │   │   ├── LocalInferenceEngine.kt      ← Interface for local engines
    │   │   └── LocalEngineType.kt           ← Enum: LLAMA_CPP, STUB
    │   │
    │   ├── media/
    │   │   └── OmniMediaSessionService.kt   ← MediaSessionManager; play/pause/next/info
    │   │
    │   ├── model/
    │   │   ├── AIModel.kt                   ← Data class with all model metadata fields
    │   │   └── CompletionRequest.kt         ← Request/Response + ToolCall + MessageRole enums
    │   │
    │   ├── network/
    │   │   └── CompletionService.kt         ← All provider HTTP calls; SSE streaming;
    │   │                                       withRetry; Anthropic prompt caching;
    │   │                                       Copilot token exchange
    │   │
    │   ├── repository/
    │   │   ├── ApiKeyRepository.kt          ← DataStore: provider API keys
    │   │   ├── ChatRepository.kt            ← Room CRUD for messages + sessions
    │   │   └── SettingsRepository.kt        ← DataStore: model assignments, prompts
    │   │
    │   ├── sync/
    │   │   └── OmniSyncService.kt           ← Foreground service; 60s TaskScheduler poll
    │   │
    │   ├── system/
    │   │   └── BootReceiver.kt              ← BOOT_COMPLETED → start Voice + Sync services
    │   │
    │   ├── tools/
    │   │   ├── ToolManager.kt               ← Interface: getToolDefinitions(), executeTool()
    │   │   ├── CompositeToolManager.kt      ← Routes all 40+ tools; single ToolManager impl
    │   │   ├── FileToolManager.kt           ← read/search/patch/create/delete/terminal/web
    │   │   ├── MemoryManager.kt             ← remember/search/update/delete + Room KnowledgeDao
    │   │   ├── VectorMemoryManager.kt       ← Semantic embedding search over KnowledgeSnippet
    │   │   ├── SystemAssistantTools.kt      ← hardware/location/device-info/app-manager/communicate
    │   │   ├── AdvancedSystemTools.kt       ← Shizuku shell, screenshot, settings, sms, calls
    │   │   ├── AdvancedFileTools.kt         ← Extended file operations
    │   │   ├── ShizukuCommandTool.kt        ← Raw Shizuku binder command execution
    │   │   ├── UIAutomationTool.kt          ← Dump XML, tap, swipe, input text, keycodes
    │   │   ├── SemanticUITool.kt            ← Semantic screen tree tool
    │   │   ├── AndroidIntentTool.kt         ← Intent-based OS actions
    │   │   ├── SystemContactsTool.kt        ← Contacts query
    │   │   ├── NotificationCaptureTool.kt   ← Read active notifications
    │   │   ├── LogcatAnalyzerTool.kt        ← Filter Logcat output
    │   │   ├── GitManagerTool.kt            ← Local git operations
    │   │   ├── GitHubManagerTool.kt         ← GitHub REST API (issues, PRs)
    │   │   ├── RequestGitHubAuthenticationTool.kt  ← Agentic Device Flow OAuth
    │   │   ├── EnvironmentSetupManager.kt   ← Scaffold build environments
    │   │   ├── TaskSchedulerTool.kt         ← Cron-like task scheduling
    │   │   ├── HeadlessBrowserManager.kt    ← Headless JS web rendering
    │   │   ├── WebScraperTool.kt            ← Web search + scraping
    │   │   ├── VisualInspectorTool.kt       ← Screenshot + layout analysis
    │   │   ├── GodEyeProfilerTool.kt        ← CPU/memory/FPS profiling
    │   │   ├── AppManifestAnalyzerTool.kt   ← AndroidManifest analysis
    │   │   ├── AgentNotificationService.kt  ← Foreground notification during agent run
    │   │   ├── TelegramPublisherTool.kt     ← Post to Telegram
    │   │   ├── DiscordPublisherTool.kt      ← Post to Discord webhook
    │   │   ├── NotionPublisherTool.kt       ← Create Notion pages
    │   │   └── SmsCaptureBuffer.kt          ← Thread-safe bounded SMS buffer (50 max)
    │   │
    │   └── voice/
    │       ├── VoiceAssistantService.kt     ← SINGLETON STT/TTS source; wake-word loop
    │       └── VoiceManager.kt             ← PURE FACADE over VoiceAssistantService
    │
    ├── domain/
    │   ├── attachment/
    │   │   └── AttachmentProcessor.kt       ← MIME resolution, token truncation, base64
    │   │
    │   └── engine/
    │       ├── AgentPipeline.kt             ← ReAct loop; AgentConfig; AgentEvent
    │       ├── SwarmOrchestrator.kt         ← Plan/Delegate/Synthesize; wave executor
    │       ├── AutoHealBuildUseCase.kt      ← Build error → agent fix → retry loop
    │       └── OmniMode.kt                 ← Enum: AGENT, SWARM, CHAT, VOICE
    │
    ├── registry/
    │   └── ModelRegistry.kt                ← All AI models; 3 tiers; 8+ providers
    │
    └── ui/
        ├── chat/
        │   ├── ChatScreen.kt               ← Main chat UI; attachment bar; mode tabs
        │   ├── ChatViewModel.kt            ← MVI state; agent/swarm invocation
        │   ├── AgentLiveConsole.kt         ← Real-time scrolling agent step log
        │   ├── AgentConsoleEntry.kt        ← Individual console entry composable
        │   ├── MarkdownText.kt             ← Headers/bold/italic/code/lists renderer
        │   ├── MessageFormatter.kt         ← THINKING_RE; ExpandableBlock logic
        │   └── ConfirmationGate.kt         ← High-risk action approval dialog
        │
        ├── debug/
        │   ├── DebugScreen.kt              ← Live log viewer with level filter
        │   └── DebugViewModel.kt
        │
        ├── navigation/
        │   └── AppNavigation.kt            ← Compose Navigation host; bottom nav
        │
        ├── overlay/
        │   └── OmniBubbleService.kt        ← Floating bubble over other apps
        │
        ├── providers/
        │   ├── ProvidersScreen.kt          ← API key management UI
        │   └── ProvidersViewModel.kt
        │
        ├── settings/
        │   ├── AISettingsScreen.kt         ← 4 model role pickers
        │   ├── AISettingsViewModel.kt
        │   ├── LocalModelManagerScreen.kt  ← SAF picker; engine status card
        │   ├── MemoryExplorerScreen.kt     ← Browse/search/delete memories
        │   ├── ScheduledTasksScreen.kt     ← Reactive task list
        │   ├── SystemPromptEditorScreen.kt ← Per-role prompt editor
        │   ├── ToolRegistryScreen.kt       ← 40+ tools by category, searchable
        │   └── IntegrationsScreen.kt       ← GitHub Models PAT; OAuth flows
        │
        └── theme/
            ├── Color.kt                   ← Material 3 color tokens
            ├── Theme.kt                   ← OmniDevTheme with dynamic color
            └── Type.kt                    ← Typography scale
```

</details>

---

## 10. Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.0 |
| UI Framework | Jetpack Compose + Material 3 Expressive |
| State Management | MVI with `StateFlow` / `SharedFlow` |
| Persistence | Room (chat history, memory) + DataStore Preferences (settings, keys) |
| Async | Kotlin Coroutines + Flow + `channelFlow` |
| Serialization | `kotlinx.serialization` (JSON) |
| HTTP Client | `HttpsURLConnection` with SSE streaming (no OkHttp dependency) |
| Native Inference | llama.cpp b8233 (C++ via JNI, compiled with Android NDK) |
| Privileged Operations | Shizuku API |
| Build System | Gradle Kotlin DSL + Version Catalog (`libs.versions.toml`) |
| CI | GitHub Actions (lint → test → assembleDebug + assembleRelease) |

---

## 11. Getting Started

### Prerequisites

- Android Studio Iguana (2023.2.1) or later
- JDK 17 (Temurin)
- Android SDK 35 + NDK (install via SDK Manager)
- *(For local LLM)* A quantized `.gguf` model file

### Clone and Build

```bash
# 1. Clone with the llama.cpp submodule
git clone --recurse-submodules https://github.com/obieda-hussien/DevSwarm.git
cd DevSwarm

# 2. Build Debug APK (NDK compiles libllama_jni.so automatically)
./gradlew assembleDebug --no-daemon

# 3. Lint check (CI gate)
./gradlew lint

# 4. Unit tests
./gradlew test
```

> If already cloned without `--recurse-submodules`:
> ```bash
> git submodule update --init --recursive
> ```
> The Gradle task `initLlamaCppSubmodule` also handles this automatically before CMake configure.

### Enable Local Edge Model (BYOM)

1. Download any quantized GGUF model (e.g., `Llama-3.2-1B-Instruct-IQ4_XS.gguf`) from HuggingFace.
2. Transfer to your Android device.
3. Open **Settings → Local Edge Model (BYOM)** → tap **Select .gguf Model File**.
4. Engine Status changes to **Ready — ModelName.gguf** 🟢
5. In **AI Preferences → Chat Model** select **Local Edge Model (BYOM)**.

### API Keys (Cloud Models)

Open **Settings → API Keys** and enter keys for: Anthropic, OpenAI, Gemini, Groq, OpenRouter, GitHub Models (PAT), GitHub Copilot (OAuth Device Flow).

---

## 12. CI/CD Pipeline

**File:** `.github/workflows/android-ci.yml`

| Step | Action |
|------|--------|
| Checkout | `actions/checkout@v4` — `submodules: recursive` to pull llama.cpp |
| JDK | `actions/setup-java@v4` — Temurin JDK 17 |
| Android NDK | `android-actions/setup-android@v3` |
| CMake cache | `actions/cache@v4` — key: `cmake-fetchcontent-{runner.os}-llama-b8233` |
| Lint | `./gradlew lint` — **hard failure gate** |
| Unit Tests | `./gradlew test` |
| Build | `./gradlew assembleDebug assembleRelease --stacktrace` |
| Artifacts | Uploads `app-debug.apk` and `app-release.apk` |

Release builds use debug signing until a production keystore is configured (see `// TODO` in `app/build.gradle.kts`). ProGuard is enabled on release with keep rules for: kotlinx.serialization, Room, JNI methods, Shizuku, Kotlin Coroutines.

---

## 13. Android Permissions Reference

| Permission | Category | Purpose |
|-----------|----------|---------|
| `INTERNET` | Normal | Cloud AI API calls |
| `READ_CONTACTS`, `WRITE_CONTACTS` | Dangerous | Contacts search tool |
| `READ_CALL_LOG`, `WRITE_CALL_LOG` | Dangerous | Call log tool |
| `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS` | Dangerous | SMS tools |
| `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | Dangerous | Bluetooth toggle |
| `WRITE_SETTINGS` | Signature | System settings tool |
| `REQUEST_INSTALL_PACKAGES` | Signature | Package installer tool |
| `PACKAGE_USAGE_STATS` | Signature | App manager (usage access) |
| `ACTIVITY_RECOGNITION` | Dangerous | Physical activity sensor |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Dangerous | GPS location tool |
| `CAMERA` | Dangerous | Visual inspector |
| `SYSTEM_ALERT_WINDOW` | Signature | Overlay bubble service |
| `FOREGROUND_SERVICE` | Normal | Agent notification + voice service |
| `FOREGROUND_SERVICE_MICROPHONE` | Normal | VoiceAssistantService |
| `RECORD_AUDIO` | Dangerous | STT microphone |
| `VIBRATE` | Normal | Wake-word haptic feedback |
| `RECEIVE_BOOT_COMPLETED` | Normal | BootReceiver auto-start |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Normal | VoiceAssistantService battery bypass |
| `BIND_ACCESSIBILITY_SERVICE` | Signature | on `<service>` tag only — OmniAccessibilityService |
| `BIND_INPUT_METHOD` | Signature | on `<service>` tag only — OmniInputMethodService |

> **Note for AI agents:** `BIND_NOTIFICATION_LISTENER_SERVICE` and `MEDIA_CONTENT_CONTROL` are **system-only protected permissions** (`signatureOrSystem` protection level). They must NEVER appear as `<uses-permission>` tags — they belong only as `android:permission="..."` attributes on the `<service>` tag. Adding them as `<uses-permission>` causes a `ProtectedPermissions` lint error that blocks CI.

---

## 14. Architecture Conventions & Agent Rules

> This section is critical for AI agents making future code changes.

### Rule 1 — Tool Routing
Always add new tools to both `CompositeToolManager.getToolDefinitions()` AND `CompositeToolManager.executeTool()`. The routing in `executeTool()` is a `when` chain — new tool IDs must be added or they silently fall through to "tool not found".

### Rule 2 — Context Window Integrity
When evicting messages in `AgentPipeline.trimMessagesForContextWindow`, ALWAYS evict ASSISTANT messages with `toolCalls` together with all immediately-following TOOL messages. Orphaned TOOL messages cause `400 Bad Request` from OpenAI/Anthropic.

### Rule 3 — VoiceManager is a Facade
Never add `SpeechRecognizer` or `TextToSpeech` to `VoiceManager`. All STT/TTS must go through `VoiceAssistantService` static methods. Concurrent recognizer sessions throw `ERROR_RECOGNIZER_BUSY`.

### Rule 4 — Protected Permissions
Never declare `BIND_NOTIFICATION_LISTENER_SERVICE`, `MEDIA_CONTENT_CONTROL`, or any other `signatureOrSystem` permission as a `<uses-permission>`. Place them as `android:permission="..."` on the relevant `<service>` tag only.

### Rule 5 — SAF Model Files
In `LlamaCppInferenceEngine`, `content://` URIs are always copied to `context.cacheDir/llm_active_model.gguf` before `nativeLoadModel`. The temp file is tracked in `@Volatile tempModelFile` and deleted in `safeFreeCurrent()`. Do not bypass this copy step.

### Rule 6 — Token Budget in AttachmentProcessor
`MAX_TEXT_CONTENT_CHARS = 48_000` is the hard cap for text/code file content. Do not raise this without benchmarking all supported providers' context window limits.

### Rule 7 — Anthropic Prompt Caching
Both `callAnthropic` and `streamAnthropic` mark the last conversation message with `cache_control: {type: ephemeral}` and send `anthropic-beta: prompt-caching-2024-07-31`. Do not remove these without Anthropic approval.

### Rule 8 — SwarmEvent Exhaustiveness
`SwarmEvent` is a sealed class. When adding new variants, update ALL `when(event)` expressions in `ChatViewModel.handleSwarmEvent()` — Kotlin `when` on sealed classes must be exhaustive or the build fails.

### Rule 9 — CompletionService withRetry
All new provider call sites inside `CompletionService.invoke()` and `stream()` must be wrapped in `withRetry { }`. The retry helper handles 429/5xx with exponential backoff (1s→2s→4s, max 3 retries) and correctly rethrows `CancellationException`.

### Rule 10 — llama.cpp b8233 API
Use `llama_init_from_model` (not deprecated `llama_new_context_with_model`), `llama_memory_clear(llama_get_memory(ctx), true)` (not removed `llama_kv_self_clear`), and `llama_vocab_is_eog` (not deprecated `llama_token_is_eog`).

---

## Roadmap

- [ ] In-app GGUF model downloader — browse HuggingFace and download within the app
- [ ] Multi-turn context window summarization — automatic summarization when context fills
- [ ] RAG — embed codebase files into a local vector store for semantic search
- [ ] Plugin SDK — third-party tool plugins as separate APKs
- [ ] Diffusion image generation — Stable Diffusion on-device via GGUF
- [ ] Encrypted knowledge base — AES-256 for sensitive memory snippets
- [ ] Production release signing — replace debug keystore for Google Play
- [ ] Automated integration tests — Espresso / Compose UI test suite
- [ ] Home screen widget — active agent task status
