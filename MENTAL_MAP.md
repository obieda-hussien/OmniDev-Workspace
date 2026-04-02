# 🧠 OmniDev Workspace — خريطة ذهنية شاملة (Mental Map)

> **الغرض:** قراءة واحدة توفر **100% من السياق** — بدون الحاجة لقراءة الملفات التفصيلية كل مرة.  
> **آخر تحديث:** 2026-04-02  
> **الهدف:** توفير ألف tokens وإعطاء أي AI Agent صورة فورية شاملة

---

## 📊 الإحصائيات الأساسية

| المقياس | القيمة |
|---------|--------|
| **إجمالي ملفات Kotlin** | 163 ملف |
| **إجمالي أسطر الكود** | ~60,000+ سطر |
| **أدوات (Tools)** | 57 أداة |
| **خدمات (Services)** | 12 خدمة |
| **واجهات AIDL** | 5 واجهات |
| **إصدار قاعدة البيانات** | v7 (تمت إضافة Agent Brain) |
| **الحد الأدنى SDK** | 24 |
| **الحد الأقصى SDK** | 35 |
| **اللغة** | Kotlin 2.0 |
| **الواجهة** | Jetpack Compose + Material 3 |

---

## 🆕 نظام Agent Brain الجديد (إضافة 2026-04-02)

```
╔══════════════════════════════════════════════════════════════════╗
║              🧠 AGENT BRAIN SYSTEM (NEW)                        ║
║         يجعل الـ Agent مثل Claude Code / GitHub Copilot Agent   ║
╚══════════════════════════════════════════════════════════════════╝

data/brain/
├── SmartLearningBridge.kt      ← الجسر الذكي المنسق (القلب الجديد)
├── ToolExecutionJournal.kt     ← مجلة التنفيذ الدائمة (الذاكرة الكاملة)
└── ToolAwarenessEngine.kt      ← محرك الوعي بالأدوات والبيئة

data/db/
├── entities/
│   ├── ToolExecutionEntry.kt   ← كيان سجل التنفيذ
│   └── SystemKnowledgeEntry.kt ← كيان قاعدة معرفة النظام
└── dao/
    ├── ToolExecutionDao.kt     ← DAO للوصول لسجل التنفيذ
    └── SystemKnowledgeDao.kt   ← DAO لقاعدة معرفة النظام

ui/brain/
├── AgentBrainDashboard.kt      ← واجهة Compose لعرض حالة الذكاء
└── AgentBrainViewModel.kt      ← ViewModel للوحة تحكم Brain
```

### كيف يعمل:
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

## 🏗️ البنية الكلية (High-Level Architecture)

