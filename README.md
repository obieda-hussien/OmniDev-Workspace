# OmniDev Workspace — Master System Blueprint

[![Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/workflows/android-ci.yml/badge.svg)](https://github.com/obieda-hussien/OmniDev-Workspace/actions/workflows/android-ci.yml)

> **AI AGENT CONTEXT MAP** — This document is the supreme authoritative reference for every LLM Agent, Copilot Session, and human developer working on this project.
> Read this first — it maps every file, flow, pattern, and architectural decision in the project in a single read.
>
> **Package:** `com.omnidev.workspace` · **Min SDK:** 24 · **Target SDK:** 35 · **NDK:** 27.0.12077973 · **Language:** Kotlin 2.0 · **UI:** Jetpack Compose + Material 3

---

## 📊 Project Statistics & Metrics

| Metric | Value |
|---------|--------|
| **Total Kotlin Files** | 180+ files |
| **Total Lines of Code** | 75,000+ lines (60,000+ pure code) |
| **Active Tools** | 70+ AI tools |
| **Active Services** | 10 background/foreground services |
| **AIDL Interfaces** | 5 interfaces |
| **Database Version** | v7 (Agent Brain integration) |
| **Min SDK** | API 24 (Android 7.0) |
| **Target SDK** | API 35 (Android 15) |
| **Primary Language** | Kotlin 2.0.21 |
| **UI Framework** | Jetpack Compose + Material 3 |
| **Product Flavors** | `lite` / `norm` / `pro` / `oem` / `admin` (5-tier architecture) |

---

## 🎛️ 5-Tier Flavor Architecture (The Business Engine)

The project ships five distinct product flavors on the `tier` Gradle dimension. Each flavor represents a specific business tier with its own `applicationId`, manifest overlay, `TierPolicy`, and runtime capability gating.

| Flavor  | `applicationId`                  | Target Audience                               | Key Capabilities                                                                                                                                                            | Execution Gate Behavior           |
|---------|----------------------------------|-----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------|
| `lite`  | `com.omnidev.workspace`          | B2C Free / Google Play                        | Web browsing (`web_search`, `web_search_deep`, `web_scraper`, `scrape_multiple`) + `read_file` + **Persistent Memory & Vector Knowledge Base tools** (`remember_fact`, `search_knowledge`, `update_memory`, `delete_memory`, `vector_store`, `vector_search`, `vector_similar`). Fully scrubbed manifest. | Deny-all (Strict)                 |
| `norm`  | `com.omnidev.workspace.norm`     | B2C Basic / Devs                              | Accessibility UI + terminal + Git + App management. No root/Shizuku.                                                                                                        | User Prompts (Dialog)             |
| `pro`   | `com.omnidev.workspace.pro`      | B2C Premium / Hackers                         | Full God-Mode: Shizuku + Root + Deep Pentesting + Swarm Orchestration.                                                                                                      | User Prompts (Dialog)             |
| `oem`   | `com.omnidev.workspace.oem`      | B2B Partners / ROMs                           | `android.uid.system` + offline local Llama.cpp. Device wipe restricted.                                                                                                     | **Zero-click** (Auto-approves)    |
| `admin` | `com.omnidev.workspace.admin`    | 🔑 **Lead Developer ONLY** (Master Key Build) | **Every capability enabled.** Shizuku + Root + Accessibility + Deep Pentesting + Device Admin Wipe + Local SLM (full ABI set, CMake) + System Integration hooks. Used exclusively for rapid end-to-end QA. Never distributed. | **Zero-click** (Auto-approves, audit-logged) |

Build a specific flavor via terminal:
```bash
./gradlew :app:assembleLiteDebug     # Play-Store-safe restricted variant (now with Memory + Vector tools)
./gradlew :app:assembleNormDebug     # Standard developer variant
./gradlew :app:assembleProDebug      # Full god-mode power user variant
./gradlew :app:assembleOemDebug      # OEM / custom ROM integration variant
./gradlew :app:assembleAdminDebug    # 🔑 Master-key developer testing build (ALL capabilities)

```
The policy layer resides in com.omnidev.workspace.core.policy (TierPolicy.kt, ConfirmationGate.kt, OmniAuditLog.kt). Privileged execution is safely abstracted behind com.omnidev.workspace.core.privileged.PrivilegedExecutionFacade to prevent dead-code linking in restricted flavors. The five `TierPolicy` implementations live in `src/lite/`, `src/norm/`, `src/pro/`, `src/oem/`, and `src/admin/` respectively — Gradle guarantees exactly one is on the classpath per build variant.
## Table of Contents
 1. Architecture Overview
 2. Agent Brain System
 3. Core Execution Flows
 4. Model Routing & Registry
 5. Tool System — Complete Directory
 6. Intelligence & Automation Engines
 7. IPC & System Integration
 8. Multimodal Capabilities
 9. Persistence Layer
 10. UI Layer
 11. Services & Receivers
 12. AIDL Interfaces
 13. Project File Map
 14. Tech Stack & Libraries
 15. Build & CI/CD
 16. Android Permissions Reference
 17. Architecture Conventions & Agent Rules
 18. Quick Development Guide
 19. Known Issues & Modularization Roadmap
## 1. Architecture Overview
**OmniDev Workspace** is a **God-Mode autonomous software engineer and pentesting OS** for Android. It orchestrates cloud and local models in a multi-agent swarm capable of writing code, running terminal commands, controlling the OS, managing files, browsing the web, patching vulnerabilities, and auto-healing build errors — all autonomously.
```text
┌───────────────────────────────────────────────────────────────────────┐
│                         OmniDev Workspace                             │
│                                                                       │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │
│  │  Shizuku /  │  │  Llama.cpp  │  │    Swarm    │  │ Semantic UI │ │
│  │  Root Shell │  │  On-device  │  │ Multi-Agent │  │ Automation  │ │
│  │  Execution  │  │  Inference  │  │ Orchestrator│  │ & Control   │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘ │
│         └────────────────┴────────────────┴────────────────┘        │
│                                     │                               │
│                          ┌──────────▼──────────┐                    │
│                          │    AgentPipeline    │                    │
│                          │  (ReAct Core Loop)  │                    │
│                          │  CompletionService  │                    │
│                          │  CompositeToolMgr   │                    │
│                          └──────────┬──────────┘                    │
│                                     │                               │
│           ┌────────────┬────────────┼─────────────┬──────────────┐  │
│    ToolManager   MemoryMgr   VectorMemory    Accessibility   VPN   │  │
│    (70+ tools)  (SQLite)    (Cosine sim)     (UI Tree)     (TUN)   │  │
└───────────────────────────────────────────────────────────────────────┘

```
### The Four Core Pillars
| Pillar | Description | Core Files |
|---|---|---|
| **OS-Level Execution** | Shizuku (ADB-level) + root shell + Device Admin for highly privileged operations. | PrivilegedExecutionManager, ShizukuCommandTool, RishShellManager |
| **Local Edge Inference** | Llama.cpp via JNI to run GGUF models locally (100% offline, absolute privacy). | LlamaCppInferenceEngine, LocalInferenceEngine, LocalModelManagerScreen |
| **Multi-Agent Swarm** | Orchestrator decomposes complex tasks; Worker instances execute in parallel. | SwarmOrchestrator, AgentPipeline, IntentClassifier |
| **Semantic UI Engine** | Reads the Android Accessibility tree, allowing the AI to "see" and interact with any app. | SemanticTreeParser, OmniAccessibilityService, SemanticUITool |
## 2. Agent Brain System
> **Integrated: 2026-04-02** — Upgrades the Agent to possess persistent long-term memory and contextual awareness, similar to Claude Code / Copilot Agent.
> 
```text
╔══════════════════════════════════════════════════════════════════╗
║              🧠 AGENT BRAIN SYSTEM                               ║
╚══════════════════════════════════════════════════════════════════╝

data/brain/
├── SmartLearningBridge.kt      ← The Orchestrating Brain (Core Hub)
├── ToolExecutionJournal.kt     ← Permanent Execution Ledger (Long-term memory)
└── ToolAwarenessEngine.kt      ← Environmental & Contextual Awareness Engine

data/db/entities/
├── ToolExecutionEntry.kt       ← Execution Log Entity
└── SystemKnowledgeEntry.kt     ← System Knowledge Base Entity

data/db/dao/
├── ToolExecutionDao.kt         ← DAO for execution journals
└── SystemKnowledgeDao.kt       ← DAO for system knowledge

ui/brain/
├── AgentBrainDashboard.kt      ← Compose UI for Intelligence Analytics
└── AgentBrainViewModel.kt      ← Brain Dashboard ViewModel

```
### System Workflow:
```text
On every Tool Execution ↓
SmartLearningBridge.onToolExecutionEnd()
    ├── ToolExecutionJournal       → Saves outcome to SQLite DB
    ├── ToolAwarenessEngine        → Learns from errors/successes
    ├── ToolIntelligenceEngine     → Updates Q-Learning weights
    ├── ToolMachineLearningEngine  → Retrains predictive ML models
    └── ToolMonitoringSystem       → Updates telemetry & latency metrics

On every Prompt Injection ↓
SmartLearningBridge.buildFullContextEnrichment()
    ├── Environment Status (Termux, Shizuku availability, Git branch, Python)
    ├── Execution Memory (Past errors, discovered patterns)
    ├── Acquired Best Practices
    └── Next-Tool Recommendations

```
## 3. Core Execution Flows
### 3.1 AgentPipeline — The ReAct Loop
**File:** domain/engine/AgentPipeline.kt
The central intelligence engine. Executes the **Reason → Act → Observe** cognitive loop:
 1. Injects context, history, and JSON tool definitions to the target LLM.
 2. Parses the response searching for structured tool calls.
 3. Executes requested tools synchronously/asynchronously via CompositeToolManager.
 4. Appends tool results to the context window and loops back to the LLM.
 5. Terminates upon triggering FINAL_ANSWER, reaching the iteration limit, exhausting the token budget, or timeout.
**Configuration:** AgentConfig handles maxIterations (default 50), tokenBudget, maxExecutionTimeMs, maxIterationTimeMs (3 mins), and maxRepeatToolCalls.
### 3.2 SwarmOrchestrator
**File:** domain/engine/SwarmOrchestrator.kt
Decomposes massive requests into ≤10 sub-tasks, spawns concurrent AgentPipeline coroutines (Workers) for each task, and synthesizes the final output.
### 3.3 IntentClassifier
**File:** domain/engine/IntentClassifier.kt
A lightweight, ultra-fast LLM router that classifies user input into a specific OmniMode before directing it to the appropriate pipeline.
### 3.4 OmniMode — Operational Modes
**File:** domain/engine/OmniMode.kt
```text
FAST          ← Max 5 iterations, optimized model (e.g., gpt-4o-mini)
BALANCED      ← Max 25 iterations, standard model (e.g., gpt-4o)
THOROUGH      ← Max 50 iterations, deep reasoning model (e.g., claude-3-opus)
SWARM         ← Triggers Multi-agent Swarm Orchestrator
AUTONOMOUS    ← Unattended execution + Auto-healing loops
DEBUG         ← Direct logcat streaming via DebugConsoleScreen

```
### 3.5 AutoHealBuildUseCase
**File:** domain/engine/AutoHealBuildUseCase.kt
Executes ./gradlew build, captures compiler errors, and invokes AgentPipeline to autonomously patch the broken files in a continuous retry loop.
### The Complete Execution Architecture
```text
User Input (Chat / Intent)
            ↓
    IntentClassifier  →  Classifies Request Intent
            ↓
    ModelRegistry     →  Selects optimal LLM (Anthropic/OpenAI/Copilot/Llama.cpp)
            ↓
    AgentPipeline ReAct Loop  [up to 50 iterations]
    ├── REASON: Send prompt + tool schemas to LLM
    ├── ACT:    Execute tool calls via CompositeToolManager
    ├── OBSERVE: Append tool outputs to context
    └── LOOP UNTIL: text output | max iterations reached
            ↓
    CompositeToolManager
    ├── Routes to FileToolManager, SystemTools, AdvancedSecurityAnalyzer, etc.
    ├── Executes with timeout + circuit breaker + error handling
    └── Returns ToolCallResult (success state + raw output)
            ↓
    Response Generation
    ├── Streams markdown text to UI in real-time
    ├── Persists to ChatRepository (SQLite)
    └── Updates VectorMemoryManager for future RAG context
            ↓
    Output (UI / Notification / Direct OS Action)

```
## 4. Model Routing & Registry
**File:** registry/ModelRegistry.kt
The central directory for all supported AI models. Exposes APIs such as:
 * getModelById(id) / findModelById(id)
 * getModelsForTier(tier) — Filters by ModelTier (FAST, BALANCED, POWERFUL, LOCAL)
 * getBestModelForTier(tier, preferredProvider?) — Smart routing
 * getDefaultModelForRole(role) — Default fallbacks (ORCHESTRATOR, WORKER, CODER)
 * addDynamicCopilotModels(models) — Injects dynamic Copilot models at runtime.
**Supported Providers:** OpenAI, Anthropic, Google Gemini, Mistral, Groq, Cerebras, GitHub Copilot, Local Edge (Llama.cpp).
**Authentication Managers:**
 * CopilotSessionManager — GitHub OAuth device-flow for Copilot token generation.
 * CopilotModelRefresher — Auto-refreshes dynamic Copilot model lists.
 * GitHubDeviceFlowManager — Generic GitHub OAuth device flow.
 * OAuthManager — Generic OAuth assistant.
 * ApiKeyRepository — Encrypted DataStore for all BYOK (Bring Your Own Key) credentials.
## 5. Tool System — Complete Directory
All tools are strictly registered and routed through CompositeToolManager (data/tools/CompositeToolManager.kt).
### 5.1 File & Terminal Tools (FileToolManager)
| Tool Name | Description |
|---|---|
| read_file | Read raw file contents |
| write_file / create_file | Write or create new files |
| delete_file | Delete specified files |
| search_files | Regex-based search within file contents |
| patch_file | Apply unified diff patches autonomously |
| terminal | Execute standard shell commands |
| grep_search | Blazing fast code search powered by Ripgrep |
| find_files | Search for files matching specific patterns |
| file_permissions | Read/modify UNIX permissions (chmod/chown) |
| disk_usage | Fetch du/df system information |
| archive_tool | Zip/Tar compression and extraction |
### 5.2 Web & Network Tools
| Tool Name | Description |
|---|---|
| web_search | DuckDuckGo/Bing search + deep fetching (parallel, 6k chars/site) |
| web_search_deep | Full DOM extraction for top N search results |
| web_scraper | Single-page HTML extraction and Markdown conversion |
| scrape_multiple | Parallel extraction of ≤8 URLs (12s timeout, 8k chars each) |
| headless_browser | Full browser automation (JavaScript rendering via WebView) |
| network_request | HTTP GET/POST/PUT/DELETE with custom headers |
| network_monitor | VPN TUN interceptor to monitor app traffic (No root required) |
### 5.3 Memory & Knowledge Tools
| Tool Name | Description |
|---|---|
| remember_fact | Store a persistent fact in the SQLite DB |
| search_knowledge | Semantic search across the knowledge base |
| update_memory | Modify an existing memory entry |
| delete_memory | Delete a memory entry |
| vector_store | Generate and store vector embeddings |
| vector_search | Approximate Nearest Neighbor (ANN) search |
| vector_similar | Find similar entries via cosine similarity |
### 5.4 Communication & Social Media Tools
| Tool Name | Description |
|---|---|
| communicate_tool | Send SMS, Email, and trigger system notifications |
| telegram_publisher | Publish messages/media to Telegram Channels |
| telegram_bot | Full Telegram Bot API integration (receive/send/listen) |
| discord_bot | Full Discord Bot API integration |
| discord_publisher | Publish rich embedded messages via Discord Webhooks |
| slack_tool | Slack Web API integration |
| send_grid_email | Send formatted emails via SendGrid API |
| notion_publisher | Create/update Notion databases and pages |
| n8n_automation | Trigger external n8n workflows via Webhooks |
| whatsapp | WhatsApp Cloud API integration |
| whatsapp_bridge | Local node-based bridge for direct WhatsApp control |
| social_media_video | Download/search media via yt-dlp + noembed |
### 5.5 OS & System Control Tools
| Tool Name | Description |
|---|---|
| hardware_toggle_tool | Toggle WiFi, Bluetooth, Airplane Mode, Flashlight |
| system_power | Trigger Reboot, Shutdown, or Recovery Mode |
| get_device_info | Fetch Build config, RAM, Storage, Battery, CPU specs |
| get_current_location | Fetch precise GPS + Network coordinates |
| app_manager_tool | Install, uninstall, launch, or force-stop applications |
| permission_manager | Grant or revoke Android permissions programmatically |
| device_admin | Execute Device Admin actions (Lock, Wipe - if allowed by Tier) |
| android_intent | Fire arbitrary Android Intents |
| shizuku_command | Execute ADB-level shell commands via Shizuku |
| agent_runtime | Run python, node, bash, or pip install natively |
| agent_sandbox | Secure, isolated execution environment for untrusted code |
| advanced_terminal | Extended terminal with persistent environment variables |
| task_scheduler | Schedule recurring or one-off autonomous background tasks |
| system_contacts | Read, search, and manage device contacts |
| clipboard | Read from or write to the system clipboard |
| sms_reader | Read and parse SMS inboxes |
| call_log | Read call history |
| media_control | Control system media playback (Play, Pause, Skip) |
| ime_tool | Control the custom OmniDev Input Method Engine |
### 5.6 UI & Accessibility Tools
| Tool Name | Description |
|---|---|
| ui_automation | Click, swipe, and type using the Accessibility Service |
| semantic_ui | Parse and map the Android Semantic UI Accessibility Tree |
| visual_inspector | Take a screenshot, overlay bounding boxes, and analyze visually |
| autofill_assist | Autofill forms using the custom IME/Accessibility |
### 5.7 Development, Security & Pentesting Tools
| Tool Name | Description |
|---|---|
| git_manager | Execute Git init, add, commit, push, pull, log, diff, branch |
| github_manager | GitHub API: Manage repos, issues, PRs, actions |
| request_github_auth | Trigger GitHub OAuth device-flow authentication |
| analyze_logcat | Deep analysis and filtering of Android Logcat |
| app_manifest_analyzer | Static analysis of AndroidManifest.xml files |
| enhanced_manifest_analyzer | Deep APK parsing: SHA hashes, native libs, metadata |
| quality_security_tool | Static code quality and security scanning |
| execution_diagnostics | Diagnose failures in tool executions |
| god_eye_profiler | Comprehensive system-wide performance profiling |
| advanced_security_analyzer | Deep static/dynamic APK security analysis |
| vuln_research_toolchain | Automated vulnerability discovery engine |
### 5.8 IPC & Extension Tools
| Tool Name | Description |
|---|---|
| omni_link | Connect to third-party extensions via IOmniExtensionInterface AIDL |
| system_launcher_tool | Control the OmniDev Custom Launcher |
| widget_generator_tool | Dynamically render Jetpack Compose widgets on the Launcher |
| omni_core_agent | Expose agent capabilities to external partner applications |
### 5.9 Advanced Intelligence Tools
| Tool Name | Description |
|---|---|
| predictive_analytics | Time-series forecasting and anomaly detection |
| intelligent_automation | Complex workflow orchestration engine |
| tool_monitoring | Fetch real-time tool performance metrics |

### 5.10 Trust & Scripting Tools (Phase 2)
| Tool Name | Tier | Description |
|---|---|---|
| get_trust_profile | Lite+ | Returns current agent trust score, level, recent history, and earned capabilities |
| reset_trust | Norm+ | Resets the trust profile to initial state (requires `confirm=true`) |
| list_earned_capabilities | Lite+ | Lists all capabilities the agent has unlocked through sustained performance |
| run_script | Norm+ | Executes JS/Python/shell scripts via ScriptEngineManager, SafeExpressionParser, or Termux |
| eval_expression | Lite+ | Safely evaluates a math/logic expression without `eval()` using a recursive-descent parser |
## 6. Intelligence & Automation Engines
Located in data/tools/*/, these are the underlying infrastructures powering the tools:
### ToolMachineLearningEngine (data/tools/ml/)
Trains multiple local ML models on the history of tool executions. It predicts the optimal "next tool" using Naive Bayes, KNN, Decision Trees, and a lightweight Neural Network.
### ToolIntelligenceEngine (data/tools/orchestration/)
A reinforcement learning engine based on Q-Learning. Maintains Q-values for each tool and tracks contextual preferences (e.g., time of day, battery level).
### ToolOrchestrator (data/tools/orchestration/)
Infrastructure layer providing TTL-based result caching, debouncing, and circuit-breaker protection around tool invocations to prevent crash loops.
### PredictiveAnalyticsEngine (data/tools/prediction/)
Time-series analytics engine. Supports: ARIMA-like forecasting, statistical anomaly detection, and trend analysis based on execution logs.
### ToolMonitoringSystem (data/tools/monitoring/)
Real-time singleton metrics aggregator. Tracks execution counts, success rates, and p99 latency for every tool in the system.
### IntelligentAutomationEngine (data/tools/automation/)
Workflow orchestration system. Supports sequential/parallel executions, conditional branching, loop iterations, and nested API calls.
### AdvancedSecurityAnalyzer & VulnResearchToolchain (data/tools/security/)
Deep static and dynamic security analysis for Android packages. Parses Android permissions, native shared libraries (.so), network security configurations, and cryptographic practices to autonomously discover vulnerabilities.
## 7. IPC & System Integration
### 7.1 AIDL Interfaces
| File | Package | Purpose |
|---|---|---|
| ipc/IOmniCoreInterface.aidl | com.omnidev.workspace.ipc | **Active** — 4-method interface for OmniCoreService |
| ipc/IOmniResponseCallback.aidl | com.omnidev.workspace.ipc | Streaming callback for agent responses |
| extension/ipc/IOmniExtensionInterface.aidl | com.omnidev.extension.ipc | Omni-Link extension binding |
| launcher/ipc/IOmniLauncherInterface.aidl | com.omnidev.launcher.ipc | Launcher & dynamic widget control |
### 7.2 IPC Services & Managers
| Component | Role |
|---|---|
| OmniCoreService | Exposes the IOmniCoreInterface binder. Enforces the CONTROL_CORE permission. |
| OmniCoreAgentTool | Tool wrapper that invokes OmniCoreService from within the agent pipeline. |
| LauncherConnectionManager | Binds to OmniDev Launcher via IOmniLauncherInterface. |
| ExtensionConnectionManager | Discovers and binds third-party Omni-Link extensions. |
| PrivilegedExecutionManager | Routes privileged shell commands dynamically through Shizuku or direct Root (su). |
| RishShellManager | Integrates with rish/ish for isolated shell environments. |
### 7.3 Shizuku Integration
 * ShizukuCommandTool — Wraps Shizuku.newProcess() for ADB-level command execution.
 * PrivilegedExecutionManager.executeCommand() gates strictly on ShizukuCommandTool.isAvailable().
> **Critical Rule:** Shizuku APIs must be invoked directly from the compiled classpath, never via reflection, to maintain security and stability.
> 
## 8. Multimodal Capabilities
| Capability | Service / Class | Notes |
|---|---|---|
| Screen Capture | OmniScreenCaptureService | Utilizes Android MediaProjection API. |
| Accessibility Tree | OmniAccessibilityService, SemanticTreeParser | Walks AccessibilityNodeInfo to generate DOM-like structures. |
| UI Automation | UIAutomationTool, GodModeAccessibility | Injects clicks, swipes, and text globally. |
| Visual Inspection | VisualInspectorTool | Combines screenshot capture with UI element overlay generation. |
| Custom Keyboard | OmniInputMethodService | System-level custom Input Method Engine (IME). |
| Media Session | OmniMediaSessionService | Controls system-wide media playback. |
## 9. Persistence Layer
### 9.1 Room Database (SQLite)
**File:** data/db/OmniDevDatabase.kt — **Current Version: 7**
| Entity | Table Name | DAO |
|---|---|---|
| ChatMessageEntity | chat_messages | ChatMessageDao |
| ChatSessionEntity | chat_sessions | ChatSessionDao |
| KnowledgeSnippet | knowledge | KnowledgeDao |
| ToolExecutionEntry | tool_execution_log | ToolExecutionDao |
| SystemKnowledgeEntry | system_knowledge | SystemKnowledgeDao |
### 9.2 DataStore (Encrypted Preferences)
| Repository | Stored Data |
|---|---|
| SettingsRepository | Active model selection, temperature, max tokens, user profile metadata. |
| ApiKeyRepository | Securely encrypted API keys for all providers (OpenAI, Anthropic, Gemini, Groq, etc.). |
| AnalyticsRepository | Local telemetry and usage analytics. |
### 9.3 Database Migration History
```text
v1 → v2: Base structure implementation
v2 → v3: Added Knowledge snippets
v3 → v4: Session enhancements
v4 → v5: Added consoleEntriesJson for live logging
v5 → v6: Added attachment metadata support
v6 → v7: Added Agent Brain (tool_execution_log + system_knowledge)

```
## 10. UI Layer
All screens are built entirely in Jetpack Compose. Navigation is handled via AppNavigation.kt.
| Screen | ViewModel | Purpose |
|---|---|---|
| ChatScreen | ChatViewModel | Primary chat interface and Agent interaction. |
| DebugScreen | DebugViewModel | Live logcat streaming + crash reporting. |
| AISettingsScreen | AISettingsViewModel | Model selection, temperature, and advanced routing. |
| ProvidersScreen | ProvidersViewModel | API Key management (BYOK). |
| IntegrationsScreen | — | Settings for Telegram/Discord/Slack/n8n webhooks. |
| LocalModelManagerScreen | — | Download and manage local GGUF models for Llama.cpp. |
| MemoryExplorerScreen | — | Browse, edit, and search the vector knowledge base. |
| ScheduledTasksScreen | — | View and manage scheduled background tasks. |
| ToolRegistryScreen | — | Live directory of all available tools and descriptions. |
| UserProfileScreen | — | User profile (Name, Email, Phone, Address). |
| AnalyticsDashboardScreen | AnalyticsDashboardViewModel | Usage charts, token costs, and session analytics. |
| AgentBrainDashboard | AgentBrainViewModel | Brain dashboard showing intelligence metrics and memory. |
**Core UI Components:**
 * AgentLiveConsole — Real-time streaming console with accordions for raw tool inputs/outputs.
 * MarkdownText — Custom Markdown renderer for Jetpack Compose.
 * MessageFormatter — Formats raw LLM outputs.
 * ConfirmationGate — High-security consent dialog for destructive actions (Delete, Wipe, Execute).
## 11. Services & Receivers
| Component | Type | Purpose | Status |
|---|---|---|---|
| OmniAccessibilityService | AccessibilityService | UI Tree parsing + Input injection | 🟢 Core |
| OmniInputMethodService | InputMethodService | Custom IME Keyboard | 🟢 Core |
| OmniDevVpnService | VpnService | Local TUN traffic monitor | 🟢 Core |
| OmniCoreService | Service | AIDL IPC for companion apps | 🟢 Core |
| OmniSyncService | Service | Background sync / Task runner | 🟢 Active |
| AgentNotificationService | Service | Foreground notification for Agent execution | 🟢 Active |
| OmniMediaSessionService | Service | Media session controller | 🟢 Active |
| OmniScreenCaptureService | Service | MediaProjection screen capturer | 🟢 Active |
| DiscordPollingService | Service | Long-polls Discord API | 🟢 Active |
| TelegramPollingService | Service | Long-polls Telegram Bot API | 🟢 Active |
| WhatsAppBridgeService | Service | Maintains local bridge connection | 🟢 Active |
| BootReceiver | BroadcastReceiver | Auto-starts services on device boot | 🟢 Active |
| OmniSmsReceiver | BroadcastReceiver | Intercepts incoming SMS | 🟢 Active |
| OmniDeviceAdminReceiver | DeviceAdminReceiver | Device Administration actions | 🟢 Active |
## 12. AIDL Interfaces
```text
app/src/main/aidl/
├── com/omnidev/workspace/
│   └── ipc/
│       ├── IOmniCoreInterface.aidl     # 4-method IPC Core 
│       └── IOmniResponseCallback.aidl  # Streaming callback
├── com/omnidev/extension/
│   └── ipc/
│       └── IOmniExtensionInterface.aidl  # Omni-Link
└── com/omnidev/launcher/
    └── ipc/
        └── IOmniLauncherInterface.aidl   # Launcher Control

```
**IOmniCoreInterface Methods:**
 * getSystemStatus(): Int
 * executeSystemCommand(command, contextData)
 * askAgentSilent(prompt)
 * streamAgentResponse(prompt, callback)
## 13. Project File Map
```text
app/src/main/
├── aidl/com/omnidev/
│   ├── workspace/ipc/             IOmniCoreInterface, IOmniResponseCallback
│   ├── extension/ipc/             IOmniExtensionInterface
│   └── launcher/ipc/              IOmniLauncherInterface
│
└── java/com/omnidev/workspace/
    ├── OmniDevApp.kt              Application class (init + Agent Brain + DI)
    ├── MainActivity.kt            Single Activity + Compose host
    │
    ├── data/
    │   ├── accessibility/         OmniAccessibilityService, SemanticTreeParser, SemanticUITool
    │   ├── admin/                 OmniDeviceAdminReceiver
    │   ├── auth/                  CopilotSessionManager, GitHubDeviceFlowManager, OAuthManager
    │   ├── brain/                 SmartLearningBridge, ToolExecutionJournal, ToolAwarenessEngine
    │   ├── communication/         OmniSmsReceiver
    │   ├── db/                    OmniDevDatabase (v7), DAOs, Entities
    │   ├── debug/                 CrashHandler, DebugLogManager
    │   ├── input/                 OmniInputMethodService
    │   ├── integration/           DiscordPollingService, TelegramPollingService, WhatsAppBridgeService
    │   ├── ipc/                   OmniCoreService, PrivilegedExecutionManager, RishShellManager
    │   ├── localllm/              LlamaCppInferenceEngine, LocalInferenceEngine
    │   ├── media/                 OmniMediaSessionService
    │   ├── model/                 AIModel, CompletionRequest, CompletionResponse
    │   ├── network/               CompletionService, OmniDevVpnService
    │   ├── repository/            ChatRepository, SettingsRepository, ApiKeyRepository
    │   ├── sync/                  OmniSyncService
    │   ├── system/                BootReceiver
    │   ├── tools/
    │   │   ├── ToolManager.kt           Interface + ToolExecutionResult + ToolDefinition
    │   │   ├── CompositeToolManager.kt  Main tool router (70+ tools)
    │   │   ├── FileToolManager.kt       File and Terminal tools
    │   │   ├── MemoryManager.kt         SQLite knowledge base
    │   │   ├── VectorMemoryManager.kt   Vector Embedding store
    │   │   ├── SystemAssistantTools.kt  Communication, device, apps
    │   │   ├── AdvancedSystemTools.kt   Root shell, package installation
    │   │   ├── HeadlessBrowserManager.kt Web scraper and browser
    │   │   ├── OmniNativeToolsManager.kt Native binaries loader
    │   │   ├── [All individual tool files...]
    │   │   ├── automation/         IntelligentAutomationEngine
    │   │   ├── ml/                 ToolMachineLearningEngine
    │   │   ├── monitoring/         ToolMonitoringSystem
    │   │   ├── orchestration/      ToolOrchestrator, ToolIntelligenceEngine
    │   │   ├── prediction/         PredictiveAnalyticsEngine
    │   │   └── security/           AdvancedSecurityAnalyzer, VulnResearchToolchain
    │   └── vision/                OmniScreenCaptureService
    │
    ├── domain/
    │   ├── attachment/            AttachmentProcessor
    │   └── engine/                AgentPipeline, SwarmOrchestrator, IntentClassifier, OmniMode
    │
    ├── registry/
    │   └── ModelRegistry.kt       All AI models + Routing Logic
    │
    └── ui/
        ├── analytics/             AnalyticsDashboardScreen + ViewModel
        ├── brain/                 AgentBrainDashboard + ViewModel
        ├── chat/                  ChatScreen, ChatViewModel, AgentLiveConsole
        ├── debug/                 DebugScreen + ViewModel
        ├── navigation/            AppNavigation
        ├── providers/             ProvidersScreen + ViewModel
        ├── settings/              Settings screens + ViewModels
        └── theme/                 Color, Theme, Type

```
## 14. Tech Stack & Libraries
| Category | Technology | Version |
|---|---|---|
| Language | Kotlin | 2.0.21 |
| UI Framework | Jetpack Compose + Material3 | BOM 2024.11.00 |
| Dependency Injection | Manual DI | — |
| Asynchronous | Kotlin Coroutines + Flow | 1.8.1 |
| Local Database | Room (SQLite) | 2.6.1 |
| Preferences | AndroidX DataStore | 1.1.1 |
| HTTP Client | Retrofit 2 + OkHttp | 2.11.0 + 4.12.0 |
| Serialization | kotlinx.serialization | 1.7.3 |
| Local LLM | Llama.cpp (JNI, GGUF) | NDK 27.0.12077973 |
| Privileged Execution | Shizuku | 13.1.5 |
| Computer Vision | CameraX + ML Kit OCR | 1.3.3 + 16.0.0 |
| HTML Parsing | Jsoup | 1.17.2 |
| Build System | Gradle 8.9, AGP 8.7.3, KSP | 2.0.21 |
| Target OS | Android 7.0 to Android 15 | API 24-35 |
## 15. Build & CI/CD
### Local Build Commands
```bash
# Debug APK compilation
./gradlew assembleDebug

# Run Lint checks
./gradlew lint

# Run Unit tests
./gradlew test

```
### CI Pipeline (.github/workflows/android-ci.yml)
 * Triggered on push to main and all Pull Requests.
 * Steps: Checkout → JDK 17 setup → Android SDK → NDK → Cache Restore → Lint → Build APK.
### Privileged Features Setup (ADB)
```bash
# Required permissions for Shizuku and advanced integrations
adb shell pm grant com.omnidev.workspace android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.omnidev.workspace android.permission.DUMP
adb shell appops set com.omnidev.workspace SYSTEM_ALERT_WINDOW allow

```
## 16. Android Permissions Reference
Protected/System permissions require explicit ADB or Shizuku granting:
| Permission | Purpose |
|---|---|
| WRITE_SECURE_SETTINGS | Modify deep system settings programmatically |
| DUMP | Read system service dumps and diagnostics |
| BIND_ACCESSIBILITY_SERVICE | Required to bind the Semantic UI Parser |
| BIND_INPUT_METHOD | Required to bind the custom IME |
| RECEIVE_SMS | Intercept incoming SMS for verification bridges |
| SYSTEM_ALERT_WINDOW | Required for floating overlays (if added in future) |
| CONTROL_CORE | Custom signature permission guarding OmniCoreService |
> **Note:** Do not add android.permission.BIND_VPN_SERVICE to <uses-permission>. It is declared only as android:permission on the <service> tag of OmniDevVpnService.
> 
## 17. Architecture Conventions & Agent Rules
 1. **Tool Return Type** — Always return ToolExecutionResult(output: String, isError: Boolean = false). Never rely on boolean success flags; use !result.isError.
 2. **Tool Registration** — Every new tool **must** be registered in both getToolDefinitions() **and** executeTool() within CompositeToolManager.
 3. **AIDL Namespaces** — Only two active packages are permitted: com.omnidev.workspace.ipc (core) and com.omnidev.launcher.ipc (launcher).
 4. **Shizuku Invocation** — Gate strictly on ShizukuCommandTool.isAvailable() (not isShizukuReady()) inside PrivilegedExecutionManager.
 5. **Lint / NewAPI** — Any queryIntentServices() targeting API ≥ 33 must be wrapped in if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU).
 6. **VpnService Declaration** — BIND_VPN_SERVICE is an android:permission on the <service> entry only.
 7. **Database Migrations** — Always increment the DB version in OmniDevDatabase and provide a named Migration object. Current version is **7**.
 8. **Context Propagation** — Pass Context via constructor injection into CompositeToolManager, not statically.
 9. **Coroutines** — All execute() methods within tools must dispatch to withContext(Dispatchers.IO). Never block the main thread.
 10. **New Engines (data/tools/*/)** — Infrastructure engines maintain single responsibility. Engines with generic execute(action, args) are exposed as tools. Internal infrastructural engines are NOT registered as tools.
