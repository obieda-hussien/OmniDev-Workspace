# OmniDev Workspace — Master System Blueprint

[![Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/workflows/android-ci.yml/badge.svg)](https://github.com/obieda-hussien/OmniDev-Workspace/actions/workflows/android-ci.yml)

> **AI AGENT CONTEXT MAP** — هذا الملف هو المرجع الأعلى سلطةً لكل LLM Agent، Copilot Session، ومطور بشري.
> اقرأه أولاً — فهو يرسم كل ملف، كل تدفق، كل نمط، وكل قرار معماري في المشروع بقراءة واحدة.
>
> **Package:** `com.omnidev.workspace` · **Min SDK:** 24 · **Target SDK:** 35 · **NDK:** 27.0.12077973 · **Language:** Kotlin 2.0 · **UI:** Jetpack Compose + Material 3

---

## 📊 إحصائيات المشروع

| المقياس | القيمة |
|---------|--------|
| **إجمالي ملفات Kotlin** | 163 ملف |
| **إجمالي أسطر الكود** | ~60,000+ سطر |
| **أدوات (Tools)** | 57+ أداة |
| **خدمات (Services)** | 12 خدمة |
| **واجهات AIDL** | 5 واجهات |
| **إصدار قاعدة البيانات** | v7 (Agent Brain) |
| **الحد الأدنى SDK** | 24 |
| **الحد الأقصى SDK** | 35 |
| **اللغة** | Kotlin 2.0 |
| **الواجهة** | Jetpack Compose + Material 3 |
| **Product Flavors** | `lite` / `norm` / `pro` / `oem` (4-tier) |

---

## 🎛️ 4-Tier Flavor Architecture

The project ships four product flavors on the `tier` Gradle dimension. Each
flavor is a distinct business tier with its own `applicationId`, manifest
overlay, `TierPolicy`, and confirmation behaviour.

| Flavor | applicationId                  | Audience                    | Capabilities                                                         | Gate behaviour |
|--------|--------------------------------|-----------------------------|----------------------------------------------------------------------|----------------|
| `lite` | `com.omnidev.workspace`        | B2C Free / Google Play      | `web_search`, `web_search_deep`, `web_scraper`, `read_file` ONLY     | Deny-all       |
| `norm` | `com.omnidev.workspace.norm`   | B2C Basic / Standard devs   | Accessibility + terminal + Git + app manager (no root, no Shizuku)   | User prompts   |
| `pro`  | `com.omnidev.workspace.pro`    | B2C Premium / Power users   | Full God-Mode — Shizuku + root + deep-security + pentesting + swarm  | User prompts   |
| `oem`  | `com.omnidev.workspace.oem`    | B2B partners / Custom ROMs  | `android.uid.system` + local Llama.cpp (no device wipe)              | **Zero-click** (OemTierPolicy auto-approves) |

Build a specific flavor with:

```bash
./gradlew :app:assembleLiteDebug     # Play-Store-safe variant
./gradlew :app:assembleNormDebug     # Standard
./gradlew :app:assembleProDebug      # Full god-mode
./gradlew :app:assembleOemDebug      # OEM / custom ROM
```

The policy layer lives in `com.omnidev.workspace.core.policy` (see
`TierPolicy.kt`, `ConfirmationGate.kt`, `OmniAuditLog.kt`,
`TierPolicyHolder.kt`). Privileged execution is abstracted behind
`com.omnidev.workspace.core.privileged.PrivilegedExecutionFacade`.

For the roadmap to physically extract `:core:shared`, `:core:ipc`, and
`:tools:{lite,standard,advanced}` as independent Gradle modules, see
[**MODULARIZATION_ROADMAP.md**](./MODULARIZATION_ROADMAP.md).

---

## جدول المحتويات

1. [نظرة عامة على المعمارية](#1-نظرة-عامة-على-المعمارية)
2. [نظام Agent Brain الجديد](#2-نظام-agent-brain-الجديد)
3. [تدفقات التنفيذ الأساسية](#3-تدفقات-التنفيذ-الأساسية)
4. [توجيه النماذج — ModelRegistry](#4-توجيه-النماذج--modelregistry)
5. [نظام الأدوات — الدليل الكامل](#5-نظام-الأدوات--الدليل-الكامل)
6. [محركات الذكاء والأتمتة](#6-محركات-الذكاء-والأتمتة)
7. [IPC وتكامل النظام](#7-ipc-وتكامل-النظام)
8. [القدرات متعددة الوسائط](#8-القدرات-متعددة-الوسائط)
9. [طبقة الاستمرارية (Persistence)](#9-طبقة-الاستمرارية-persistence)
10. [طبقة الواجهة (UI)](#10-طبقة-الواجهة-ui)
11. [الخدمات والمستقبلات](#11-الخدمات-والمستقبلات)
12. [واجهات AIDL](#12-واجهات-aidl)
13. [خريطة ملفات المشروع](#13-خريطة-ملفات-المشروع)
14. [المكدس التقني والمكتبات](#14-المكدس-التقني-والمكتبات)
15. [البدء والـ CI/CD](#15-البدء-والـ-cicd)
16. [مرجع صلاحيات Android](#16-مرجع-صلاحيات-android)
17. [اتفاقيات المعمارية وقواعد الـ Agents](#17-اتفاقيات-المعمارية-وقواعد-الـ-agents)
18. [دليل التطوير السريع](#18-دليل-التطوير-السريع)
19. [المشكلات المكتشفة وخطة العمل](#19-المشكلات-المكتشفة-وخطة-العمل)

---

## 1. نظرة عامة على المعمارية

**OmniDev Workspace** هو **مهندس برمجيات ذاتي الحكم بوضع God-Mode** لنظام Android. يُنسّق بين النماذج السحابية والمحلية في سرب متعدد الوكلاء (Multi-Agent Swarm) قادر على كتابة كود، تشغيل محطات طرفية، التحكم بنظام التشغيل، إدارة الملفات، الكلام والاستماع، تصفح الويب، وإصلاح أخطاء البناء تلقائياً — باستقلالية تامة.

```
┌───────────────────────────────────────────────────────────────────────┐
│                         OmniDev Workspace                             │
│                                                                       │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │
│  │  Shizuku /  │  │  Llama.cpp  │  │    Swarm    │  │   Duplex    │ │
│  │  Root Shell │  │  On-device  │  │  Multi-Agent│  │  Voice I/O  │ │
│  │  Execution  │  │  Inference  │  │  (Parallel) │  │  + Overlay  │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘ │
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

### الركائز الأربع الأساسية

| الركيزة | الوصف | الملفات الرئيسية |
|---------|-------|-----------------|
| **OS-Level Execution** | Shizuku (ADB-level) + root shell + Device Admin للعمليات المميزة | `PrivilegedExecutionManager`, `ShizukuCommandTool`, `RishShellManager` |
| **Local Edge Inference** | Llama.cpp JNI لتشغيل نماذج GGUF محلياً بدون إنترنت | `LlamaCppInferenceEngine`, `LocalInferenceEngine`, `LocalModelManagerScreen` |
| **Multi-Agent Swarm** | المنسق يفكك المهام؛ نسخ Worker تعمل بالتوازي | `SwarmOrchestrator`, `AgentPipeline`, `IntentClassifier` |
| **Duplex Voice UI** | اكتشاف كلمة التنبيه + STT + TTS + فقاعة عائمة | `VoiceAssistantService`, `OmniBubbleService`, `AdvancedVoiceCommandEngine` |

### البنية الكلية (4 طبقات رئيسية)

```
OmniDev Workspace
│
├── 🎯 DOMAIN LAYER (domain/)
│   ├── engine/
│   │   ├── AgentPipeline.kt          ← ReAct Loop الأساسي
│   │   ├── SwarmOrchestrator.kt      ← Multi-Agent Orchestration
│   │   ├── IntentClassifier.kt       ← تصنيف النوايا
│   │   ├── AutoHealBuildUseCase.kt   ← إصلاح البناء التلقائي
│   │   └── OmniMode.kt               ← أوضاع التشغيل
│   └── attachment/
│       └── AttachmentProcessor.kt    ← معالج المرفقات
│
├── 💾 DATA LAYER (data/)
│   ├── tools/                        ← 70+ أداة ذكاء اصطناعي
│   ├── brain/                        ← Agent Brain System (NEW)
│   ├── ipc/                          ← IPC & Shizuku Integration
│   ├── repository/                   ← مخازن البيانات
│   ├── db/                           ← Room Database v7
│   ├── network/                      ← Completion API & VPN
│   ├── accessibility/                ← Semantic UI Control
│   ├── auth/                         ← GitHub OAuth & Copilot
│   ├── integration/                  ← Telegram, Discord, WhatsApp
│   ├── localllm/                     ← Llama.cpp Edge Inference
│   └── [10+ modules إضافية]
│
├── 🎨 UI LAYER (ui/)
│   ├── chat/                         ← الواجهة الرئيسية
│   ├── settings/                     ← 7 شاشات إعدادات
│   ├── analytics/                    ← لوحة Analytics
│   ├── debug/                        ← Debug Console
│   ├── overlay/                      ← Floating Bubble
│   └── theme/                        ← Material 3 Theme
│
└── 🔌 SERVICES & RECEIVERS (12 خدمة)
    ├── OmniCoreService (AIDL IPC)
    ├── OmniAccessibilityService
    ├── OmniInputMethodService (IME)
    ├── OmniDevVpnService
    ├── OmniSyncService
    ├── OmniMediaSessionService
    └── [6+ خدمات إضافية]
```

---

## 2. نظام Agent Brain الجديد

> **أُضيف في 2026-04-02** — يجعل الـ Agent مثل Claude Code / GitHub Copilot Agent

```
╔══════════════════════════════════════════════════════════════════╗
║              🧠 AGENT BRAIN SYSTEM                               ║
╚══════════════════════════════════════════════════════════════════╝

data/brain/
├── SmartLearningBridge.kt      ← الجسر الذكي المنسق (القلب الجديد)
├── ToolExecutionJournal.kt     ← مجلة التنفيذ الدائمة (الذاكرة الكاملة)
└── ToolAwarenessEngine.kt      ← محرك الوعي بالأدوات والبيئة

data/db/entities/
├── ToolExecutionEntry.kt       ← كيان سجل التنفيذ
└── SystemKnowledgeEntry.kt     ← كيان قاعدة معرفة النظام

data/db/dao/
├── ToolExecutionDao.kt         ← DAO للوصول لسجل التنفيذ
└── SystemKnowledgeDao.kt       ← DAO لقاعدة معرفة النظام

ui/brain/
├── AgentBrainDashboard.kt      ← واجهة Compose لعرض حالة الذكاء
└── AgentBrainViewModel.kt      ← ViewModel للوحة تحكم Brain
```

### كيف يعمل النظام:

```
كل تنفيذ أداة ↓
SmartLearningBridge.onToolExecutionEnd()
    ├── ToolExecutionJournal  → حفظ دائم في SQLite
    ├── ToolAwarenessEngine   → تعلم من الخطأ/النجاح
    ├── ToolIntelligenceEngine → تحديث Q-Learning
    ├── ToolMachineLearningEngine → تحديث النماذج
    └── ToolMonitoringSystem  → مراقبة الأداء

في كل System Prompt ↓
SmartLearningBridge.buildFullContextEnrichment()
    ├── وعي البيئة (Termux, Shizuku, Git, Python, etc.)
    ├── ذاكرة التنفيذ (إحصائيات، أخطاء، أنماط)
    ├── أفضل الممارسات المكتسبة
    └── توصيات الأداة التالية
```

### قاعدة البيانات (v7):
```sql
tool_execution_log:    سجل كل تنفيذ أداة بتفاصيله الكاملة
system_knowledge:      قاعدة معرفة بيئة النظام والأدوات
```

---

## 3. تدفقات التنفيذ الأساسية

### 3.1 AgentPipeline — حلقة ReAct
**الملف:** `domain/engine/AgentPipeline.kt`

المحرك المركزي. يشغّل حلقة **Reason → Act → Observe**:

1. يرسل system prompt + history + tool definitions للنموذج
2. يُحلل الاستجابة بحثاً عن tool calls
3. ينفذ الأدوات عبر `CompositeToolManager`
4. يُضيف نتائج الأدوات للسياق ويكرر
5. ينتهي عند `FINAL_ANSWER`، حد التكرارات، ميزانية الـ tokens، أو انتهاء المهلة الزمنية

**الإعدادات:** `AgentConfig` — `maxIterations` (50)، `tokenBudget`، `maxExecutionTimeMs`، `maxIterationTimeMs` (3 دقائق)، `maxRepeatToolCalls`

**تدفق الأحداث:** `AgentEvent` — `Thinking`، `ToolCall`، `ToolResult`، `StreamChunk`، `FinalAnswer`، `Error`

### 3.2 SwarmOrchestrator
**الملف:** `domain/engine/SwarmOrchestrator.kt`

يُجزئ الطلبات المعقدة إلى ≤10 مهام فرعية، ويُفرخ worker `AgentPipeline` coroutines بالتوازي، ويُجمع النتائج.

**الأحداث:** `SwarmEvent` — `PlanReady`، `WorkerStarted`، `WorkerCompleted`، `WorkerFailed`، `FinalSynthesis`

### 3.3 IntentClassifier
**الملف:** `domain/engine/IntentClassifier.kt`

موجّه نوايا سريع قائم على LLM يصنف المدخل إلى `OmniMode` قبل التوجيه للـ pipeline المناسب.

### 3.4 OmniMode — أوضاع التشغيل
**الملف:** `domain/engine/OmniMode.kt`

```
FAST          ← 5 iterations max، model: gpt-4o-mini
BALANCED      ← 25 iterations max، model: gpt-4o
THOROUGH      ← 50 iterations max، model: claude-opus-4
SWARM         ← Multi-agent orchestration
AUTONOMOUS    ← Unattended execution + auto-healing
VOICE         ← VoiceAssistantService mode
DEBUG         ← DebugConsoleScreen logging
```

### 3.5 AutoHealBuildUseCase
**الملف:** `domain/engine/AutoHealBuildUseCase.kt`

يُشغّل `./gradlew build`، يُحلل أخطاء المترجم، ويستدعي `AgentPipeline` لتصحيح الملفات تلقائياً في حلقة retry.

### تدفق التنفيذ الكامل

```
User Input (Chat / Voice / Intent)
            ↓
    IntentClassifier  →  تصنيف النية
            ↓
    ModelRegistry     →  اختيار LLM (Anthropic / OpenAI / Copilot / Llama.cpp)
            ↓
    AgentPipeline ReAct Loop  [max 25-50 iterations]
    ├── REASON: إرسال prompt + tools للنموذج
    ├── ACT:    تنفيذ tool calls عبر CompositeToolManager
    ├── OBSERVE: إضافة النتائج للسياق
    └── LOOP حتى: text output | max iterations
            ↓
    CompositeToolManager
    ├── يُوجه لـ FileToolManager، SystemTools، إلخ
    ├── ينفذ مع timeout + error handling
    └── يُعيد ToolCallResult (success + output)
            ↓
    Response Generation
    ├── يُبث النص للواجهة في الوقت الفعلي
    ├── يحفظ في ChatRepository
    └── يُحدث MemoryManager للسياق المستقبلي
            ↓
    Output (UI / Voice / Notification)
```

---

## 4. توجيه النماذج — ModelRegistry

**الملف:** `registry/ModelRegistry.kt`

سجل مركزي لكل النماذج المدعومة. يوفر:
- `getModelById(id)` / `findModelById(id)` — البحث عن نموذج
- `getModelsForTier(tier)` — تصفية حسب `ModelTier` (FAST, BALANCED, POWERFUL, LOCAL)
- `getBestModelForTier(tier, preferredProvider?)` — اختيار ذكي
- `getDefaultModelForRole(role)` — افتراضيات حسب الدور (ORCHESTRATOR, WORKER, CODER, RESEARCHER…)
- `addDynamicCopilotModels(models)` / `clearDynamicCopilotModels()` — حقن نماذج في وقت التشغيل

**المزودون:** OpenAI، Anthropic، Google Gemini، Mistral، Groq، Cerebras، GitHub Copilot، Local (Llama.cpp)

**المصادقة:**
- `CopilotSessionManager` — GitHub OAuth device-flow لـ Copilot token
- `CopilotModelRefresher` — تحديث قائمة نماذج Copilot
- `GitHubDeviceFlowManager` — GitHub OAuth device flow عام
- `OAuthManager` — مساعد OAuth عام
- `ApiKeyRepository` — DataStore مشفر لكل مفاتيح API

---

## 5. نظام الأدوات — الدليل الكامل

كل الأدوات مسجلة وتُرسل عبر `CompositeToolManager` (`data/tools/CompositeToolManager.kt`).

### 5.1 أدوات الملفات والطرفية (FileToolManager)

| اسم الأداة | الوصف |
|-----------|-------|
| `read_file` | قراءة محتوى الملف |
| `write_file` / `create_file` | كتابة / إنشاء ملفات |
| `delete_file` | حذف الملفات |
| `search_files` | بحث بـ regex في محتوى الملفات |
| `patch_file` | تطبيق unified diff patches |
| `terminal` | تشغيل أوامر shell |
| `grep_search` | بحث كود مدعوم بـ Ripgrep |
| `find_files` | البحث عن ملفات بنمط |
| `file_permissions` | قراءة/ضبط صلاحيات UNIX |
| `disk_usage` | معلومات du/df |
| `archive_tool` | عمليات zip/tar |

### 5.2 أدوات الويب والشبكة

| اسم الأداة | الوصف |
|-----------|-------|
| `web_search` | بحث DuckDuckGo/Bing + جلب عميق (موازي، 6000 حرف/موقع) |
| `web_search_deep` | جلب محتوى كامل لأفضل N نتائج |
| `web_scraper` | استخراج صفحة واحدة |
| `scrape_multiple` | استخراج موازي لـ ≤8 URLs (12s timeout، 8000 حرف لكل) |
| `headless_browser` | أتمتة متصفح كاملة (JavaScript rendering) |
| `network_request` | HTTP GET/POST/PUT/DELETE مع headers |
| `network_monitor` | مراقبة حركة مرور VPN (TUN interceptor، بدون root) |

### 5.3 أدوات الذاكرة

| اسم الأداة | الوصف |
|-----------|-------|
| `remember_fact` | حفظ حقيقة في قاعدة بيانات SQLite |
| `search_knowledge` | بحث دلالي في قاعدة المعرفة |
| `update_memory` | تحديث مدخل ذاكرة موجود |
| `delete_memory` | حذف مدخل ذاكرة |
| `vector_store` | إضافة embeddings للمخزن المتجهي |
| `vector_search` | بحث تقريبي عن أقرب جار |
| `vector_similar` | إيجاد عناصر مشابهة بـ cosine similarity |

### 5.4 أدوات التواصل والشبكات الاجتماعية

| اسم الأداة | الوصف |
|-----------|-------|
| `communicate_tool` | إرسال SMS، بريد إلكتروني، إشعارات |
| `telegram_publisher` | نشر على قناة/مجموعة Telegram |
| `telegram_bot` | Telegram Bot API كامل (إرسال/استقبال/وسائط) |
| `discord_bot` | Discord Bot API |
| `discord_publisher` | نشر rich embeds على Discord webhook |
| `slack_tool` | Slack Web API |
| `send_grid_email` | بريد إلكتروني عبر SendGrid |
| `notion_publisher` | إنشاء/تحديث صفحات Notion |
| `n8n_automation` | تشغيل n8n workflows عبر webhook |
| `whatsapp` | WhatsApp Cloud API |
| `whatsapp_bridge` | WhatsApp عبر bridge محلي |
| `social_media_video` | yt-dlp + noembed: get_info, download, search_youtube |

### 5.5 أدوات النظام وOS

| اسم الأداة | الوصف |
|-----------|-------|
| `hardware_toggle_tool` | تبديل WiFi، Bluetooth، وضع الطيران، الكشاف |
| `system_power` | إعادة التشغيل، الإيقاف، وضع الاسترداد |
| `get_device_info` | معلومات البناء، RAM، التخزين، البطارية، CPU |
| `get_current_location` | موقع GPS + الشبكة |
| `app_manager_tool` | تثبيت/إزالة/تشغيل/إيقاف التطبيقات |
| `permission_manager` | منح/سحب الصلاحيات برمجياً |
| `device_admin` | إجراءات Device Admin |
| `android_intent` | تشغيل Intents تعسفية |
| `shizuku_command` | أوامر ADB-level عبر Shizuku |
| `agent_runtime` | python_run، node_run، shell_script، pip_install |
| `agent_sandbox` | تنفيذ معزول وآمن |
| `advanced_terminal` | طرفية موسعة مع إدارة بيئة |
| `task_scheduler` | جدولة مهام متكررة/لمرة واحدة |
| `system_contacts` | قراءة/بحث جهات الاتصال |
| `clipboard` | قراءة/كتابة الحافظة |
| `sms_reader` | قراءة رسائل SMS |
| `call_log` | قراءة سجل المكالمات |
| `media_control` | التحكم في تشغيل الوسائط |
| `ime_tool` | التحكم في OmniDev IME |

### 5.6 أدوات الواجهة وإمكانية الوصول

| اسم الأداة | الوصف |
|-----------|-------|
| `ui_automation` | النقر والتمرير والكتابة عبر Accessibility |
| `semantic_ui` | قراءة شجرة UI الدلالية |
| `visual_inspector` | فحص العناصر المرئية بالصورة |
| `autofill_assist` | ملء الحقول عبر IME/Accessibility |

### 5.7 أدوات التطوير والكود

| اسم الأداة | الوصف |
|-----------|-------|
| `git_manager` | Git init/add/commit/push/pull/log/diff/branch |
| `github_manager` | GitHub API: repos، issues، PRs، actions |
| `request_github_auth` | GitHub OAuth device-flow |
| `analyze_logcat` | تحليل وتصفية logcat |
| `app_manifest_analyzer` | تحليل AndroidManifest.xml |
| `enhanced_manifest_analyzer` | APK عميق: SHA hashes، native libs، metadata |
| `quality_security_tool` | فحص جودة وأمان الكود |
| `execution_diagnostics` | تشخيص أخطاء تنفيذ الأدوات |
| `god_eye_profiler` | تنميط أداء شامل للنظام |

### 5.8 أدوات IPC والامتداد

| اسم الأداة | الوصف |
|-----------|-------|
| `omni_link` | ربط بالامتدادات الخارجية عبر `IOmniExtensionInterface` AIDL |
| `system_launcher_tool` | التحكم بـ OmniDev Launcher |
| `widget_generator_tool` | رسم Compose widgets على المشغّل |
| `omni_core_agent` | كشف قدرات الـ agent للتطبيقات الخارجية |

### 5.9 أدوات الذكاء المتقدم

| اسم الأداة | الوصف |
|-----------|-------|
| `predictive_analytics` | تنبؤ بالسلاسل الزمنية واكتشاف الشذوذات |
| `security_analyzer` | تحليل أمان APK ثابت/ديناميكي متقدم |
| `intelligent_automation` | محرك أتمتة workflows معقد |
| `voice_commands` | محرك الأوامر الصوتية الكاملة |
| `tool_monitoring` | مقاييس أداء الأدوات في الوقت الفعلي |

---

## 6. محركات الذكاء والأتمتة

كلها موجودة في `data/tools/*/`:

### ToolMachineLearningEngine (`data/tools/ml/`)
يُدرّب نماذج ML متعددة على تاريخ تنفيذ الأدوات. يتوقع الأداة الأمثل التالية باستخدام Naive Bayes، KNN، Decision Tree، وشبكة عصبية.

### ToolIntelligenceEngine (`data/tools/orchestration/`)
محرك تعزيز قائم على Q-Learning. يحتفظ بـ Q-values لكل أداة، ويتتبع التفضيلات السياقية (وقت اليوم، مستوى البطارية).

### ToolOrchestrator (`data/tools/orchestration/`)
طبقة بنية تحتية توفر TTL-based result caching وحماية circuit-breaker حول استدعاءات الأدوات.

### PredictiveAnalyticsEngine (`data/tools/prediction/`)
محرك تحليلات سلاسل زمنية. يدعم: تنبؤ ARIMA-like، اكتشاف شذوذات، وتحليل اتجاه.

### ToolMonitoringSystem (`data/tools/monitoring/`)
جامع مقاييس singleton في الوقت الفعلي. يتتبع عدد التنفيذات، معدل النجاح، p99 latency.

### IntelligentAutomationEngine (`data/tools/automation/`)
نظام أتمتة workflows. يدعم: إجراءات متسلسلة/متوازية، فروع شرطية، حلقات، مكالمات API.

### AdvancedSecurityAnalyzer (`data/tools/security/`)
تحليل أمان ثابت/ديناميكي عميق للحزم. يحلل: الصلاحيات، المكتبات الأصلية، إعدادات الشبكة، الممارسات التشفيرية.

### AdvancedVoiceCommandEngine (`data/tools/voice/`)
خط أنابيب كامل للأوامر الصوتية: SpeechRecognizer، اكتشاف كلمة التنبيه، NLP، TTS، دعم متعدد اللغات (عربي/إنجليزي).

---

## 7. IPC وتكامل النظام

### 7.1 واجهات AIDL

| الملف | الحزمة | الغرض |
|-------|--------|-------|
| `ipc/IOmniCoreInterface.aidl` | `com.omnidev.workspace.ipc` | **نشط** — واجهة 4 methods لـ OmniCoreService |
| `ipc/IOmniResponseCallback.aidl` | `com.omnidev.workspace.ipc` | Streaming callback |
| `extension/ipc/IOmniExtensionInterface.aidl` | `com.omnidev.extension.ipc` | ربط الامتدادات |
| `launcher/ipc/IOmniLauncherInterface.aidl` | `com.omnidev.launcher.ipc` | التحكم بالمشغّل + الـ widgets |

> **ملاحظة:** `com.omnidev.workspace.IOmniCoreInterface.aidl` القديم في الجذر تم **حذفه** — استبدله الإصدار في حزمة `ipc/`.

### 7.2 خدمات ومديرو IPC

| الفئة | الدور |
|-------|-------|
| `OmniCoreService` | يكشف `IOmniCoreInterface` binder. يُطبق `CONTROL_CORE` permission |
| `OmniCoreAgentTool` | wrapper أداة تستدعي `OmniCoreService` من الـ agent |
| `LauncherConnectionManager` | يربط بـ OmniDev Launcher عبر `IOmniLauncherInterface` |
| `ExtensionConnectionManager` | يكتشف ويربط امتدادات Omni-Link |
| `PrivilegedExecutionManager` | يُوجه أوامر shell المميزة عبر Shizuku أو root |
| `RishShellManager` | تكامل rish/ish shell |

### 7.3 تكامل Shizuku
- `ShizukuCommandTool` — يلفّ `Shizuku.newProcess()` لتنفيذ ADB-level
- `PrivilegedExecutionManager.executeCommand()` يبوّب على `ShizukuCommandTool.isAvailable()` فقط (ليس `isShizukuReady()`) لتجنب race condition

> **قاعدة حرجة:** يجب استدعاء Shizuku مباشرة من الكلاسبات المجمّعة، ليس عبر reflection.

---

## 8. القدرات متعددة الوسائط

| القدرة | الخدمة / الفئة | ملاحظات |
|--------|--------------|---------|
| التقاط الشاشة | `OmniScreenCaptureService` | MediaProjection |
| شجرة إمكانية الوصول | `OmniAccessibilityService`, `SemanticTreeParser` | يمشي `AccessibilityNodeInfo` |
| أتمتة الواجهة | `UIAutomationTool`, `GodModeAccessibility` | نقر/تمرير/كتابة |
| الفحص المرئي | `VisualInspectorTool` | لقطة شاشة + تراكب عناصر |
| التعرف على الكلام | `AdvancedVoiceCommandEngine` | SpeechRecognizer + wake word |
| تحويل النص لكلام | `AdvancedVoiceCommandEngine` | TTS مع دعم عربي/إنجليزي |
| المساعد الصوتي | `VoiceAssistantService`, `VoiceManager` | خدمة صوتية خلفية |
| تكامل IME | `OmniInputMethodService` | لوحة مفاتيح مخصصة |
| تراكب عائم | `OmniBubbleService` | فقاعة agent دائمة |
| جلسة الوسائط | `OmniMediaSessionService` | التحكم في التشغيل |

---

## 9. طبقة الاستمرارية (Persistence)

### 9.1 قاعدة بيانات Room (SQLite)
**الملف:** `data/db/OmniDevDatabase.kt` — **الإصدار الحالي: 7**

| الكيان | الجدول | DAO |
|--------|-------|-----|
| `ChatMessageEntity` | `chat_messages` | `ChatMessageDao` |
| `ChatSessionEntity` | `chat_sessions` | `ChatSessionDao` |
| `KnowledgeSnippet` | `knowledge` | `KnowledgeDao` |
| `ToolExecutionEntry` | `tool_execution_log` | `ToolExecutionDao` |
| `SystemKnowledgeEntry` | `system_knowledge` | `SystemKnowledgeDao` |

### 9.2 DataStore (تفضيلات مشفرة)

| المستودع | البيانات المخزنة |
|---------|----------------|
| `SettingsRepository` | system prompt، اختيار النموذج، درجة الحرارة، max tokens، ملف المستخدم |
| `ApiKeyRepository` | مفاتيح API لكل المزودين (OpenAI، Anthropic، Gemini، Groq، إلخ) |
| `AnalyticsRepository` | تحليلات الاستخدام |

### 9.3 تاريخ هجرة قاعدة البيانات
```
v1 → v2: الهيكل الأساسي
v2 → v3: إضافة knowledge snippets
v3 → v4: تحسينات الجلسة
v4 → v5: إضافة consoleEntriesJson
v5 → v6: إضافة metadata
v6 → v7: إضافة Agent Brain (tool_execution_log + system_knowledge)
```

---

## 10. طبقة الواجهة (UI)

كل الشاشات Jetpack Compose. التنقل عبر `AppNavigation.kt`.

| الشاشة | ViewModel | الغرض |
|--------|-----------|-------|
| `ChatScreen` | `ChatViewModel` | واجهة المحادثة الرئيسية |
| `DebugScreen` | `DebugViewModel` | logcat مباشر + عرض الأعطال |
| `AISettingsScreen` | `AISettingsViewModel` | النموذج، درجة الحرارة، system prompt |
| `ProvidersScreen` | `ProvidersViewModel` | إدارة مفاتيح API |
| `IntegrationsScreen` | — | إعدادات Telegram/Discord/Slack/n8n |
| `LocalModelManagerScreen` | — | تنزيل/إدارة نماذج GGUF المحلية |
| `MemoryExplorerScreen` | — | تصفح/تعديل مدخلات قاعدة المعرفة |
| `ScheduledTasksScreen` | — | عرض/إدارة المهام المجدولة |
| `SystemPromptEditorScreen` | — | تعديل system prompt بالقوالب |
| `ToolRegistryScreen` | — | قائمة أدوات مباشرة مع الأوصاف |
| `UserProfileScreen` | — | ملف المستخدم (اسم، بريد، هاتف، عنوان) |
| `AnalyticsDashboardScreen` | `AnalyticsDashboardViewModel` | مخططات الاستخدام وتحليلات الجلسة |
| `AgentBrainDashboard` | `AgentBrainViewModel` | لوحة حالة الذاكرة والذكاء |

**المكونات:**
- `AgentLiveConsole` — وحدة تحكم streaming مباشرة مع accordion لنتائج الأدوات
- `MarkdownText` — محوّل Markdown لـ Compose
- `MessageFormatter` — تنسيق مخرجات LLM الخام
- `ConfirmationGate` — حوار موافقة للإجراءات المدمرة
- `OmniBubbleService` — فقاعة chat عائمة

---

## 11. الخدمات والمستقبلات

| المكوّن | النوع | الغرض | الحالة |
|---------|-------|-------|--------|
| `OmniAccessibilityService` | AccessibilityService | شجرة UI + حقن input | 🟢 Core |
| `OmniInputMethodService` | InputMethodService | IME مخصص | 🟢 Core |
| `VoiceAssistantService` | Service | مستمع صوتي خلفي | 🟢 Core |
| `OmniDevVpnService` | VpnService | مراقب حركة TUN | 🟢 Core |
| `OmniCoreService` | Service | AIDL IPC للتطبيقات المرافقة | 🟢 Core |
| `OmniSyncService` | Service | مزامنة خلفية / مشغّل مهام | 🟢 Active |
| `AgentNotificationService` | Service | إشعار foreground لتشغيل الـ agent | 🟢 Active |
| `OmniMediaSessionService` | Service | التحكم في جلسة الوسائط | 🟢 Active |
| `OmniScreenCaptureService` | Service | التقاط شاشة MediaProjection | 🟢 Active |
| `OmniBubbleService` | Service | تراكب واجهة عائم | 🟢 Active |
| `DiscordPollingService` | Service | Long-polls Discord | 🟢 Active |
| `TelegramPollingService` | Service | Long-polls Telegram Bot API | 🟢 Active |
| `WhatsAppBridgeService` | Service | يحافظ على اتصال bridge محلي | 🟢 Active |
| `BootReceiver` | BroadcastReceiver | تشغيل تلقائي عند الإقلاع | 🟢 Active |
| `OmniSmsReceiver` | BroadcastReceiver | اعتراض SMS الواردة | 🟢 Active |
| `OmniDeviceAdminReceiver` | DeviceAdminReceiver | إدارة الجهاز | 🟢 Active |

---

## 12. واجهات AIDL

```
app/src/main/aidl/
├── com/omnidev/workspace/
│   └── ipc/
│       ├── IOmniCoreInterface.aidl     # 4-method IPC  
│       └── IOmniResponseCallback.aidl  # Streaming callback
├── com/omnidev/extension/
│   └── ipc/
│       └── IOmniExtensionInterface.aidl  # Omni-Link
└── com/omnidev/launcher/
    └── ipc/
        └── IOmniLauncherInterface.aidl   # التحكم بالمشغّل
```

**Methods الـ `IOmniCoreInterface`:**
- `getSystemStatus(): Int`
- `executeSystemCommand(command, contextData)`
- `askAgentSilent(prompt)`
- `streamAgentResponse(prompt, callback)`

---

## 13. خريطة ملفات المشروع

```
app/src/main/
├── aidl/com/omnidev/
│   ├── workspace/ipc/             IOmniCoreInterface, IOmniResponseCallback
│   ├── extension/ipc/             IOmniExtensionInterface
│   └── launcher/ipc/              IOmniLauncherInterface
│
└── java/com/omnidev/workspace/
    ├── OmniDevApp.kt              Application class (init + Agent Brain)
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
    │   │   ├── FileToolManager.kt       أدوات الملفات والطرفية
    │   │   ├── MemoryManager.kt         قاعدة معرفة SQLite
    │   │   ├── VectorMemoryManager.kt   مخزن Embedding متجهي
    │   │   ├── SystemAssistantTools.kt  تواصل، مخطط، جهاز، تطبيقات
    │   │   ├── AdvancedSystemTools.kt   root shell، تثبيت packages، إعدادات
    │   │   ├── HeadlessBrowserManager.kt ← متصفح خفي (WebView)
    │   │   ├── OmniNativeToolsManager.kt ← تحميل وتشغيل أدوات native
    │   │   ├── [جميع ملفات الأدوات الفردية...]
    │   │   ├── automation/         IntelligentAutomationEngine
    │   │   ├── ml/                 ToolMachineLearningEngine
    │   │   ├── monitoring/         ToolMonitoringSystem
    │   │   ├── orchestration/      ToolOrchestrator, ToolIntelligenceEngine
    │   │   ├── prediction/         PredictiveAnalyticsEngine
    │   │   ├── security/           AdvancedSecurityAnalyzer, OmniNativeToolsManager
    │   │   └── voice/              AdvancedVoiceCommandEngine
    │   ├── vision/                OmniScreenCaptureService
    │   └── voice/                 VoiceAssistantService, VoiceManager
    │
    ├── domain/
    │   ├── attachment/            AttachmentProcessor
    │   └── engine/                AgentPipeline, SwarmOrchestrator, IntentClassifier, OmniMode
    │
    ├── registry/
    │   └── ModelRegistry.kt       كل نماذج AI + منطق التوجيه
    │
    └── ui/
        ├── analytics/             AnalyticsDashboardScreen + ViewModel
        ├── brain/                 AgentBrainDashboard + ViewModel
        ├── chat/                  ChatScreen, ChatViewModel, AgentLiveConsole
        ├── debug/                 DebugScreen + ViewModel
        ├── navigation/            AppNavigation
        ├── overlay/               OmniBubbleService
        ├── providers/             ProvidersScreen + ViewModel
        ├── settings/              7 شاشات إعدادات + ViewModels
        └── theme/                 Color, Theme, Type
```

---

## 14. المكدس التقني والمكتبات

| الفئة | التقنية | الإصدار |
|-------|---------|---------|
| اللغة | Kotlin | 2.0.21 |
| الواجهة | Jetpack Compose + Material3 | BOM 2024.11.00 |
| حقن التبعيات | Manual DI (بدون Hilt حالياً) | — |
| غير متزامن | Kotlin Coroutines + Flow | 1.8.1 |
| قاعدة بيانات محلية | Room (SQLite) | 2.6.1 |
| التفضيلات | AndroidX DataStore | 1.1.1 |
| HTTP | Retrofit 2 + OkHttp | 2.11.0 + 4.12.0 |
| التسلسل | kotlinx.serialization | 1.7.3 |
| LLM محلي | Llama.cpp (JNI, GGUF) | NDK 27.0.12077973 |
| تنفيذ مميز | Shizuku | 13.1.5 |
| رؤية حاسوبية | CameraX + ML Kit OCR | 1.3.3 + 16.0.0 |
| HTML parsing | Jsoup | 1.17.2 |
| Build | Gradle 8.9, AGP 8.7.3, KSP 2.0.21 |
| Min SDK | 24 (Android 7) | |
| Target SDK | 35 (Android 15) | |

---

## 15. البدء والـ CI/CD

### البناء
```bash
# Debug APK
./gradlew assembleDebug

# Lint
./gradlew lint

# Unit tests
./gradlew test
```

### CI Pipeline (`.github/workflows/android-ci.yml`)
- يُشغَّل عند push لـ `main` وكل PRs
- الخطوات: checkout → JDK 17 → Android SDK → NDK → استعادة cache → lint → build

### إعداد الميزات المميزة
```bash
# الصلاحيات الأساسية (مطلوبة لـ Shizuku)
adb shell pm grant com.omnidev.workspace android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.omnidev.workspace android.permission.DUMP
adb shell appops set com.omnidev.workspace SYSTEM_ALERT_WINDOW allow
```

---

## 16. مرجع صلاحيات Android

الصلاحيات المحمية/النظام تتطلب منح ADB أو Shizuku:

| الصلاحية | الغرض |
|---------|-------|
| `WRITE_SECURE_SETTINGS` | تعديل إعدادات النظام |
| `DUMP` | قراءة dumps خدمات النظام |
| `BIND_ACCESSIBILITY_SERVICE` | ربط خدمة إمكانية الوصول |
| `BIND_INPUT_METHOD` | ربط IME |
| `RECEIVE_SMS` | التقاط SMS للـ bridge |
| `SYSTEM_ALERT_WINDOW` | تراكب عائم |
| `CONTROL_CORE` | حارس على `OmniCoreService` binder |

> **لا تضف** `android.permission.BIND_VPN_SERVICE` في `<uses-permission>`. يُعلَن فقط كـ `android:permission` على مدخل `<service>` الخاص بـ `OmniDevVpnService`.

---

## 17. اتفاقيات المعمارية وقواعد الـ Agents

1. **نوع إرجاع الأداة** — دائماً أرجع `ToolExecutionResult(output: String, isError: Boolean = false)`. لا تستخدم `result.success` — استخدم `!result.isError`.

2. **تسجيل الأداة** — كل أداة جديدة **يجب** تسجيلها في `getToolDefinitions()` **و** `executeTool()` في `CompositeToolManager`.

3. **AIDL** — حزمتان فقط نشطتان: `com.omnidev.workspace.ipc` (core) و `com.omnidev.launcher.ipc` (launcher).

4. **Shizuku** — بوّب على `ShizukuCommandTool.isAvailable()` فقط (ليس `isShizukuReady()`) داخل `PrivilegedExecutionManager`.

5. **Lint / NewAPI** — أي استدعاء `queryIntentServices()` يستهدف API ≥ 33 يجب أن يكون داخل `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)`.

6. **VpnService** — `BIND_VPN_SERVICE` هو `android:permission` على مدخل `<service>` فقط، ليس في `<uses-permission>`.

7. **هجرات DB** — دائماً زِد إصدار DB في `OmniDevDatabase` وأضف كائن هجرة مسمّى. الإصدار الحالي: **7**.

8. **نشر الـ Context** — مرر `Context` عبر مُنشئ `CompositeToolManager`، ليس بشكل ثابت.

9. **Coroutines** — كل methods الـ `execute()` للأدوات تستخدم `withContext(Dispatchers.IO)`. لا تستدعِ I/O blocking في الـ main thread.

10. **محركات `data/tools/*/` الجديدة** — كل محرك له مسؤولية واحدة. المحركات ذات `execute(action, args)` عامة تُكشف كأدوات. المحركات الداخلية/البنية التحتية لا تُسجَّل.

---

## 18. دليل التطوير السريع

### إضافة أداة جديدة
```
1. أنشئ: data/tools/YourNewTool.kt
2. نفّذ: ToolManager interface (أو استخدم object مع static methods)
3. أضف getToolDefinitions() ← تعريف الأداة والمعاملات
4. سجّل في: CompositeToolManager.getToolDefinitions() + executeTool()
5. حدّث هذا الملف
```

### إضافة خدمة جديدة
```
1. أنشئ: data/YourNewService.kt (يمتد Service)
2. سجّل في: AndroidManifest.xml
3. إذا كان AIDL: أضف ملف .aidl
4. أضف للـ BootReceiver إذا احتجت تشغيل تلقائي
5. حدّث هذا الملف
```

### إضافة شاشة جديدة
```
1. أنشئ: ui/screens/YourNewScreen.kt (Composable)
2. أضف للـ NavGraph في AppNavigation.kt
3. اربطها مع ViewModel
4. حدّث هذا الملف
```

### نقاط الدخول الرئيسية

| الدخول | الملف | الغرض |
|-------|-------|-------|
| **MainActivity** | `MainActivity.kt` | Launch activity + DI يدوي |
| **OmniDevApp** | `OmniDevApp.kt` | Application class + Agent Brain init |
| **OmniCoreService** | `OmniCoreService.kt` | AIDL IPC entry point |
| **BootReceiver** | `BootReceiver.kt` | تشغيل تلقائي عند الإقلاع |
| **VoiceAssistantService** | `VoiceAssistantService.kt` | مستمع كلمة التنبيه |
| **OmniAccessibilityService** | `OmniAccessibilityService.kt` | أتمتة الواجهة |

### الملفات الأهم للمراجعة السريعة

| الأولوية | الملف | السبب |
|---------|-------|-------|
| 1️⃣ | `domain/engine/AgentPipeline.kt` | **قلب النظام** — ReAct Loop |
| 2️⃣ | `data/tools/CompositeToolManager.kt` | **موحد الأدوات** — كل التوجيه |
| 3️⃣ | `data/network/CompletionService.kt` | **توجيه LLM** — كل استدعاءات API |
| 4️⃣ | `data/brain/SmartLearningBridge.kt` | **قلب الذاكرة** — السياق والتعلم |
| 5️⃣ | `registry/ModelRegistry.kt` | **اختيار النموذج** |
| 6️⃣ | `ui/screens/ChatScreen.kt` | **الواجهة الرئيسية** |
| 7️⃣ | `OmniDevApp.kt` | **نقطة البدء** + تهيئة |

---

## 19. المشكلات المكتشفة وخطة العمل

### 🔴 ملفات AIDL مكررة (تم الحل جزئياً)
```diff
✅ حُل: حُذف الملف القديم
   com/omnidev/workspace/IOmniCoreInterface.aidl   [محذوف]
✅ الاحتفاظ بـ:
   com/omnidev/workspace/ipc/IOmniCoreInterface.aidl [نشط]
```

### 🟡 أدوات ذات وظائف متداخلة (للدراسة)
```
TelegramBotTool vs TelegramPublisherTool     ← أغراض مختلفة (OK)
DiscordBotTool vs DiscordPublisherTool       ← أغراض مختلفة (OK)
WhatsAppTool vs WhatsAppBridgeTool           ← تكاملات مختلفة (OK)
MemoryManager vs VectorMemoryManager         ← ذاكرتان متكاملتان (OK)
```

### 📋 خطة العمل

#### المرحلة 1: تنظيف ✅
- [x] فحص ملفات AIDL المكررة
- [x] حذف AIDL القديم وتحديث الـ imports

#### المرحلة 2: Agent Brain ✅
- [x] SmartLearningBridge
- [x] ToolExecutionJournal
- [x] ToolAwarenessEngine
- [x] هجرة قاعدة البيانات v7

#### المرحلة 3: OmniNativeToolsManager ✅
- [x] تحميل Python، aapt2، jadx، apktool
- [x] تنفيذ عبر Shizuku بدون Termux

#### المرحلة 4: قيد التنفيذ
- [ ] استكمال تكامل Dynamic Tool Registry
- [ ] CAMPS daemon (Magisk systemless module)
- [ ] Root كامل عبر mtkclient

---

> **آخر تحديث:** 2026-04-16
> **الصيانة:** يجب تحديث هذا الملف عند إضافة/حذف أي Tool أو Service أو تغيير معماري