```
OmniDev Workspace (God-Mode AI Agent)
│
├─ 🧠 BRAIN LAYER (domain/)
│  ├─ AgentPipeline.kt          ← ReAct Loop (قلب النظام)
│  ├─ SwarmOrchestrator.kt      ← تنسيق Multi-Agent
│  ├─ IntentClassifier.kt       ← تصنيف النوايا
│  ├─ ModelRegistry.kt          ← توجيه LLM (Anthropic, OpenAI, Local)
│  └─ AutoHealBuildUseCase.kt   ← إصلاح أخطاء Build تلقائياً
│
├─ 🛠️ TOOLS LAYER (data/tools/) ← 57 TOOL
│  ├─ CompositeToolManager.kt   ← موحد الأدوات الرئيسي
│  ├─ FileToolManager.kt        ← read, write, patch, delete
│  ├─ MemoryManager.kt          ← ذاكرة SQL طويلة المدى
│  ├─ VectorMemoryManager.kt    ← ذاكرة دلالية TF-IDF
│  ├─ GitManagerTool.kt         ← git operations
│  ├─ AdvancedSystemTools.kt    ← shell commands عبر Shizuku
│  ├─ LogcatAnalyzerTool.kt     ← تحليل logcat
│  ├─ NetworkMonitorTool.kt     ← مراقبة الشبكة VPN
│  ├─ DiscordBotTool.kt         ← التواصل عبر Discord
│  ├─ TelegramBotTool.kt        ← التواصل عبر Telegram
│  ├─ WhatsAppBotTool.kt        ← التواصل عبر WhatsApp
│  ├─ TaskSchedulerTool.kt      ← جدولة المهام
│  ├─ SocialMediaVideoTool.kt   ← تحميل من YouTube/TikTok
│  ├─ HeadlessBrowserManager.kt ← تصفح الويب بلا رأس
│  └─ [40+ أدوات إضافية]
│
├─ 💾 DATA LAYER (data/)
│  ├─ Repository/               ← ChatRepository, SettingsRepository
│  ├─ Database/                 ← Room (Chat, Knowledge, Sessions)
│  ├─ Network/                  ← CompletionService (API Integration)
│  ├─ IPC/                       ← Shizuku، AIDL، Rish Shell
│  ├─ Accessibility/            ← SemanticUI، GodModeAccessibility
│  ├─ Auth/                      ← GitHub OAuth، Copilot Sessions
│  ├─ Integration/              ← Telegram/Discord/WhatsApp Polling
│  ├─ LocalLLM/                 ← Llama.cpp (GGUF Inference)
│  ├─ Media/                    ← MediaSession Control
│  ├─ Voice/                    ← STT/TTS (Always-on Listener)
│  ├─ Vision/                   ← OCR, Image Analysis
│  └─ Input/                    ← OmniInputMethodService (IME)
│
├─ 🎨 UI LAYER (ui/)
│  ├─ ChatScreen                ← الواجهة الرئيسية
│  ├─ SettingsScreen            ← إعدادات (7 أشاشات)
│  ├─ AnalyticsScreen           ← لوحة التحليلات
│  ├─ DebugConsoleScreen        ← وحدة التصحيح
│  ├─ OverlayBubbleService      ← Floating Bubble
│  └─ theme/                    ← Material 3 + Custom Theme
│
├─ 🔌 SERVICES & RECEIVERS (12 خدمة)
│  ├─ OmniCoreService.kt        ← IPC المركزية (AIDL)
│  ├─ OmniAccessibilityService  ← التحكم في الواجهة
│  ├─ OmniInputMethodService    ← Keyboard IME
│  ├─ OmniDevVpnService         ← VPN Monitor
│  ├─ OmniSyncService           ← مزامنة خلفية
│  ├─ OmniMediaSessionService   ← تحكم الوسائط
│  ├─ VoiceAssistantService     ← مساعد صوتي (Always-on)
│  ├─ AgentNotificationService  ← إشعارات
│  ├─ DiscordPollingService     ← Discord Polling
│  ├─ TelegramPollingService    ← Telegram Polling
│  ├─ WhatsAppBridgeService     ← WhatsApp Bridge
│  └─ BootReceiver              ← Auto-start on Boot
│
└─ 🔧 SYSTEM INTEGRATIONS
   ├─ Shizuku                   ← OS-level execution
   ├─ Llama.cpp                 ← Local GGUF inference
   ├─ Cloud APIs                ← Anthropic, OpenAI, Gemini
   └─ Third-party Services      ← Discord، Telegram، WhatsApp
```

---

## 🔧 نظام الأدوات — 57 Tool المتكاملة

### 📂 تصنيف الأدوات (Categorized)

#### 1️⃣ **أدوات الملفات** (File Tools)
- `FileToolManager.kt` — read_file_lines, patch_file_content, create_file, delete_file
- `AdvancedFileTools.kt` — search_codebase, find_files, diff_files
- `GodModeFileRouter.kt` — توجيه ملفات بـ god-mode (root access)

#### 2️⃣ **أدوات الذاكرة** (Memory Tools)
- `MemoryManager.kt` — SQL-based long-term memory (remember_fact, search_knowledge)
- `VectorMemoryManager.kt` — Semantic vector RAG (vector_store, vector_search)

#### 3️⃣ **أدوات النظام** (System Tools)
- `AdvancedSystemTools.kt` — Shizuku integration (getprop, setprop, dumpsys)
- `OmniExecutionDiagnostics.kt` — تشخيص البيئة (Python, Node, Git, Termux)
- `GodEyeProfilerTool.kt` — تحليل الأداء والذاكرة