## 18. Quick Development Guide
### How to Add a New Tool
```text
1. Create: data/tools/YourNewTool.kt
2. Implement: ToolManager interface (or use an object with static methods)
3. Define: getToolDefinitions() ← Defines tool schema and parameters for the LLM
4. Register: Add to CompositeToolManager.getToolDefinitions() + executeTool()
5. Update this Blueprint file

```
### How to Add a New Service
```text
1. Create: data/YourNewService.kt (extending Service)
2. Register: Add to AndroidManifest.xml
3. If AIDL: Add the corresponding .aidl file
4. Add to BootReceiver if auto-start is required
5. Update this Blueprint file

```
### How to Add a New UI Screen
```text
1. Create: ui/screens/YourNewScreen.kt (Composable function)
2. Route: Add to NavGraph in AppNavigation.kt
3. Bind: Connect with a respective ViewModel
4. Update this Blueprint file

```
### Key Entry Points
| Entry Point | File | Purpose |
|---|---|---|
| **MainActivity** | MainActivity.kt | Launch activity + Manual DI setup |
| **OmniDevApp** | OmniDevApp.kt | Application class + Agent Brain initialization |
| **OmniCoreService** | OmniCoreService.kt | AIDL IPC entry point |
| **BootReceiver** | BootReceiver.kt | Auto-start triggers upon device boot |
| **OmniAccessibilityService** | OmniAccessibilityService.kt | Semantic UI parsing & automation |
### Critical Files for Quick Review
| Priority | File | Reason |
|---|---|---|
| 1️⃣ | domain/engine/AgentPipeline.kt | **The Core Brain** — ReAct Loop |
| 2️⃣ | data/tools/CompositeToolManager.kt | **The Tool Hub** — Routing layer |
| 3️⃣ | data/network/CompletionService.kt | **LLM Routing** — API dispatch |
| 4️⃣ | data/brain/SmartLearningBridge.kt | **Memory Hub** — Context and learning |
| 5️⃣ | registry/ModelRegistry.kt | **Model Selection** |
| 6️⃣ | ui/screens/ChatScreen.kt | **Primary UI** |
| 7️⃣ | OmniDevApp.kt | **Initialization** |
## 19. Known Issues & Modularization Roadmap
### 🔴 Duplicate AIDL Files (Partially Resolved)
```diff
✅ Resolved: Deleted the legacy file
   com/omnidev/workspace/IOmniCoreInterface.aidl   [DELETED]
✅ Kept:
   com/omnidev/workspace/ipc/IOmniCoreInterface.aidl [ACTIVE]

```
### 🟡 Overlapping Tool Functionality (Under Review)
```text
TelegramBotTool vs TelegramPublisherTool     ← Different intents (OK)
DiscordBotTool vs DiscordPublisherTool       ← Different intents (OK)
WhatsAppTool vs WhatsAppBridgeTool           ← Different integration points (OK)
MemoryManager vs VectorMemoryManager         ← Complementary DBs (OK)

```
### 📋 Action Plan & Modularization Roadmap
#### Phase 1: Cleanup ✅
 * [x] Audit duplicate AIDL files.
 * [x] Delete old AIDLs and fix import references.
