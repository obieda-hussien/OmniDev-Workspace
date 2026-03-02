# OmniDev Workspace

[![Android CI](https://github.com/obieda-hussien/DevSwarm/actions/workflows/android-ci.yml/badge.svg)](https://github.com/obieda-hussien/DevSwarm/actions/workflows/android-ci.yml)

**OmniDev Workspace** is a highly advanced, autonomous AI coding companion for Android. Built with Jetpack Compose, Kotlin Coroutines/Flow, and Material 3 Expressive design, it runs alongside standard Android IDEs to provide intelligent code generation, analysis, and editing capabilities.

> Package: `com.omnidev.workspace`

## Architecture

The project follows **Clean Architecture** with an **MVI/MVVM** UI pattern:

```
app/src/main/java/com/omnidev/workspace/
├── data/
│   ├── model/          # Data classes (AIModel, CompletionRequest, etc.)
│   ├── repository/     # SettingsRepository (DataStore persistence)
│   └── tools/          # ToolManager interface & FileToolManager
├── domain/
│   ├── engine/         # AgentPipeline (ReAct loop) & SwarmOrchestrator
│   └── attachment/     # AttachmentProcessor (multi-modal files)
├── registry/           # ModelRegistry (hardcoded AI model catalog)
├── ui/
│   ├── theme/          # Material 3 Expressive theme (Color, Type, Theme)
│   ├── settings/       # AISettingsScreen & ViewModel (model routing)
│   ├── chat/           # ChatScreen & ViewModel (Omni-Chat interface)
│   └── navigation/     # Compose Navigation host
├── MainActivity.kt
└── OmniDevApp.kt
```

## Key Features

### Granular Model Routing
Assign specific AI models to 4 distinct roles:
- **Chat Model** — General Q&A interactions
- **Agent Model** — Single ReAct loop executor
- **Swarm Orchestrator** — Task decomposition planner in Team mode
- **Swarm Worker** — Code executor in Team mode

Models are grouped by provider (Anthropic, OpenAI, Gemini, Groq, OpenRouter) in Material 3 ExposedDropdownMenus.

### Token-Optimized Smart Tools
Five file-system tools designed to minimize context window token usage:
| Tool | Purpose |
|------|---------|
| `read_file_lines` | Read specific line ranges (never full files) |
| `search_codebase` | Regex search with file + line snippets |
| `patch_file_content` | Surgical find-and-replace edits |
| `create_file` | Create new files with content |
| `delete_file` | Delete individual files |

All tools enforce **Target Context scoping** — paths are canonicalized and validated to prevent traversal attacks.

### Multi-Modal Attachment Engine
- Supports images, PDFs, text files, and videos simultaneously
- Safety limits: max 5 files, max 15 MB total
- Video handling: native pass-through for supporting models (Gemini), graceful rejection for others

### ReAct & Swarm Engine
- **Single Agent Loop**: Reason → Act (tool call) → Observe → Iterate until goal is met
- **Swarm Mode**: Orchestrator decomposes tasks → Workers execute sub-tasks → Synthesize results
- **Deep Thinking**: Toggleable `<thinking>` block support for extended chain-of-thought reasoning

## Tech Stack

- **Language**: Kotlin 2.0
- **UI Framework**: Jetpack Compose + Material 3 Expressive
- **State Management**: MVI with StateFlow
- **Persistence**: DataStore Preferences
- **Async**: Kotlin Coroutines + Flow
- **Serialization**: kotlinx.serialization
- **Build System**: Gradle (Kotlin DSL) with Version Catalog
- **Min SDK**: 24 · **Target SDK**: 35

## Getting Started

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17 or later
- Android SDK 35

### Build

```bash
# Debug build
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run lint checks
./gradlew lint
```

## CI/CD

GitHub Actions automatically runs on every push and PR to `main`:

1. **Lint** — Static analysis checks
2. **Unit Tests** — ModelRegistry, FileToolManager, scope validation tests
3. **Build** — `assembleDebug` to produce the APK
4. **Artifact** — Uploads the debug APK

See [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml).