#### 4️⃣ **أدوات Git & Versioning**
- `GitManagerTool.kt` — git operations (commit, push, pull, branch)
- `GitHubManagerTool.kt` — GitHub API (create issue, create PR)

#### 5️⃣ **أدوات التطوير** (Development Tools)
- `LogcatAnalyzerTool.kt` — تحليل logcat (crashes, ANR)
- `AppManifestAnalyzerTool.kt` — تحليل manifest الأمان
- `EnhancedAppManifestAnalyzerTool.kt` — تحليل متقدم (signatures, deep links)

#### 6️⃣ **أدوات الاتصال** (Communication Tools)
- `SystemAssistantTools.kt` — call, sms, WhatsApp
- `DiscordBotTool.kt` — Discord integration
- `TelegramBotTool.kt` — Telegram Bot API
- `NotionPublisherTool.kt` — Notion database writer

#### 7️⃣ **أدوات الشبكة & المراقبة** (Network Tools)
- `NetworkMonitorTool.kt` — VPN-based traffic monitoring
- `NetworkRequestTool.kt` — HTTP requests (GET, POST, etc.)
- `OmniDevVpnService.kt` — VPN tunnel service

#### 8️⃣ **أدوات الوسائط المتعددة** (Multimedia)
- `SocialMediaVideoTool.kt` — download من YouTube, TikTok, Instagram
- `HeadlessBrowserManager.kt` — تصفح الويب بلا واجهة (Puppeteer-like)
- `OmniMediaSessionService.kt` — التحكم في الموسيقى

#### 9️⃣ **أدوات المهام & الجدولة** (Tasks & Scheduling)
- `TaskSchedulerTool.kt` — جدولة مهام autonomous (CRON, delays)
- `N8nAutomationTool.kt` — تشغيل workflows من n8n

#### 🔟 **أدوات الأمان & الخصوصية** (Security)
- `ClipboardTool.kt` — قراءة/كتابة clipboard
- `AutofillAssistTool.kt` — ملء النماذج التلقائي
- `VpnControlTool.kt` — التحكم في VPN

#### 1️⃣1️⃣ **أدوات UI & Interaction**
- `SemanticUITool.kt` — التحكم بـ semantic node IDs (click, type, scroll)
- `LauncherControlTool.kt` — التحكم بـ launcher
- `AndroidIntentTool.kt` — تشغيل intents, deep links

#### 1️⃣2️⃣ **أدوات خاصة**
- `AgentRuntimeTool.kt` — Python, Node, Shell execution
- `AgentSandboxTool.kt` — تشغيل scripts معزول آمن
- `EnvironmentSetupManager.kt` — Termux bootstrap, Python install
- `CompositeToolManager.kt` — **الموحد الرئيسي** (يجمع كل الأدوات)

---

## 🧠 تدفق الإجراء (Execution Flow)

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. User Input (Chat, Voice, Intent)                             │
└───────────┬─────────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 2. IntentClassifier                                              │
│    ├─ Classify intent (Chat, Code, System, Tools)               │
│    └─ Route to appropriate handler                              │
└───────────┬─────────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 3. ModelRegistry                                                 │
│    ├─ Select LLM (Anthropic, OpenAI, Copilot, Llama.cpp)        │
│    └─ Determine reasoning mode (FAST, BALANCED, THOROUGH)       │
└───────────┬─────────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 4. AgentPipeline ReAct Loop (max 25-50 iterations)               │
│    ├─ REASON: Send prompt + tools to LLM                        │
│    ├─ ACT: Execute tool calls via CompositeToolManager          │
│    ├─ OBSERVE: Append results to history                        │
│    └─ LOOP until: text output OR max iterations                 │
└───────────┬─────────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 5. Tool Execution (CompositeToolManager)                         │
│    ├─ Route to FileToolManager, SystemTools, etc.               │
│    ├─ Execute with timeout + error handling                     │
│    └─ Return ToolCallResult (success + output)                  │
└───────────┬─────────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 6. Response Generation                                           │
│    ├─ Stream text to UI in real-time                            │
│    ├─ Save to ChatRepository                                    │
│    └─ Update MemoryManager for future context                   │
└───────────┬─────────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 7. Output (UI, Voice, Notification)                             │
│    ├─ Display in ChatScreen                                     │
│    ├─ Speak via VoiceAssistantService (TTS)                     │
│    └─ Send notification if needed                               │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🔌 الخدمات الأساسية (12 Services)