#### Phase 2: Agent Brain ✅
 * [x] Implement SmartLearningBridge.
 * [x] Implement ToolExecutionJournal.
 * [x] Implement ToolAwarenessEngine.
 * [x] Migrate Database to v7.
#### Phase 3: OmniNativeToolsManager ✅
 * [x] Load Python, aapt2, jadx, apktool binaries.
 * [x] Execute via Shizuku bypassing Termux limitations.
#### Phase 4: Flavor Integration & Policy Layer ✅
 * [x] Integrate 4-Tier Gradle Flavors (lite, norm, pro, oem).
 * [x] Setup Manifest Overlays using tools:node="remove" for strict compliance.
 * [x] Implement TierPolicy and PrivilegedExecutionFacade.
 * [x] **Expand to 5-Tier Architecture** — added `admin` master-key developer
       build (all flags `true`, zero-click auto-approval, Shizuku + root +
       Runtime.exec fallback chain, full llama.cpp ABI set).
 * [x] **Upgrade `lite` tier** to ship the Persistent Memory & Vector
       Knowledge Base tools (`remember_fact`, `search_knowledge`,
       `update_memory`, `delete_memory`, `vector_store`, `vector_search`,
       `vector_similar`) so even the Play-Store-safe variant has long-term
       on-device context.
#### Phase 5: Physical Modularization (Pending)
 * [ ] Create :core:shared module and migrate Domain, Entities, and AgentPipeline.
 * [ ] Create :core:ipc module for AIDL and Binders.
 * [ ] Create :tools:lite module for strictly safe tools.
 * [ ] Create :tools:standard module.
 * [ ] Create :tools:advanced module for Shizuku, Pentesting, and Root shells (restricted to pro flavor).
 * [ ] Implement CAMPS daemon (Magisk systemless module).
 * [ ] Achieve full root execution via mtkclient.
> **Last Updated:** 2026-04-23 (5-Tier Architecture Expansion — added `admin` master-key build; upgraded `lite` with Memory + Vector tools)
> **Maintenance Rule:** This document MUST be updated whenever a Tool, Service, or Architectural change is committed.
