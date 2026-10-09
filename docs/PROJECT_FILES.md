# OmniDev Workspace file guide

OmniDev Workspace combines chat, tool agents, teams, a floating assistant, chat memory and integrations. Gradle builds one `:app` module; `core/`, `domain/`, `data/` and `ui/` are package boundaries inside it.

## Repository layout

| Area | Path | Contents |
| --- | --- | --- |
| Application overview | `README.md` | Features, counts, setup, builds and documentation links |
| Android application | `app/` | Kotlin, resources, manifests, AIDL, C++ and tests |
| Build management | `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle/` | Modules, flavors, dependencies and wrapper |
| Validation and releases | `.github/workflows/`, `.github/scripts/` | Quality, tests, lint, builds, signing and dependency preparation |
| Maintenance | `scripts/` | Source metrics, tool/skill inventory and repository security checks |
| WhatsApp bridge | `whatsapp-bridge/` | Termux bridge, setup and execution |
| Operating and integration guides | `docs/` and specialized root documents | Assistant, access, memory, performance and OmniLink |
| Planning materials | `00_INTEGRATION_ORDER.md`, `01_...` through `08_..._PROMPT.md` | Historical Omni specifications; compare with source before implementation |

## Entry points

Paths below start at `app/src/main/java/com/omnidev/workspace/`.

| File | Responsibility |
| --- | --- |
| `OmniDevApp.kt` | Initialize application policies and shared services |
| `MainActivity.kt` | UI, navigation, results and incoming requests |
| `WorkspaceChatRuntime.kt` | Create main/assistant chat environments and wire tools and engines |
| `ui/chat/ChatViewModel.kt` | Send messages, run selected modes, handle tool results and approvals |
| `domain/engine/AgentPipeline.kt` | Agent loop, tool calls and result handling |
| `domain/engine/SwarmOrchestrator.kt` | Team planning, work distribution and synthesis |
| `data/tools/CompositeToolManager.kt` | Collect definitions and dispatch tool execution |
| `core/policy/TierPolicy.kt` | Flavor capabilities and approval boundaries |

## Code by responsibility

| Area | Packages | Key components |
| --- | --- | --- |
| Personalization and reference photos | `ui/settings/`, `data/model/`, `data/chatmedia/` | `UserProfileScreen`, `ProfilePersonalization`, `ProfileReferenceStore`, consented generation references |
| Virtual companion | `ui/companion/` | Drawing, movement, moods, two neural networks, local learning and long-term memory |
| Tool and skill mentions | `ui/chat/`, `domain/engine/` | `MentionFocus`, multiple tools/skills in the message editor, priority with supporting tools available |
| Application UI | `ui/chat/`, `ui/settings/`, `ui/providers/`, `ui/navigation/` | Chat, settings, providers and navigation |
| Floating assistant | `ui/assistant/`, `data/assistant/` | Panel, session, voice, bubble, attachments and Android settings return |
| Permissions and privileges | `core/policy/`, `core/privileged/`, `data/tools/`, `data/ipc/` | `PermissionManagerTool`, `DeviceAccessCatalog`, `PermissionRequestPlan`, `AppOpAccessPlan`, `PrivilegedExecutionManager` |
| Mode decisions | `domain/engine/` | `IntentClassifier`, `AdaptiveModeRouter`, `ModeDecisionModel`, `ModeOutcomeLearner` |
| Agent budgets and continuity | `domain/engine/` | Context compression, token budgets, handoffs, repeated calls and stall handling |
| Project indexing | `data/repo/`, `data/builddoctor/`, `data/rollback/` | Code indexing, evidence retrieval, build diagnosis and rollback |
| Data and memory | `data/db/`, `data/repository/`, `data/brain/` | Room, DAOs, repositories and memory |
| Models and providers | `data/model/`, `data/network/`, `data/localllm/`, `registry/` | Requests, completions, local models and registration |
| Device control | `data/accessibility/`, `data/input/`, `data/admin/`, `data/media/` | Accessibility, IME, device administration and media sessions |
| Connected applications | `data/ipc/`, `data/mcp/`, `data/integration/`, `data/auth/` | OmniLink, MCP, integrations and accounts |
| Background and scheduling | `data/background/`, `data/sync/`, `domain/engine/` | Periodic work, synchronization and scheduled tasks |
| Performance and monitoring | `ui/motion/`, `ui/analytics/`, `ui/brain/`, `data/debug/` | Motion policy, analytics, memory views and logs |

A tool family is not a single tool count. Definitions, dispatch paths, flavors, Android grants and integration availability determine the actual exposed set. See the [tool and skill catalog](TOOL_AND_SKILL_CATALOG.md) and [MCP defaults](MCP_DEFAULT_SERVICES.md).

## Android sources and tests

| Path | Purpose |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | Shared declarations and components |
| `app/src/{lite,norm,pro,oem,admin}/` | Flavor policies, manifests and sources |
| `app/src/liteNorm/`, `app/src/proOem/`, `app/src/proOemAdmin/` | Shared flavor groups |
| `app/src/main/res/` | Icons, strings, XML and themes |
| `app/src/main/aidl/` | Binder service contracts |
| `app/src/main/cpp/` | Native local-model execution bridge |
| `app/src/main/assets/agent-skills/` | Six bundled agent skills |
| `app/src/test/` | JVM engine, policy, planning and tool tests |
| `app/src/androidTest/` | Compose, window and device/emulator behavior tests |

## Documentation map

| Goal | Document |
| --- | --- |
| Personalization and reference photos | [Profile personalization](PROFILE_PERSONALIZATION.md) |
| Tool and skill mentions | [Chat mentions](chat-mentions.md) |
| Companion and local learning | [Virtual companion](virtual-companion.md) |
| Files and lines | [Repository statistics](REPOSITORY_STATS.md) |
| Tool and bundled skill counts | [Tool and skill catalog](TOOL_AND_SKILL_CATALOG.md) |
| Default MCP setup | [Default services](MCP_DEFAULT_SERVICES.md) |
| Application setup | [README](../README.md) |
| Layers and contracts | [Architecture](../PROJECT_ARCHITECTURE.md) |
| Quick entry points | [Mental map](../MENTAL_MAP.md) |
| AUTO and outcome learning | [Decision engine](../DECISION_ENGINE.md) |
| Floating assistant | [Screen assistant](SCREEN_ASSISTANT.md) |
| Device permissions | [Device access](../DEVICE_ACCESS.md) |
| Chat memory | [History recall](../CHAT_HISTORY_RECALL.md) |
| Motion and performance | [Performance guide](../UI_PERFORMANCE.md) |
| Omni applications | [OmniLink](../OMNILINK_V3_INTEGRATION.md), [protocol](../LINK_PROTOCOL.md) |
| Launcher | [Launcher integration](LAUNCHER_INTEGRATION.md) |
| Telegram and WhatsApp | [Telegram](../TELEGRAM_INTEGRATION.md), [WhatsApp](../whatsapp-bridge/README.md) |
| Future module separation | [Modularization roadmap](../MODULARIZATION_ROADMAP.md) |
| Attribution | [Attribution](../ATTRIBUTION.md) |

Reproduce committed file/line counts with `python3 scripts/repository_stats.py` and catalog counts with `python3 scripts/tool_catalog.py`. GitHub Actions records build results. Vision documents describe proposals; source and runtime contracts describe implementation.
