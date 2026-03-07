# OmniDev Workspace

[![Android CI](https://github.com/obieda-hussien/DevSwarm/actions/workflows/android-ci.yml/badge.svg)](https://github.com/obieda-hussien/DevSwarm/actions/workflows/android-ci.yml)

**OmniDev Workspace** is a fully autonomous, on-device AI coding companion and system agent for Android. It combines powerful cloud AI models with real on-device LLM inference via llama.cpp, a 34+ tool ecosystem, long-term memory, multi-session history, and deep system integration through Shizuku — all wrapped in a modern Material 3 Expressive UI built with Jetpack Compose.

> Package: `com.omnidev.workspace` · Min SDK: 24 · Target SDK: 35

---

## Features

### 🤖 On-Device Local LLM (Local Edge Model — BYOM)
Run any quantized `.gguf` model entirely on-device with **zero internet and zero API key** required:
- **llama.cpp JNI bridge** — real C++ inference via `libllama_jni.so` compiled with the Android NDK
- **File-descriptor model loading** — GGUF files are opened via `content://` URIs and passed through `/proc/self/fd/{fd}` directly to C++, no filesystem copy needed
- **Token streaming** — each decoded token piece is delivered to the Compose UI in real-time via a JNI callback
- **Multi-turn support** — KV cache cleared between messages (`llama_kv_self_clear`) so the context never fills up
- **OOM crash protection** — pre-load native heap check, `OutOfMemoryError` catch on load and generation, safe cleanup via `llama_model_free`
- **Suggested model catalog** — curated GGUF links with size and capability descriptions (Llama-3.2-1B, Llama-3.2-3B, DeepSeek-Coder-1.3B, Phi-3.5-mini)
- Engine status card shows **Ready / No model loaded** with green indicator

### ☁️ Multi-Provider Cloud AI
Full streaming support across all major providers:

| Provider | Notable Models |
|----------|---------------|
| **Anthropic** | Claude 3.5 Sonnet, Claude 3 Opus, Claude 3.7 Sonnet |
| **OpenAI** | GPT-4o, GPT-4 Turbo, o1, o3-mini |
| **Google Gemini** | Gemini 2.5 Pro, Gemini 2.5 Flash, Gemini 1.5 Flash |
| **Groq** | Llama-3.3-70B, DeepSeek-R1, Mixtral |
| **OpenRouter** | GPT-5.3 Codex, Gemini 3.1 Pro, DeepSeek R2, Grok-3, Codestral 2501, Llama 4 Maverick |

### 🔀 Granular Model Routing
Assign a specific model to each of 4 independent roles in **AI Preferences**:
- **Chat Model** — general Q&A and conversational responses
- **Agent Model** — single ReAct loop tool executor
- **Swarm Orchestrator** — task decomposition planner (Team mode)
- **Swarm Worker** — code executor in Team mode with optional persona

### 🧠 ReAct Agent & Swarm Orchestration
- **Single Agent (Agent tab)**: Reason → Act (tool call) → Observe → loop until goal met
- **Swarm Team (Team Agents tab)**: Omni-Orchestrator decomposes the task → assigns sub-tasks with `requiredPersona` → Workers execute in parallel → Synthesizer merges results
- **Deep Thinking**: toggleable `<thinking>` block parsing for extended chain-of-thought
- **Confirmation Gate**: high-risk actions (delete, install, root shell) require user approval before execution
- **Auto-Heal Build**: `AutoHealBuildUseCase` detects compilation errors and loops the agent to fix them automatically

### 🛠️ 34+ Agent Tools

#### Codebase & File System
| Tool | Description |
|------|-------------|
| `read_file_lines` | Read specific line ranges — never wastes tokens on full-file reads |
| `search_codebase` | Regex search returning file path + line number + snippet |
| `patch_file_content` | Surgical find-and-replace edits |
| `create_file` | Create new files with content |
| `delete_file` | Delete individual files |
| `advanced_terminal` | Full terminal via Shizuku with God Mode fallback to `/data/local/tmp` |
| `setup_build_environment` | Scaffold project environments |

#### AI Memory & Knowledge
| Tool | Description |
|------|-------------|
| `remember_fact` | Store a long-term fact in the Room knowledge base |
| `search_knowledge` | Vector/keyword search over stored facts |
| `update_memory` | Edit an existing memory snippet |
| `delete_memory` | Remove a memory snippet |

#### System & Device
| Tool | Description |
|------|-------------|
| `hardware_toggle_tool` | Toggle WiFi, Bluetooth, mobile data, location, flashlight, auto-rotate |
| `get_device_info` | Battery, RAM, storage, build info, ABI, locale |
| `get_current_location` | GPS coordinates (with permission) |
| `app_manager_tool` | List, launch, force-stop, or clear data for installed apps |
| `read_notifications` | Capture active Android notifications |

#### Advanced System Tools (Shizuku)
| Tool | Description |
|------|-------------|
| `screenshot_tool` | Take screenshot via `screencap`, saves to `/data/local/tmp/` |
| `system_settings_tool` | Get/put values in `system`, `secure`, `global` settings namespaces |
| `package_installer_tool` | Install APK (`pm install -r -g`), uninstall, list packages |
| `root_shell_tool` | Execute arbitrary shell commands via Shizuku (6000 char output limit) |
| `ui_automation` | Dump screen XML, tap, swipe, input text, press keycodes |
| `call_log_tool` | Read recent calls, search by name/number, count entries |
| `sms_reader_tool` | Read inbox, sent messages, search by number or body |
| `search_contacts` | Query device contacts by name or phone number |

#### Developer & Version Control
| Tool | Description |
|------|-------------|
| `git_manager` | Init, status, add, commit, push, pull, branch, diff |
| `github_manager` | Create/update issues and pull requests via the GitHub REST API |
| `analyze_logcat` | Filter and analyze Logcat output by tag, level, or package |
| `visual_inspector` | Take screenshots and analyze UI layout for debugging |
| `god_eye_profiler` | CPU, memory, frame rate profiling |
| `read_db_schema` | Inspect Room/SQLite database schemas |
| `analyze_anr_trace` | Parse ANR trace files for deadlock detection |

#### Communications & Publishing
| Tool | Description |
|------|-------------|
| `communicate_tool` | Send messages via SMS, email, or in-app channels |
| `telegram_publish` | Post messages and files to a Telegram channel/bot |
| `publish_to_discord` | Send rich embeds to a Discord webhook |
| `create_notion_page` | Create pages in a Notion database |
| `planner_tool` | Create and manage scheduled agent tasks |

### 📅 Task Scheduler
- Schedule recurring or one-shot agent tasks with cron-like syntax
- **ScheduledTasksScreen** — reactive live list of all tasks with cancel/delete controls
- `TaskSchedulerTool.scheduleTaskDirectly()` callable from agent tools

### 🗂️ Tool Registry
- **ToolRegistryScreen** lists all 34+ tools grouped by category
- Searchable, shows description and parameter schema for each tool

### 💾 Long-Term Memory
- **Room database** (`KnowledgeSnippet` entity) persists facts across sessions
- **MemoryExplorerScreen** — browse, search, and delete stored memories
- `MemoryManager` handles injection of relevant facts into every system prompt

### 📜 Session History
- Every conversation stored in Room (`ChatMessageEntity`, `ChatSessionEntity`)
- Restore and continue any previous session
- **ChatRepository** with full CRUD via DAOs

### 🪟 Glass Brain Console
- **AgentLiveConsole** — real-time scrolling log of every agent step (thought, tool call, observation, error)
- Color-coded by step type with copy-to-clipboard support

### 📎 Multi-Modal Attachment Engine
- Attach images, PDFs, text files, and videos to any message
- Safety limits: max 5 files, 15 MB total
- Vision models receive base64-encoded images; Gemini models receive video natively
- Non-supporting models receive a graceful error

### ✏️ Markdown Rendering
- `MarkdownText` composable renders headers, bold, italic, inline code, code blocks, and bullet lists
- Code blocks show syntax-highlighted monospace with a copy button

### 🐛 Debug Screen & Crash Handler
- **DebugScreen** — live structured log viewer with level filter (DEBUG / INFO / WARNING / ERROR)
- `CrashHandler` catches uncaught exceptions and writes structured crash reports with full device info
- `DebugLogManager` ring-buffer with timestamps, module tags, and stack traces

### 💬 Overlay Bubble
- **OmniBubbleService** — floating chat bubble overlay that stays visible over other apps
- Tap to expand the full chat interface without switching apps

### 🔗 Integrations
- **OAuth Manager** — handles OAuth 2.0 flows for third-party services
- **Telegram**, **Discord**, **Notion** publisher tools
- **GitHub** REST API integration (issues, PRs)
- **Environment Setup Manager** — auto-configures build dependencies

### 🔧 System Prompt Editor
- Full-screen editor to customize the AI's base persona and instructions per role

---

## Architecture

The project follows **Clean Architecture** with an **MVI/MVVM** UI pattern:

```
app/src/main/java/com/omnidev/workspace/
├── data/
│   ├── auth/           # OAuthManager
│   ├── db/             # Room database (ChatMessage, ChatSession, KnowledgeSnippet)
│   ├── debug/          # CrashHandler, DebugLogManager
│   ├── localllm/       # LlamaCppInferenceEngine (JNI), LocalInferenceEngine interface
│   ├── model/          # AIModel, CompletionRequest, ModelProvider enum
│   ├── network/        # CompletionService (streaming HTTP client)
│   ├── repository/     # ApiKeyRepository, ChatRepository, SettingsRepository
│   └── tools/          # CompositeToolManager + all 34+ tool implementations
├── domain/
│   ├── attachment/     # AttachmentProcessor (multi-modal files)
│   └── engine/         # AgentPipeline (ReAct loop), SwarmOrchestrator, AutoHealBuildUseCase
├── registry/           # ModelRegistry (full AI model catalog)
├── ui/
│   ├── chat/           # ChatScreen, ChatViewModel, AgentLiveConsole, MarkdownText
│   ├── debug/          # DebugScreen, DebugViewModel
│   ├── navigation/     # Compose Navigation host (AppNavigation)
│   ├── overlay/        # OmniBubbleService (floating bubble)
│   ├── providers/      # ProvidersScreen (API key management)
│   ├── settings/       # AISettingsScreen, LocalModelManagerScreen, MemoryExplorerScreen,
│   │                   # ScheduledTasksScreen, SystemPromptEditorScreen, ToolRegistryScreen,
│   │                   # IntegrationsScreen
│   └── theme/          # Material 3 Expressive theme (Color, Type, Theme)
├── MainActivity.kt
└── OmniDevApp.kt
app/src/main/cpp/
├── llama_jni.cpp       # Real JNI bridge using llama.cpp C API
├── llama_jni_stub.cpp  # No-op stub when llama.cpp submodule absent
└── CMakeLists.txt      # Two-tier: git submodule → FetchContent b5695
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.0 |
| UI Framework | Jetpack Compose + Material 3 Expressive |
| State Management | MVI with StateFlow / SharedFlow |
| Persistence | Room (chat history, memory) + DataStore Preferences (settings) |
| Async | Kotlin Coroutines + Flow |
| Serialization | kotlinx.serialization (JSON) |
| HTTP Client | OkHttp with SSE streaming |
| Native Inference | llama.cpp (C++ via JNI, compiled with Android NDK) |
| Privileged Operations | Shizuku API |
| Build System | Gradle Kotlin DSL + Version Catalog |

---

## Getting Started

### Prerequisites

- Android Studio Iguana (2023.2.1) or later
- JDK 17
- Android SDK 35
- Android NDK (installed via SDK Manager or `android-actions/setup-android`)
- *(For local LLM)* A quantized `.gguf` model file on your device

### Clone and Build

```bash
# 1. Clone with the llama.cpp submodule
git clone --recurse-submodules https://github.com/obieda-hussien/DevSwarm.git
cd DevSwarm

# 2. Build Debug APK (NDK will compile libllama_jni.so automatically)
./gradlew assembleDebug

# 3. Build Release APK (signed with debug keystore for testing)
./gradlew assembleRelease

# 4. Run unit tests
./gradlew test

# 5. Lint check
./gradlew lint
```

> **Note:** If you already cloned without `--recurse-submodules`, initialize the submodule with:
> ```bash
> git submodule update --init --recursive
> ```
> The Gradle build task `initLlamaCppSubmodule` also handles this automatically before every CMake configure.

### Enable Local Edge Model

1. Download a quantized GGUF model from HuggingFace (e.g., `Llama-3.2-1B-Instruct-IQ4_XS.gguf`)
2. Transfer it to your Android device
3. Open **Settings → Local Edge Model (BYOM)**
4. Tap **Select .gguf Model File** and choose the file
5. Engine Status changes to **Ready — ModelName.gguf** 🟢
6. In **AI Preferences → Chat Model**, select **Local Edge Model (BYOM)**
7. Chat without any internet connection or API key

### API Keys (Cloud Models)

1. Open **Settings → API Keys**
2. Enter keys for the providers you want to use (Anthropic, OpenAI, Gemini, Groq, OpenRouter)
3. Keys are stored encrypted in DataStore Preferences

---

## Permissions

The app declares the following permissions to power its full tool ecosystem:

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | Cloud AI API calls |
| `READ_CONTACTS`, `WRITE_CONTACTS` | Contacts search tool |
| `READ_CALL_LOG`, `WRITE_CALL_LOG` | Call log tool |
| `READ_SMS`, `RECEIVE_SMS` | SMS reader tool |
| `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | Bluetooth hardware toggle |
| `WRITE_SETTINGS`, `WRITE_SECURE_SETTINGS` | System settings tool |
| `REQUEST_INSTALL_PACKAGES`, `INSTALL_PACKAGES` | Package installer tool |
| `PACKAGE_USAGE_STATS` | Usage access for app manager |
| `ACTIVITY_RECOGNITION` | Physical activity sensor |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Location tool |
| `CAMERA` | Visual inspector tool |
| `SYSTEM_ALERT_WINDOW` | Overlay bubble service |
| `FOREGROUND_SERVICE` | Agent notification service |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Notification capture tool |

> Shizuku-based tools (`screenshot_tool`, `system_settings_tool`, `package_installer_tool`, `root_shell_tool`, `ui_automation`, `advanced_terminal`) require **Shizuku** to be installed and running on the device.

---

## CI/CD

GitHub Actions runs automatically on every push and PR to `main`:

| Step | Action |
|------|--------|
| Checkout | `actions/checkout@v4` with `submodules: recursive` to pull llama.cpp |
| JDK | `actions/setup-java@v4` — Temurin JDK 17 |
| Android NDK | `android-actions/setup-android@v3` |
| CMake cache | `actions/cache@v4` keyed to llama.cpp b5695 |
| Lint | `./gradlew lint` |
| Unit Tests | `./gradlew test` |
| Build | `./gradlew assembleDebug assembleRelease --stacktrace` |
| Artifacts | Uploads both `app-debug.apk` and `app-release.apk` |

Release builds use the **debug signing config** until a production keystore is configured (see `// TODO` in `app/build.gradle.kts`). ProGuard is enabled on release with keep rules for kotlinx.serialization, Room, JNI methods, Shizuku, and Kotlin Coroutines.

See [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml).

---

## Roadmap

- [ ] **In-app GGUF model downloader** — browse HuggingFace catalog and download directly within the app
- [ ] **Multi-turn context window management** — sliding window with automatic summarization when context exceeds model limit
- [ ] **Voice input / TTS output** — speak to the agent and hear responses aloud
- [ ] **RAG (Retrieval-Augmented Generation)** — embed codebase files into a local vector store for semantic search
- [ ] **Plugin SDK** — allow third-party tool plugins packaged as separate APKs
- [ ] **Diffusion image generation** — run Stable Diffusion on-device via GGUF/ggml
- [ ] **Encrypted knowledge base** — AES-256 encryption for sensitive memory snippets
- [ ] **Production release signing** — replace debug keystore with production keystore for Google Play
- [ ] **Automated integration tests** — Espresso/Compose UI test suite
- [ ] **Widget** — home screen widget showing active agent task status