| الخدمة | الملف | الوظيفة | الحالة |
|------|------|--------|-------|
| **OmniCoreService** | `OmniCoreService.kt` | IPC مركزية (AIDL) | 🟢 Core |
| **OmniAccessibilityService** | `OmniAccessibilityService.kt` | التحكم بـ UI | 🟢 Core |
| **OmniInputMethodService** | `OmniInputMethodService.kt` | IME Keyboard | 🟢 Core |
| **OmniDevVpnService** | `OmniDevVpnService.kt` | VPN Monitor | 🟢 Core |
| **OmniSyncService** | `OmniSyncService.kt` | مزامنة خلفية | 🟢 Core |
| **OmniMediaSessionService** | `OmniMediaSessionService.kt` | تحكم الوسائط | 🟢 Core |
| **VoiceAssistantService** | `VoiceAssistantService.kt` | STT/TTS Always-on | 🟢 Core |
| **AgentNotificationService** | `AgentNotificationService.kt` | إشعارات | 🟢 Active |
| **DiscordPollingService** | `DiscordPollingService.kt` | Discord Polling | 🟢 Active |
| **TelegramPollingService** | `TelegramPollingService.kt` | Telegram Polling | 🟢 Active |
| **WhatsAppBridgeService** | `WhatsAppBridgeService.kt` | WhatsApp Bridge | 🟢 Active |
| **BootReceiver** | `BootReceiver.kt` | Auto-start on boot | 🟢 Active |

---

## 💾 طبقة البيانات (Data Layer)

### 📦 المستودعات (Repositories)
- `ChatRepository.kt` — إدارة محادثات
- `SettingsRepository.kt` — الإعدادات
- `AnalyticsRepository.kt` — التحليلات
- `ApiKeyRepository.kt` — مفاتيح API

### 🗄️ قاعدة البيانات (Room Database)
```
OmniDevDatabase
├─ ChatMessageEntity (جداول: id, content, sender, timestamp)
├─ ChatSessionEntity (جداول: sessionId, model, createdAt)
├─ KnowledgeSnippet (جداول: fact, embedding, category)
└─ DAOs:
   ├─ ChatMessageDao
   ├─ ChatSessionDao
   └─ KnowledgeDao
```

### 🌐 الشبكة (Network)
- `CompletionService.kt` — تكامل APIs (Anthropic, OpenAI, Gemini)
- `OmniDevVpnService.kt` — VPN tunnel + traffic monitoring

### 🔐 المصادقة (Auth)
- `OAuthManager.kt` — OAuth 2.0 handler
- `GitHubDeviceFlowManager.kt` — GitHub Device Flow
- `CopilotSessionManager.kt` — Copilot token management

---

## 🎨 طبقة الواجهة (UI Layer)

| الشاشة | الملف | الوظيفة |
|------|------|--------|
| **ChatScreen** | `ChatScreen.kt` | الواجهة الرئيسية |
| **SettingsScreen** | `SettingsScreen.kt` (7 أشاشات) | الإعدادات |
| **AnalyticsScreen** | `AnalyticsScreen.kt` | لوحة البيانات |
| **DebugConsoleScreen** | `DebugConsoleScreen.kt` | وحدة التصحيح |
| **OverlayBubbleService** | `OverlayBubbleService.kt` | Floating Bubble |
| **Theme** | `theme/` | Material 3 styling |

---

## ⚙️ التكاملات الخارجية (Integrations)

