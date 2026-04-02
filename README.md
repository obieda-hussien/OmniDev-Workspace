# OmniDev Workspace — Master System Blueprint

[![Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/workflows/android-ci.yml/badge.svg)](https://github.com/obieda-hussien/OmniDev-Workspace/actions/workflows/android-ci.yml)

> **AI AGENT CONTEXT MAP** — This document is the authoritative reference for all LLM agents, Copilot sessions, and developers. Read it first — it maps every file, flow, pattern, and decision in the repository so you can operate with full context in a single pass, saving thousands of context-window tokens.

**OmniDev Workspace** is a **God-Mode Autonomous AI Software Engineer** for Android. It orchestrates cloud and on-device LLMs into a multi-agent swarm that can write code, run terminals, control the OS, manage files, speak and listen, browse the web, and self-heal build failures — fully autonomously.

> Package: `com.omnidev.workspace` · Min SDK: 24 · Target SDK: 35 · NDK: 27.0.12077973 · Language: Kotlin 2.0 · UI: Jetpack Compose + Material 3

---

## Table of Contents

1. [System Architecture Overview](#1-system-architecture-overview)
2. [Core Execution Flows — The Brain](#2-core-execution-flows--the-brain)
3. [LLM Routing — ModelRegistry](#3-llm-routing--modelregistry)
4. [Tool Ecosystem — Complete Directory](#4-tool-ecosystem--complete-directory)
5. [Intelligence & Automation Engines](#5-intelligence--automation-engines)
6. [IPC & System Integration](#6-ipc--system-integration)
7. [Multi-Modal Capabilities — Eyes, Ears, Voice](#7-multi-modal-capabilities--eyes-ears-voice)
8. [Persistence Layer](#8-persistence-layer)
9. [UI Layer](#9-ui-layer)
10. [System Services & Receivers](#10-system-services--receivers)
11. [AIDL Interfaces](#11-aidl-interfaces)
12. [Project File Map](#12-project-file-map)
13. [Tech Stack & Dependencies](#13-tech-stack--dependencies)
14. [Getting Started & CI/CD](#14-getting-started--cicd)
15. [Android Permissions Reference](#15-android-permissions-reference)
16. [Architecture Conventions & Agent Rules](#16-architecture-conventions--agent-rules)

---

## 1. System Architecture Overview

```
┌───────────────────────────────────────────────────────────────────────┐
│                         OmniDev Workspace                             │
│                                                                       │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │
│  │  Shizuku /  │  │  Llama.cpp  │  │    Swarm    │  │   Duplex    │ │
│  │  Root Shell │  │  On-device  │  │  Multi-Agent│  │  Voice I/O  │ │
│  │  Execution  │  │  Inference  │  │  (Parallel) │  │  + Overlay  │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘ │
│         │                │                │                │         │
│         └────────────────┴────────────────┴────────────────┘         │
│                                     │                                 │
│                          ┌──────────▼──────────┐                     │
│                          │    AgentPipeline     │                     │
│                          │  (ReAct Loop)        │                     │
│                          │  CompletionService   │                     │
│                          │  CompositeToolMgr    │                     │
│                          └──────────┬──────────┘                     │
│                                     │                                 │
│           ┌────────────┬────────────┼─────────────┬──────────────┐   │
│    ToolManager   MemoryMgr   VectorMemory    Accessibility   VPN  │   │
│    (70+ tools)  (SQLite)    (Cosine sim)     (UIAutomation)  (TUN)│   │
└───────────────────────────────────────────────────────────────────────┘
```

### Four Core Pillars

| Pillar | Description | Key Files |
|--------|-------------|-----------|
| **OS-Level Execution** | Shizuku (ADB-level) + root shell + Device Admin for privileged ops | `PrivilegedExecutionManager`, `ShizukuCommandTool`, `RishShellManager` |
| **Local Edge Inference** | Llama.cpp JNI running GGUF models on-device with zero network dependency | `LlamaCppInferenceEngine`, `LocalInferenceEngine`, `LocalModelManagerScreen` |
| **Multi-Agent Swarm** | Orchestrator decomposes tasks; Worker `AgentPipeline` instances run in parallel | `SwarmOrchestrator`, `AgentPipeline`, `IntentClassifier` |
| **Duplex Voice UI** | Wake-word detection + SpeechRecognizer + TTS + floating bubble overlay | `VoiceAssistantService`, `OmniBubbleService`, `AdvancedVoiceCommandEngine` |

---

## 2. Core Execution Flows — The Brain

### 2.1 AgentPipeline (ReAct Loop)
**File:** `domain/engine/AgentPipeline.kt`

The central execution engine. Runs a **Reason → Act → Observe** loop:
1. Sends system prompt + history + tool definitions to the LLM
2. Parses the response for tool calls
3. Executes tools via `CompositeToolManager`
4. Appends tool results to context and loops
5. Terminates on `FINAL_ANSWER`, iteration limit, token budget, or wall-clock timeout

**Config:** `AgentConfig` — `maxIterations` (50), `tokenBudget`, `maxExecutionTimeMs`, `maxIterationTimeMs` (3 min), `maxRepeatToolCalls`

**Output stream:** `AgentEvent` — `Thinking`, `ToolCall`, `ToolResult`, `StreamChunk`, `FinalAnswer`, `Error`

### 2.2 SwarmOrchestrator
**File:** `domain/engine/SwarmOrchestrator.kt`

Breaks complex requests into ≤10 sub-tasks via an Orchestrator LLM prompt, spawns independent Worker `AgentPipeline` coroutines in parallel, and synthesizes results. Emits `SwarmEvent` — `PlanReady`, `WorkerStarted`, `WorkerCompleted`, `WorkerFailed`, `WorkerStreamChunk`, `FinalSynthesis`.

### 2.3 IntentClassifier
**File:** `domain/engine/IntentClassifier.kt`

Fast LLM-based intent router that classifies user input into `OmniMode` categories before routing to the appropriate pipeline.

### 2.4 OmniMode
**File:** `domain/engine/OmniMode.kt`

Enum of agent operating modes: `DEVELOPER`, `RESEARCHER`, `SYSTEM_OPERATOR`, `CREATIVE`, `ANALYST`, `ASSISTANT`, `SWARM`.

### 2.5 AutoHealBuildUseCase
**File:** `domain/engine/AutoHealBuildUseCase.kt`

Runs `./gradlew build`, parses compiler errors from stdout, and invokes `AgentPipeline` to patch the failing files automatically in a retry loop.

---

## 3. LLM Routing — ModelRegistry

**File:** `registry/ModelRegistry.kt`

Central registry of all supported AI models. Provides:
- `getModelById(id)` / `findModelById(id)` — look up a model
- `getModelsForTier(tier)` — filter by `ModelTier` (FAST, BALANCED, POWERFUL, LOCAL)
- `getBestModelForTier(tier, preferredProvider?)` — smart selection
- `getDefaultModelForRole(role)` — role-based defaults (ORCHESTRATOR, WORKER, CODER, RESEARCHER…)
- `addDynamicCopilotModels(models)` / `clearDynamicCopilotModels()` — runtime model injection

**Providers:** OpenAI, Anthropic, Google Gemini, Mistral, Groq, Cerebras, GitHub Copilot, Local (Llama.cpp)

**Auth:**
- `CopilotSessionManager` — GitHub OAuth device-flow for Copilot token
- `CopilotModelRefresher` — refreshes Copilot model list from API
- `GitHubDeviceFlowManager` — generic GitHub OAuth device flow
- `OAuthManager` — generic OAuth helper
- `ApiKeyRepository` — encrypted DataStore for all provider API keys

---

## 4. Tool Ecosystem — Complete Directory

All tools are registered and dispatched by `CompositeToolManager` (`data/tools/CompositeToolManager.kt`).

### 4.1 File & Terminal Tools (via `FileToolManager`)

| Tool Name | Description |
|-----------|-------------|
| `read_file` | Read file contents |
| `write_file` / `create_file` | Write / create files |
| `delete_file` | Delete files |
| `search_files` | Search file contents with regex |
| `patch_file` | Apply unified diff patches |
| `terminal` | Run shell commands |
| `web_search` | Web search (overridden by WebSearchTool) |
| `grep_search` | Ripgrep-powered code search |
| `find_files` | Find files by pattern |
| `file_permissions` | Read/set UNIX permissions |
| `disk_usage` | du/df disk usage |
| `archive_tool` | zip/tar operations |

**Files:** `FileToolManager.kt`, `AdvancedFileTools.kt`, `GodModeFileRouter.kt`, `GodModeAccessibility.kt`

### 4.2 Web & Network Tools

| Tool Name | Description |
|-----------|-------------|
| `web_search` | DuckDuckGo/Bing search + deep fetch (parallel, 6000 chars/site) |
| `web_search_deep` | Fetches full page content of top-N search results |
| `web_scraper` | Single-page scraping |
| `scrape_multiple` | Parallel scraping of ≤8 URLs (12s timeout, 8000 chars each) |
| `headless_browser` | Full browser automation (JavaScript rendering) |
| `network_request` | HTTP GET/POST/PUT/DELETE with headers |
| `network_monitor` | VPN-based traffic monitor (TUN interceptor, no root) — start/stop/status/get_traffic_log/get_app_stats/block_domain/unblock_domain/block_ads |

**Files:** `WebSearchTool.kt`, `WebScraperTool.kt`, `HeadlessBrowserManager.kt`, `NetworkRequestTool.kt`, `NetworkMonitorTool.kt`, `OmniDevVpnService.kt`

### 4.3 Memory Tools

| Tool Name | Description |
|-----------|-------------|
| `remember_fact` | Store a fact in SQLite knowledge base |
| `search_knowledge` | Semantic search over knowledge base |
| `update_memory` | Update an existing memory entry |
| `delete_memory` | Delete a memory entry |
| `vector_store` | Add embeddings to vector store |
| `vector_search` | Approximate-nearest-neighbor search |
| `vector_similar` | Find similar items by cosine similarity |

**Files:** `MemoryManager.kt`, `VectorMemoryManager.kt`

### 4.4 Communication & Social Tools

| Tool Name | Description |
|-----------|-------------|
| `communicate_tool` | Send SMS, email, notifications |
| `telegram_publisher` | Post to Telegram channel/group |
| `telegram_bot` | Full Telegram Bot API (send/receive/media) |
| `discord_bot` | Discord Bot API |
| `discord_publisher` | Post rich embeds to Discord webhook |
| `slack_tool` | Slack Web API |
| `send_grid_email` | Transactional email via SendGrid |
| `notion_publisher` | Create/update Notion pages and databases |
| `n8n_automation` | Trigger n8n workflows via webhook |
| `whatsapp` | WhatsApp Cloud API (official, requires Meta token) |
| `whatsapp_bridge` | WhatsApp via local bridge URL |
| `social_media_video` | yt-dlp + noembed: get_info, get_captions, download, play, search_youtube, setup |

**Files:** `TelegramPublisherTool.kt`, `TelegramBotTool.kt`, `DiscordBotTool.kt`, `DiscordPublisherTool.kt`, `SlackTool.kt`, `SendGridEmailTool.kt`, `NotionPublisherTool.kt`, `N8nAutomationTool.kt`, `WhatsAppTool.kt`, `WhatsAppBridgeTool.kt`, `SocialMediaTool.kt`

> **Note:** `TelegramBotTool` and `TelegramPublisherTool` serve different purposes: publisher posts to channels, bot handles interactive conversations. Similarly `WhatsAppTool` (Cloud API) vs `WhatsAppBridgeTool` (local bridge) are distinct integrations.

### 4.5 System & OS Tools

| Tool Name | Description |
|-----------|-------------|
| `hardware_toggle_tool` | Toggle WiFi, Bluetooth, airplane mode, flashlight |
| `system_power` | Reboot, shutdown, recovery (via Shizuku/root) |
| `get_device_info` | Build info, RAM, storage, battery, CPU, network |
| `get_current_location` | GPS + network location |
| `app_manager_tool` | Install/uninstall/launch/force-stop apps |
| `permission_manager` | Grant/revoke permissions programmatically |
| `device_admin` | Device Admin actions (lock screen, wipe data) |
| `android_intent` | Fire arbitrary Android intents |
| `shizuku_command` | Run ADB-level commands via Shizuku |
| `agent_runtime` | python_run, node_run, shell_script, pip_install, download_exec |
| `agent_sandbox` | Isolated code execution sandbox |
| `advanced_terminal` | Extended terminal with env management |
| `setup_build_environment` | Configure Termux/SDK build environment |
| `termux_bridge` | Run commands in Termux environment |
| `planner_tool` | Create calendar events and reminders |
| `task_scheduler` | Schedule recurring/one-shot agent tasks |
| `task_manager` | List, cancel, inspect scheduled tasks |
| `system_contacts` | Read/search device contacts |
| `clipboard` | Get/set clipboard content |
| `sms_reader` | Read device SMS messages |
| `call_log` | Read call history |
| `media_control` | Control media playback (play/pause/skip/volume) |
| `sync_service` | Control background OmniSyncService |
| `ime_tool` | Control OmniDev IME: commit_text, delete, get_selected |

**Files:** `SystemAssistantTools.kt`, `AdvancedSystemTools.kt`, `SystemPowerTool.kt`, `ShizukuCommandTool.kt`, `AgentRuntimeTool.kt`, `AgentSandboxTool.kt`, `EnvironmentSetupManager.kt`, `TermuxEnvironmentBridge.kt`, `TermuxExecutionFix.kt`, `PermissionManagerTool.kt`, `SystemContactsTool.kt`, `ClipboardTool.kt`, `AndroidIntentTool.kt`, `TaskSchedulerTool.kt`, `TaskManagerTool.kt`

### 4.6 UI & Accessibility Tools

| Tool Name | Description |
|-----------|-------------|
| `ui_automation` | Tap, swipe, type via accessibility service |
| `semantic_ui` | Read semantic UI tree, find elements by description |
| `visual_inspector` | Screenshot-based visual element inspection |
| `autofill_assist` | Fill focused fields via IME/accessibility. Actions: save_profile, get_profile, fill_focused, status, open_autofill_settings |

**Files:** `UIAutomationTool.kt`, `SemanticUITool.kt`, `SemanticTreeParser.kt`, `VisualInspectorTool.kt`, `AutofillAssistTool.kt`, `OmniAccessibilityService.kt`, `GodModeAccessibility.kt`, `AccessibilityStateManager.kt`

### 4.7 Development & Code Tools

| Tool Name | Description |
|-----------|-------------|
| `git_manager` | Git init/add/commit/push/pull/log/diff/branch |
| `github_manager` | GitHub API: repos, issues, PRs, actions |
| `request_github_auth` | GitHub OAuth device-flow for agent auth |
| `analyze_logcat` | Parse and filter logcat output |
| `app_manifest_analyzer` | Analyze AndroidManifest.xml structure |
| `enhanced_manifest_analyzer` | Deep APK: SHA hashes, native libs, metadata |
| `enhanced_intent_resolver` | Resolve intents across activities/services/receivers |
| `enhanced_cached_analysis` | Get cached enhanced manifest analysis |
| `enhanced_manifest_to_html` | Export manifest analysis as HTML report |
| `quality_security_tool` | Code quality and security checks |
| `execution_diagnostics` | Diagnose tool execution failures |
| `god_eye_profiler` | System-wide performance profiling |

**Files:** `GitManagerTool.kt`, `GitHubManagerTool.kt`, `RequestGitHubAuthenticationTool.kt`, `LogcatAnalyzerTool.kt`, `AppManifestAnalyzerTool.kt`, `EnhancedAppManifestAnalyzerTool.kt`, `QualitySecurityTool.kt`, `OmniExecutionDiagnostics.kt`, `GodEyeProfilerTool.kt`

### 4.8 IPC & Extension Tools

| Tool Name | Description |
|-----------|-------------|
| `omni_link` | Bind to external extensions via `IOmniExtensionInterface` AIDL |
| `system_launcher_tool` | Control OmniDev Launcher via `IOmniLauncherInterface` AIDL |
| `widget_generator_tool` | Render Compose widgets on launcher via `renderOmniWidget()` |
| `omni_core_agent` | Expose agent capabilities to external apps via `IOmniCoreInterface` |

**Files:** `OmniLinkTool.kt`, `LauncherControlTool.kt`, `WidgetGeneratorTool.kt`, `OmniCoreAgentTool.kt`, `ExtensionConnectionManager.kt`, `LauncherConnectionManager.kt`

### 4.9 New Intelligence & Future Tools *(Added in futures branch)*

| Tool Name | Description |
|-----------|-------------|
| `predictive_analytics` | Time-series forecasting (forecast, detect_anomalies, analyze_trend) via `PredictiveAnalyticsEngine` |
| `security_analyzer` | Advanced APK security analysis (analyze, scan_all, quick_scan) via `AdvancedSecurityAnalyzer` |
| `intelligent_automation` | Workflow automation engine (execute_workflow, list_workflows, get_statistics, get_patterns) via `IntelligentAutomationEngine` |
| `voice_commands` | Voice command engine (initialize, start_listening, stop_listening, speak, get_stats, get_history) via `AdvancedVoiceCommandEngine` |
| `tool_monitoring` | Real-time tool performance metrics (get_metrics, get_all_metrics, most_used, slowest, most_failed) via `ToolMonitoringSystem` |

**Internal infrastructure (not exposed as tools):**
- `ToolOrchestrator` — circuit-breaker + TTL caching wrapper for tool execution
- `ToolDependencyGraph` — dependency resolution for tool chains
- `ToolMachineLearningEngine` — learns usage patterns and predicts next tools (Naive Bayes, KNN, Decision Tree, Neural Network)
- `ToolIntelligenceEngine` — Q-Learning-based tool scorer + pattern detector

---

## 5. Intelligence & Automation Engines

All located under `data/tools/*/`:

### ToolMachineLearningEngine (`data/tools/ml/`)
Trains multiple ML models on tool execution history. Records each execution (tool name, success, latency, parameters), updates statistics, and predicts the next best tool using Naive Bayes, KNN, Decision Tree, and feedforward Neural Network ensemble. Used internally to improve tool suggestions.

### ToolIntelligenceEngine (`data/tools/orchestration/`)
Q-Learning-based reinforcement engine. Maintains Q-values for each tool, decays exploration rate over time, tracks contextual preferences (time of day, day of week, battery level), and detects recurring usage patterns.

### ToolOrchestrator (`data/tools/orchestration/`)
Infrastructure layer providing TTL-based result caching and circuit-breaker protection around tool calls. Does not expose agent tools.

### ToolDependencyGraph (`data/tools/orchestration/`)
Resolves tool dependency chains and enables topological-sort execution of dependent tool sequences.

### PredictiveAnalyticsEngine (`data/tools/prediction/`)
Time-series analytics engine. Exposed as `predictive_analytics` tool. Supports: ARIMA-like forecasting, anomaly detection via statistical thresholds, and trend analysis (RISING/FALLING/STABLE).

### ToolMonitoringSystem (`data/tools/monitoring/`)
Singleton real-time metrics collector. Tracks execution count, success rate, average/p99 latency, and failure count per tool. Emits `MonitoringEvent` via `SharedFlow`. Exposed as `tool_monitoring` tool.

### IntelligentAutomationEngine (`data/tools/automation/`)
Workflow automation system. Supports complex workflows with: sequential/parallel actions, conditional branches, loops, API calls, tool calls, wait steps, and custom scripts. Learns user patterns and adapts. Exposed as `intelligent_automation` tool.

### AdvancedSecurityAnalyzer (`data/tools/security/`)
Deep static + dynamic security analysis for Android packages. Analyses permissions, native libraries, network configuration, cryptographic practices, component exposure, malware indicators, and generates risk scores. Exposed as `security_analyzer` tool.

### AdvancedVoiceCommandEngine (`data/tools/voice/`)
Full voice command pipeline: SpeechRecognizer integration, wake-word detection (`omnidev`, `أومني ديف`), NLP command matching, Text-to-Speech feedback, command history, and multi-language support (Arabic/English). Exposed as `voice_commands` tool.

---

## 6. IPC & System Integration

### 6.1 AIDL Interfaces

| File | Package | Purpose |
|------|---------|---------|
| `ipc/IOmniCoreInterface.aidl` | `com.omnidev.workspace.ipc` | **Active** — 4-method interface used by `OmniCoreService` for launcher/companion IPC |
| `ipc/IOmniResponseCallback.aidl` | `com.omnidev.workspace.ipc` | Streaming callback for `streamAgentResponse()` |
| `extension/ipc/IOmniExtensionInterface.aidl` | `com.omnidev.extension.ipc` | Extension binding (Omni-Link) |
| `launcher/ipc/IOmniLauncherInterface.aidl` | `com.omnidev.launcher.ipc` | Launcher control + widget rendering |

> The legacy root `com.omnidev.workspace.IOmniCoreInterface.aidl` was removed — it has been replaced by the `ipc/` package version used by `OmniCoreService`.

### 6.2 IPC Services & Managers

| Class | Role |
|-------|------|
| `OmniCoreService` | Exposes `IOmniCoreInterface` binder. Enforces `CONTROL_CORE` permission on every method |
| `OmniCoreAgentTool` | Tool wrapper that calls into `OmniCoreService` from the agent |
| `LauncherConnectionManager` | Binds to OmniDev Launcher via `IOmniLauncherInterface`; exposes `renderOmniWidget()` |
| `LauncherCommandRouter` | Routes launcher commands to the correct handler |
| `ExtensionConnectionManager` | Discovers and binds Omni-Link extensions via `com.omnidev.action.BIND_EXTENSION` |
| `PrivilegedExecutionManager` | Routes privileged shell commands through Shizuku or root fallback |
| `RishShellManager` | Ish/Rish shell integration for root commands |

### 6.3 Shizuku Integration
- `ShizukuCommandTool` — wraps `Shizuku.newProcess()` for ADB-level command execution
- `PrivilegedExecutionManager.executeCommand()` gates on `ShizukuCommandTool.isAvailable()` alone (not `isShizukuReady()`) to avoid binder-flicker race

---

## 7. Multi-Modal Capabilities — Eyes, Ears, Voice

| Capability | Service / Class | Notes |
|-----------|-----------------|-------|
| Screen capture | `OmniScreenCaptureService` | MediaProjection-based screenshot |
| Accessibility tree | `OmniAccessibilityService`, `SemanticTreeParser` | Walks `AccessibilityNodeInfo` tree |
| UI automation | `UIAutomationTool`, `GodModeAccessibility` | Tap/swipe/type via a11y |
| Visual inspection | `VisualInspectorTool` | Screenshot + element overlay |
| Speech recognition | `AdvancedVoiceCommandEngine` | SpeechRecognizer + wake word |
| Text-to-speech | `AdvancedVoiceCommandEngine` | TTS with Arabic/English support |
| Voice assistant | `VoiceAssistantService`, `VoiceManager` | Background voice service |
| IME integration | `OmniInputMethodService` | Custom keyboard with agent text injection |
| Floating overlay | `OmniBubbleService` | Always-on-top agent bubble |
| Media session | `OmniMediaSessionService` | MediaSession control for playback |

---

## 8. Persistence Layer

### 8.1 Room Database (SQLite)
**File:** `data/db/OmniDevDatabase.kt`
- **Current version:** 6
- **Migration 5→6:** Added `consoleEntriesJson TEXT NOT NULL DEFAULT ''` to `chat_messages`

| Entity | Table | DAO |
|--------|-------|-----|
| `ChatMessageEntity` | `chat_messages` | `ChatMessageDao` |
| `ChatSessionEntity` | `chat_sessions` | `ChatSessionDao` |
| `KnowledgeSnippet` | `knowledge` | `KnowledgeDao` |

### 8.2 DataStore (encrypted preferences)
| Repository | Keys Stored |
|-----------|-------------|
| `SettingsRepository` | System prompt, model selection, temperature, max tokens, user profile (email, phone, address for autofill) |
| `ApiKeyRepository` | API keys for all providers (OpenAI, Anthropic, Gemini, Groq, etc.) |
| `AnalyticsRepository` | Usage analytics, telemetry preferences |

### 8.3 Chat Repository
**File:** `data/repository/ChatRepository.kt`
Combines Room (messages + sessions) with DataStore. Used by `ChatViewModel`.

### 8.4 AgentConsoleSerializer
**File:** `ui/chat/AgentConsoleSerializer.kt`
Serializes `List<AgentConsoleEntry>` to/from JSON using `org.json` for storage in `consoleEntriesJson`.

---

## 9. UI Layer

All screens are Jetpack Compose. Navigation is handled by `AppNavigation.kt`.

| Screen | ViewModel | Route |
|--------|-----------|-------|
| `ChatScreen` | `ChatViewModel` | Main agent chat |
| `DebugScreen` | `DebugViewModel` | Real-time logcat + crash viewer |
| `AISettingsScreen` | `AISettingsViewModel` | Model, temperature, system prompt |
| `ProvidersScreen` | `ProvidersViewModel` | API key management per provider |
| `IntegrationsScreen` | — | Telegram/Discord/Slack/n8n config |
| `LocalModelManagerScreen` | — | Download/manage local GGUF models |
| `MemoryExplorerScreen` | — | Browse/edit knowledge base entries |
| `ScheduledTasksScreen` | — | View/manage scheduled agent tasks |
| `SystemPromptEditorScreen` | — | Edit system prompt with templates |
| `ToolRegistryScreen` | — | Live tool list with descriptions |
| `UserProfileScreen` | — | User profile (name, email, phone, address) |
| `AnalyticsDashboardScreen` | `AnalyticsDashboardViewModel` | Usage charts and session analytics |

**Components:**
- `AgentLiveConsole` — real-time streaming console with tool call/result accordion
- `MarkdownText` — Compose Markdown renderer
- `MessageFormatter` — format raw LLM output to display messages
- `ConfirmationGate` — approval dialog for destructive actions
- `OmniBubbleService` — floating chat bubble (overlay window)

---

## 10. System Services & Receivers

| Component | Type | Purpose |
|-----------|------|---------|
| `OmniAccessibilityService` | AccessibilityService | UI tree + input injection |
| `OmniInputMethodService` | InputMethodService | Custom IME for text injection |
| `VoiceAssistantService` | Service | Background voice listener |
| `OmniDevVpnService` | VpnService | TUN-based traffic monitor |
| `OmniCoreService` | Service | AIDL IPC for companion apps |
| `OmniSyncService` | Service | Background sync / task runner |
| `AgentNotificationService` | Service | Foreground notification for agent runs |
| `OmniMediaSessionService` | Service | Media session control |
| `OmniScreenCaptureService` | Service | MediaProjection screen capture |
| `OmniBubbleService` | Service | Always-on-top floating UI overlay |
| `DiscordPollingService` | Service | Long-polls Discord for new messages |
| `TelegramPollingService` | Service | Long-polls Telegram Bot API |
| `WhatsAppBridgeService` | Service | Maintains local WhatsApp bridge connection |
| `BootReceiver` | BroadcastReceiver | Auto-start services on device boot |
| `OmniSmsReceiver` | BroadcastReceiver | Intercept incoming SMS for bridge |
| `OmniDeviceAdminReceiver` | DeviceAdminReceiver | Device admin (lock screen, wipe) |
| `CrashHandler` | — | Uncaught exception handler + crash log |
| `DebugLogManager` | — | In-memory rotating log buffer |

---

## 11. AIDL Interfaces

```
app/src/main/aidl/
├── com/omnidev/workspace/
│   └── ipc/
│       ├── IOmniCoreInterface.aidl     # 4-method launcher/companion IPC
│       └── IOmniResponseCallback.aidl  # Streaming agent response callback
├── com/omnidev/extension/
│   └── ipc/
│       └── IOmniExtensionInterface.aidl  # Omni-Link extension binding
└── com/omnidev/launcher/
    └── ipc/
        └── IOmniLauncherInterface.aidl   # Launcher control + widget rendering
```

**`IOmniCoreInterface` (ipc package) methods:**
- `getSystemStatus(): Int`
- `executeSystemCommand(command, contextData)`
- `askAgentSilent(prompt)`
- `streamAgentResponse(prompt, callback)`

**`IOmniLauncherInterface` methods (partial):** launch app, set wallpaper, get installed apps, `renderOmniWidget(widgetId, composeJson)`

**`IOmniExtensionInterface`:** invoked by `ExtensionConnectionManager` for extensions binding via `com.omnidev.action.BIND_EXTENSION`

---

## 12. Project File Map

```
app/src/main/
├── aidl/com/omnidev/
│   ├── workspace/ipc/             IOmniCoreInterface, IOmniResponseCallback
│   ├── extension/ipc/             IOmniExtensionInterface
│   └── launcher/ipc/              IOmniLauncherInterface
│
├── java/com/omnidev/workspace/
│   ├── OmniDevApp.kt              Application class (Hilt + init)
│   ├── MainActivity.kt            Single Activity + Compose host
│   │
│   ├── data/
│   │   ├── accessibility/         OmniAccessibilityService, SemanticTreeParser, SemanticUITool, GodModeAccessibility
│   │   ├── admin/                 OmniDeviceAdminReceiver
│   │   ├── auth/                  CopilotSessionManager, CopilotModelRefresher, GitHubDeviceFlowManager, OAuthManager
│   │   ├── communication/         OmniSmsReceiver (SMS capture)
│   │   ├── db/                    OmniDevDatabase (v6), DAOs, Entities
│   │   ├── debug/                 CrashHandler, DebugLogManager
│   │   ├── input/                 OmniInputMethodService (IME)
│   │   ├── integration/           DiscordPollingService, TelegramPollingService, WhatsAppBridgeService
│   │   ├── ipc/                   OmniCoreService, OmniCoreAgentTool, LauncherConnectionManager,
│   │   │                          LauncherCommandRouter, ExtensionConnectionManager,
│   │   │                          PrivilegedExecutionManager, RishShellManager
│   │   ├── localllm/              LlamaCppInferenceEngine, LocalInferenceEngine, LocalEngineType
│   │   ├── media/                 OmniMediaSessionService
│   │   ├── model/                 AIModel, CompletionRequest, CompletionResponse, ChatMessage
│   │   ├── network/               CompletionService (Retrofit), OmniDevVpnService (TUN)
│   │   ├── repository/            ChatRepository, SettingsRepository, ApiKeyRepository, AnalyticsRepository
│   │   ├── sync/                  OmniSyncService
│   │   ├── system/                BootReceiver
│   │   ├── tools/
│   │   │   ├── ToolManager.kt           Interface + ToolExecutionResult + ToolDefinition
│   │   │   ├── CompositeToolManager.kt  Main tool router (70+ tools)
│   │   │   ├── FileToolManager.kt       File/terminal tools
│   │   │   ├── MemoryManager.kt         SQLite knowledge base
│   │   │   ├── VectorMemoryManager.kt   Embedding vector store
│   │   │   ├── SystemAssistantTools.kt  Communication, Planner, Hardware, Location, Device, AppManager
│   │   │   ├── AdvancedSystemTools.kt   Root shell, package installer, settings, call log, SMS
│   │   │   ├── [All individual tool files...]
│   │   │   ├── automation/         IntelligentAutomationEngine
│   │   │   ├── ml/                 ToolMachineLearningEngine
│   │   │   ├── monitoring/         ToolMonitoringSystem
│   │   │   ├── orchestration/      ToolOrchestrator, ToolDependencyGraph, ToolIntelligenceEngine
│   │   │   ├── prediction/         PredictiveAnalyticsEngine
│   │   │   ├── security/           AdvancedSecurityAnalyzer
│   │   │   └── voice/              AdvancedVoiceCommandEngine
│   │   ├── vision/                OmniScreenCaptureService
│   │   └── voice/                 VoiceAssistantService, VoiceManager
│   │
│   ├── domain/
│   │   ├── attachment/            AttachmentProcessor (image/file ingestion)
│   │   └── engine/                AgentPipeline, SwarmOrchestrator, IntentClassifier, OmniMode, AutoHealBuildUseCase
│   │
│   ├── registry/
│   │   └── ModelRegistry.kt       All AI models + routing logic
│   │
│   └── ui/
│       ├── analytics/             AnalyticsDashboardScreen + ViewModel
│       ├── chat/                  ChatScreen, ChatViewModel, AgentLiveConsole, MessageFormatter, ConfirmationGate, AgentConsoleEntry, AgentConsoleSerializer, MarkdownText
│       ├── debug/                 DebugScreen + ViewModel
│       ├── navigation/            AppNavigation
│       ├── overlay/               OmniBubbleService
│       ├── providers/             ProvidersScreen + ViewModel
│       ├── settings/              AISettingsScreen, AISettingsViewModel, IntegrationsScreen, LocalModelManagerScreen, MemoryExplorerScreen, ScheduledTasksScreen, SystemPromptEditorScreen, ToolRegistryScreen, UserProfileScreen
│       └── theme/                 Color, Theme, Type
│
└── res/
    ├── drawable/                  Vector icons and drawables
    ├── mipmap-*/                  App launcher icons
    ├── values/                    strings.xml, colors.xml, styles.xml
    └── xml/                       Accessibility service config, IME config, network security config, device admin config
```

---

## 13. Tech Stack & Dependencies

| Category | Technology |
|----------|------------|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose + Material3 |
| DI | Hilt (Dagger) |
| Async | Kotlin Coroutines + Flow |
| Local DB | Room (SQLite) |
| Preferences | AndroidX DataStore |
| HTTP | Retrofit 2 + OkHttp |
| Serialization | kotlinx.serialization |
| Local LLM | Llama.cpp (JNI, GGUF models) — NDK 27.0.12077973 |
| Privileged Exec | Shizuku + Root fallback |
| Build | Gradle 8.9, AGP, KSP |
| Min SDK | 24 (Android 7) |
| Target SDK | 35 (Android 15) |

---

## 14. Getting Started & CI/CD

### Build

```bash
# Debug APK
./gradlew assembleDebug

# Lint
./gradlew lint

# Unit tests
./gradlew test
```

### CI Pipeline (`.github/workflows/android-ci.yml`)
- Triggers on push to `main` and all PRs
- Steps: checkout → JDK 17 → Android SDK → NDK → cache restore → `./gradlew lint` → build

### Setup for Privileged Features

See `SYSTEM_SETUP.md` for full ADB grant commands. Key grants for `com.omnidev.workspace`:
```bash
adb shell pm grant com.omnidev.workspace android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.omnidev.workspace android.permission.DUMP
adb shell appops set com.omnidev.workspace SYSTEM_ALERT_WINDOW allow
```

---

## 15. Android Permissions Reference

The app declares protected/system permissions with `tools:ignore="ProtectedPermissions"`. These require ADB grant or Shizuku:

| Permission | Purpose |
|------------|---------|
| `WRITE_SECURE_SETTINGS` | Modify system settings |
| `DUMP` | Read system service dumps |
| `BIND_ACCESSIBILITY_SERVICE` | Accessibility service binding |
| `BIND_INPUT_METHOD` | IME binding |
| `RECEIVE_SMS` | SMS capture for bridge |
| `FOREGROUND_SERVICE_*` | Multiple foreground service types |
| `SYSTEM_ALERT_WINDOW` | Floating overlay |
| `android:permission="CONTROL_CORE"` | Guard on `OmniCoreService` binder |
| `android:permission="CONTROL_LAUNCHER"` | Guard on `IOmniLauncherInterface` |

> **Do NOT** add `android.permission.BIND_VPN_SERVICE` to `<uses-permission>`. It is only declared as `android:permission` on the `OmniDevVpnService` `<service>` entry.

---

## 16. Architecture Conventions & Agent Rules

1. **Tool return type** — Always return `ToolExecutionResult(output: String, isError: Boolean = false)`. Never use `result.success` — use `!result.isError`.

2. **Tool registration** — Every new tool **must** be registered in both `getToolDefinitions()` and `executeTool()` in `CompositeToolManager`. Infrastructure engines (ToolOrchestrator, ToolDependencyGraph, ToolMachineLearningEngine, ToolIntelligenceEngine) are internal-only and do NOT get tool registrations.

3. **AIDL** — Only two AIDL packages are active: `com.omnidev.workspace.ipc` (core) and `com.omnidev.launcher.ipc` (launcher). The root `com.omnidev.workspace` package AIDL was removed.

4. **Shizuku** — Gate on `ShizukuCommandTool.isAvailable()` only (not `isShizukuReady()`) inside `PrivilegedExecutionManager` to avoid binder-flicker race.

5. **Lint / NewAPI** — Any `queryIntentServices()` call targeting API ≥ 33 must be directly inside an `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)` branch.

6. **VpnService** — `BIND_VPN_SERVICE` is `android:permission` on the `<service>` entry only, never in `<uses-permission>`.

7. **DB migrations** — Always increment DB version in `OmniDevDatabase` and add a named migration object. Current version: 6.

8. **Context propagation** — Pass `Context` through `CompositeToolManager` constructor, not statically. Tools that need Context are conditional: `if (context != null)`.

9. **Coroutines** — All tool `execute()` methods use `withContext(Dispatchers.IO)` or `Dispatchers.Default`. Never call blocking I/O on the main thread.

10. **New engines in `data/tools/*/`** — Each subdirectory engine (ml, monitoring, orchestration, prediction, security, voice, automation) has a single responsibility. Engines with a public `execute(action, args)` API are exposed as agent tools. Internal/infrastructure engines are not.