| الخدمة | الملف | نوع | الحالة |
|------|------|------|--------|
| **Anthropic Claude** | `ModelRegistry.kt` | Cloud LLM | ✅ Active |
| **OpenAI GPT** | `ModelRegistry.kt` | Cloud LLM | ✅ Active |
| **Google Gemini** | `ModelRegistry.kt` | Cloud LLM | ✅ Active |
| **Llama.cpp** | `LlamaCppInferenceEngine.kt` | Local GGUF | ✅ Active |
| **Shizuku** | `PrivilegedExecutionManager.kt` | OS-level | ✅ Active |
| **GitHub** | `GitHubManagerTool.kt` | API | ✅ Active |
| **Discord** | `DiscordBotTool.kt` | Bot API | ✅ Active |
| **Telegram** | `TelegramBotTool.kt` | Bot API | ✅ Active |
| **WhatsApp** | `WhatsAppBotTool.kt` | Business API | ✅ Active |
| **Notion** | `NotionPublisherTool.kt` | API | ✅ Active |
| **n8n** | `N8nAutomationTool.kt` | Workflow | ✅ Active |
| **YouTube/TikTok** | `SocialMediaVideoTool.kt` | yt-dlp | ✅ Active |
| **Puppeteer** | `HeadlessBrowserManager.kt` | Browser | ✅ Active |

---

## 🎯 الأوضاع التشغيلية (OmniMode)

```
FAST          ← 5 iteration max، model: gpt-4o-mini
BALANCED      ← 25 iteration max، model: gpt-4o
THOROUGH      ← 50 iteration max، model: claude-opus-4
SWARM         ← Multi-agent orchestration
AUTONOMOUS    ← Unattended execution + auto-healing
VOICE         ← VoiceAssistantService mode
DEBUG         ← DebugConsoleScreen logging
```

---

## 🔍 نقاط الدخول الرئيسية (Entry Points)

| الدخول | الملف | الغرض |
|------|------|--------|
| **MainActivity** | `MainActivity.kt` | Launch activity |
| **OmniDevApp** | `OmniDevApp.kt` | Application class |
| **OmniCoreService** | `OmniCoreService.kt` | AIDL IPC entry |
| **BootReceiver** | `BootReceiver.kt` | Auto-start |
| **VoiceAssistantService** | `VoiceAssistantService.kt` | Wake-word listener |
| **OmniAccessibilityService** | `OmniAccessibilityService.kt` | UI automation |

---

## 🚀 مسار التطوير الموصى به

### 1️⃣ **لإضافة أداة جديدة (New Tool)**
```
1. أنشئ ملف: data/tools/YourNewTool.kt
2. ورّثها من BaseTool أو مباشرة
3. سجلها في CompositeToolManager.kt
4. أضفها إلى ModelRegistry tool list
```

### 2️⃣ **لإضافة خدمة جديدة (New Service)**
```
1. أنشئ ملف: data/YourNewService.kt (extends Service)
2. سجلها في AndroidManifest.xml
3. ركبها في OmniCoreService.kt
4. أضفها إلى BootReceiver إن احتجت
```

### 3️⃣ **لإضافة شاشة جديدة (New Screen)**
```
1. أنشئ ملف: ui/screens/YourNewScreen.kt (Composable)
2. أضفها إلى NavGraph
3. ركبها مع ViewModel
4. اختبرها في Preview
```

---

## 📝 ملخص سريع

- **151 ملف** Kotlin + **57 أداة** مدمجة + **12 خدمة** = **نظام شامل**
- **ReAct Loop** يحول الـ prompt إلى actions متسلسلة
- **Shizuku** يعطيك access لـ OS-level commands بدون root كامل
- **Llama.cpp** لـ edge inference محلي
- **Multi-agent swarm** للعمل المعقد
- **Memory + Vector DB** للسياق طويل المدى
- **Always-on voice** listener مع TTS/STT
- **10+ integrations** (Discord, Telegram, GitHub, etc.)

---

## 🔗 الملفات المهمة للمراجعة السريعة

1. `domain/engine/AgentPipeline.kt` — **قلب النظام**
2. `data/tools/CompositeToolManager.kt` — **موحد الأدوات**
3. `data/network/CompletionService.kt` — **توجيه LLM**
4. `ui/screens/ChatScreen.kt` — **الواجهة الرئيسية**
5. `OmniDevApp.kt` — **نقطة البدء**
6. `README.md` — **الوثائق الشاملة**
7. `PROJECT_ARCHITECTURE.md` — **التفاصيل**

---

🎯 **هذا الملف هو مرجعك الأساسي — احفظه واستخدمه!**
