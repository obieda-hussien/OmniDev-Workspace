# 🧠 OmniDev Workspace — خريطة معمارية كاملة
**نسخة: 3.0 | آخر تحديث: 2026-04-22**

> **الغرض:** هذا الملف هو الخريطة الذهنية الكاملة للمشروع. يوفر لأي AI Agent أو مطور صورة فورية عن البنية بدون الحاجة لقراءة 56,743 سطر كود.

---

## 🎛️ 4-Tier Build Flavor Architecture

The project now ships in four product flavors on the `tier` dimension. Each
flavor is a distinct business tier with its own applicationId, its own manifest
overlay, its own capability policy, and its own confirmation behaviour.

| Flavor | applicationId                  | Target audience                | Core capabilities                                                       | Confirmation behaviour |
|--------|--------------------------------|--------------------------------|-------------------------------------------------------------------------|------------------------|
| `lite` | `com.omnidev.workspace`        | B2C Free / Google Play Store   | `web_search`, `web_search_deep`, `web_scraper`, `read_file` ONLY        | Deny-all gate (defense-in-depth; tools aren't registered) |
| `norm` | `com.omnidev.workspace.norm`   | B2C Basic / Standard devs      | Accessibility, terminal, Git, app-manager (no root, no Shizuku)         | User prompts (UI gate) |
| `pro`  | `com.omnidev.workspace.pro`    | B2C Premium / Elite hackers    | Full God-Mode — Shizuku, root, deep-security, pentesting, full swarm   | User prompts (UI gate) |
| `oem`  | `com.omnidev.workspace.oem`    | B2B partners / Custom ROMs     | System-uid privilege, local Llama.cpp, but NO device-wipe               | **Zero-click** — OemTierPolicy auto-approves every request |

### Policy layer (`com.omnidev.workspace.core.policy`)

```
core/policy/
  ├── TierPolicy.kt            ← Interface: allowRoot, allowShizuku, allowAccessibility,
  │                              allowDeepSecurity, allowDeviceAdminWipe, allowLocalSlm,
  │                              allowSystemIntegration, autoApproveConfirmations,
  │                              confirmationGate(uiGate).
  ├── ConfirmationGate.kt      ← fun interface { suspend request(kind, preview, diff) }
  │                              + ConfirmationKind enum (policy-layer twin of ConfirmationType).
  ├── OmniAuditLog.kt          ← Process-local ring buffer (1k entries) + SharedFlow emitter.
  └── TierPolicyHolder.kt      ← Process-wide holder installed once at OmniDevApp.onCreate().

app/src/lite/java/.../LiteTierPolicy.kt   ← deny-all gate, all caps false.
app/src/norm/java/.../NormTierPolicy.kt   ← UI gate delegate, allowAccessibility=true.
app/src/pro/java/.../ProTierPolicy.kt     ← UI gate delegate, every cap true.
app/src/oem/java/.../OemTierPolicy.kt     ← AUTO-APPROVING gate with audit log.
```

### Privileged execution facade (`com.omnidev.workspace.core.privileged`)

```
core/privileged/
  ├── PrivilegedExecutionFacade.kt          ← isAvailable() + suspend execute(cmd, timeoutMs).
  └── PrivilegedExecutionFacadeHolder.kt    ← Process-wide holder, deny-all default.

app/src/lite/java/.../PrivilegedExecutionFacadeBootstrap.kt → deny-all.
app/src/norm/java/.../...Bootstrap.kt                        → deny-all.
app/src/pro/java/.../...Bootstrap.kt                         → adapter over PrivilegedExecutionManager
                                                              (Shizuku → rish → root fallback).
app/src/oem/java/.../...Bootstrap.kt                         → Runtime.exec() with android.uid.system
                                                              + OmniAuditLog recording.
```

### Flavor-scoped AndroidManifest.xml overlays

The AGP Manifest Merger physically strips prohibited nodes from each flavor's
final APK using `tools:node="remove"`:

- `app/src/lite/AndroidManifest.xml` — strips ~90 permissions (incl.
  `MANAGE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`, `PACKAGE_USAGE_STATS`,
  `WRITE_SECURE_SETTINGS`, `READ_LOGS`, Shizuku `API_V23`) + 13
  services/receivers (`OmniAccessibilityService`, `OmniDevVpnService`,
  `OmniCoreService`, all integration polling services, Device Admin,
  SMS receiver, Boot receiver) and the `ShizukuProvider`.
- `app/src/norm/AndroidManifest.xml` — strips Shizuku + privileged/system-signature
  permissions; keeps Accessibility, IME, Git.
- `app/src/pro/AndroidManifest.xml` — no-op overlay (Pro inherits full base
  manifest verbatim).
- `app/src/oem/AndroidManifest.xml` — adds `android:sharedUserId="android.uid.system"`
  for platform-signed custom-ROM installs; strips Shizuku.

### Runtime tool gating (`TierToolGate`)

`CompositeToolManager.getToolDefinitions()` routes through `TierToolGate.filter()`
so the LLM function-calling schema is scoped to the current tier's allow-list.
`CompositeToolManager.executeTool()` short-circuits with `TierToolGate.denyReason()`
before any side effect runs — defense-in-depth against hallucinated tool names.

---

## 📊 إحصائيات المشروع

```
إجمالي ملفات Kotlin: 172
إجمالي الأسطر: ~60,000
عدد الأدوات (Tools): 60+
عدد الخدمات (Services): 12
عدد واجهات AIDL: 5
عدد المستويات (Tiers): 4 (lite / norm / pro / oem)
```

---

## 🏗️ البنية الأساسية (4 طبقات رئيسية)

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
│   ├── tools/                        ← 60+ أداة ذكاء اصطناعي
│   ├── ipc/                          ← IPC & Shizuku Integration
│   ├── repository/                   ← مخازن البيانات
│   ├── db/                           ← Room Database
│   ├── network/                      ← Completion API & VPN
│   ├── accessibility/                ← Semantic UI Control
│   ├── auth/                         ← GitHub OAuth & Copilot
│   ├── integration/                  ← Telegram, Discord, WhatsApp
│   ├── localllm/                     ← Llama.cpp Edge Inference
│   └── [10+ modules]
│
├── 🎨 UI LAYER (ui/)
│   ├── chat/                         ← الواجهة الرئيسية
│   ├── settings/                     ← 7 شاشات إعدادات
│   ├── analytics/                    ← لوحة Analytics
│   ├── debug/                        ← Debug Console
│   ├── overlay/                      ← Floating Bubble
│   └── theme/                        ← Material 3 Theme
│
└── 🔌 SERVICES & RECEIVERS
    ├── OmniCoreService (AIDL IPC)
    ├── OmniAccessibilityService
    ├── OmniInputMethodService (IME)
    ├── OmniDevVpnService
    ├── OmniSyncService
    ├── OmniMediaSessionService
    └── [6+ خدمات إضافية]
```

---

## 🔧 نظام الأدوات (Tools System)

### 🎯 الأدوات الأساسية (Core Tools)
```
CompositeToolManager.kt          ← المدير الرئيسي (يوحد كل الأدوات)
│
├── FileToolManager              ← أدوات الملفات (read, write, search, patch)
├── MemoryManager                ← الذاكرة طويلة المدى (SQL-based)
├── VectorMemoryManager          ← الذاكرة الدلالية (TF-IDF RAG)
├── SystemAssistantTools         ← أدوات النظام (call, sms, toggle)
├── LogcatAnalyzerTool           ← تحليل Logcat
├── GitManagerTool               ← عمليات Git
├── EnvironmentSetupManager      ← Termux & Python Bootstrap
├── NotificationCaptureTool      ← التقاط الإشعارات
├── TaskSchedulerTool            ← جدولة المهام الذكية
└── [50+ أدوات إضافية]
```

### 📦 تصنيف الأدوات حسب الوظيفة

#### 🗂️ File & System Tools
- `GodModeFileRouter` → قراءة/كتابة أي ملف (Shizuku)
- `AdvancedFileTools` → grep, find, chmod, archive
- `AdvancedSystemTools` → disk usage, hex dump, diff

#### 🌐 Integration Tools
- `TelegramBotTool` + `TelegramPublisherTool`
- `DiscordBotTool` + `DiscordPublisherTool`
- `WhatsAppTool` + `WhatsAppBridgeTool`
- `SlackTool`
- `SendGridEmailTool`
- `N8nAutomationTool`

#### 🔐 Security & Analysis
- `AppManifestAnalyzerTool` → فحص أمان التطبيقات
- `EnhancedAppManifestAnalyzerTool` → تحليل متقدم
- `QualitySecurityTool` → مراجعة الكود
- `AdvancedSecurityAnalyzer` → تحليل أمان متقدم

#### 🚀 Execution & Runtime
- `AgentRuntimeTool` → بيئة تنفيذ كاملة
- `PythonRuntimeManager` → Python Autonomous Runtime
- `TermuxEnvironmentBridge` → Termux Integration
- `ShizukuCommandTool` → Privileged Commands
- `OmniCoreAgentTool` → AIDL-based Execution

#### 🧠 Intelligence & Orchestration
- `ToolOrchestrator` → تنسيق الأدوات
- `ToolDependencyGraph` → رسم التبعيات
- `ToolIntelligenceEngine` → محرك الذكاء
- `ToolMonitoringSystem` → مراقبة الأداء
- `IntelligentAutomationEngine` → أتمتة ذكية

#### 🎨 UI & Interaction
- `SemanticUITool` → التحكم في الواجهات
- `UIAutomationTool` → أتمتة UI
- `VisualInspectorTool` → التقاط الشاشة
- `WidgetGeneratorTool` → توليد Widgets
- `LauncherControlTool` → التحكم في Launcher
- `AutofillAssistTool` → مساعد الملء التلقائي

#### 🌍 Web & Network
- `WebScraperTool` → استخراج المحتوى
- `WebSearchTool` → البحث على الإنترنت
- `HeadlessBrowserManager` → متصفح خفي
- `NetworkMonitorTool` → مراقبة الشبكة
- `NetworkRequestTool` → طلبات HTTP

#### 📱 Android System
- `PermissionManagerTool` → إدارة الصلاحيات
- `SystemPowerTool` → إعادة التشغيل والإيقاف
- `VPNControlTool` → التحكم في VPN
- `SystemContactsTool` → جهات الاتصال
- `ClipboardTool` → الحافظة

#### 🔨 Development Tools
- `GitHubManagerTool` → GitHub API
- `RequestGitHubAuthenticationTool` → OAuth Flow
- `GodEyeProfilerTool` → Profiling
- `OmniExecutionDiagnostics` → تشخيص الأخطاء
- `AgentSandboxTool` → Sandbox Execution

#### 🗣️ Voice & Media
- `AdvancedVoiceCommandEngine` → الأوامر الصوتية
- `SocialMediaTool` → YouTube, TikTok, etc.

#### 📊 Analytics & ML
- `ToolMachineLearningEngine` → Machine Learning
- `PredictiveAnalyticsEngine` → التنبؤ
- `TaskManagerTool` → إدارة المهام

---

## 🔌 نظام IPC & AIDL

### ملفات AIDL المكررة (مشكلة حرجة!)
```diff
❌ DUPLICATE FOUND:
   app/src/main/aidl/com/omnidev/workspace/IOmniCoreInterface.aidl
   app/src/main/aidl/com/omnidev/workspace/ipc/IOmniCoreInterface.aidl
   
   الملف الأول: 209 سطر (واجهة كاملة)
   الملف الثاني: 10 أسطر فقط (واجهة مبسطة)
   
✅ الحل: حذف الملف المكرر والاحتفاظ بالنسخة الكاملة
```

### خدمات IPC
```
OmniCoreService.kt               ← الخدمة الأساسية (AIDL Binder)
PrivilegedExecutionManager.kt    ← Shizuku Command Execution
RishShellManager.kt              ← rish Full-Shell
ExtensionConnectionManager.kt    ← Omni-Link Extensions
LauncherConnectionManager.kt     ← Launcher Communication
LauncherCommandRouter.kt         ← تنسيق الأوامر
```

---

## 💾 قاعدة البيانات (Room)

```kotlin
OmniDevDatabase.kt
├── ChatSessionDao           ← جلسات المحادثة
├── ChatMessageDao           ← الرسائل
└── KnowledgeDao             ← الذاكرة طويلة المدى

Entities:
├── ChatSessionEntity        ← Session metadata
├── ChatMessageEntity        ← Message with role/content/timestamp
└── KnowledgeSnippet         ← Memory facts with tags
```

---

## 🎨 UI Screens (Jetpack Compose)

```
ChatScreen.kt                    ← الشاشة الرئيسية
├── AgentLiveConsole.kt          ← عرض مباشر للتنفيذ
├── MarkdownText.kt              ← عرض Markdown
├── ConfirmationGate.kt          ← بوابة تأكيد الأوامر الخطرة
└── MessageFormatter.kt          ← تنسيق الرسائل

Settings Screens:
├── AISettingsScreen.kt          ← إعدادات AI & Models
├── IntegrationsScreen.kt        ← API Keys & Webhooks
├── LocalModelManagerScreen.kt   ← Llama.cpp Models
├── MemoryExplorerScreen.kt      ← استعراض الذاكرة
├── ScheduledTasksScreen.kt      ← المهام المجدولة
├── SystemPromptEditorScreen.kt  ← تعديل System Prompt
├── ToolRegistryScreen.kt        ← سجل الأدوات
└── UserProfileScreen.kt         ← ملف المستخدم

AnalyticsDashboardScreen.kt      ← لوحة Analytics
DebugScreen.kt                   ← Debug Console
ProvidersScreen.kt               ← إدارة Models
```

---

## 🔐 الخدمات الأساسية (Android Services)

### 1. OmniAccessibilityService
```
الوظيفة: التحكم في UI عبر Accessibility API
الملفات:
├── OmniAccessibilityService.kt      ← الخدمة الرئيسية
├── AccessibilityStateManager.kt     ← إدارة الحالة
├── GodModeAccessibility.kt          ← God-Mode تحكم
├── SemanticTreeParser.kt            ← تحليل UI Tree
└── SemanticUITool.kt                ← واجهة الأدوات
```

### 2. OmniInputMethodService
```
الوظيفة: IME للكتابة في أي تطبيق
الملف: OmniInputMethodService.kt
```

### 3. OmniDevVpnService
```
الوظيفة: مراقبة الشبكة + حجب الإعلانات
الملف: OmniDevVpnService.kt
```

### 4. OmniSyncService
```
الوظيفة: مزامنة البيانات في الخلفية
الملف: OmniSyncService.kt
```

### 5. OmniMediaSessionService
```
الوظيفة: التحكم في تشغيل الوسائط
الملف: OmniMediaSessionService.kt
```

### 6. Polling Services
```
TelegramPollingService.kt         ← Telegram Bot Polling
DiscordPollingService.kt          ← Discord Bot Polling
WhatsAppBridgeService.kt          ← WhatsApp Bridge
```

### 7. OmniBubbleService
```
الوظيفة: Floating Bubble Overlay
الملف: ui/overlay/OmniBubbleService.kt
```

---

## 🔄 تدفق التنفيذ (Execution Flow)

```mermaid
User Input
    ↓
ChatViewModel
    ↓
AgentPipeline.executeQuery()
    ↓
┌─────────────────────────────────────┐
│  1. IntentClassifier               │  ← تصنيف النية
│  2. SwarmOrchestrator (optional)   │  ← Multi-Agent؟
│  3. CompletionService              │  ← استدعاء LLM
│  4. Parse Tool Calls               │  ← استخراج function_calls
│  5. CompositeToolManager.execute() │  ← تنفيذ الأداة
│  6. Result → LLM Context           │  ← إرجاع النتيجة
│  7. Loop until done                │  ← ReAct Loop
└─────────────────────────────────────┘
    ↓
Final Response → UI
```

---

## ⚙️ المشاكل المكتشفة

### 🔴 ملفات AIDL مكررة
```
app/src/main/aidl/com/omnidev/workspace/IOmniCoreInterface.aidl        [209 lines]
app/src/main/aidl/com/omnidev/workspace/ipc/IOmniCoreInterface.aidl    [10 lines]
```
**الحل:** حذف `ipc/IOmniCoreInterface.aidl` والاحتفاظ بالنسخة الكاملة

### 🟡 أدوات متشابهة (قد تحتاج دمج)
```
TelegramBotTool vs TelegramPublisherTool     ← وظائف متداخلة
DiscordBotTool vs DiscordPublisherTool       ← يمكن دمجهما
WhatsAppTool vs WhatsAppBridgeTool           ← نفس الهدف
MemoryManager vs VectorMemoryManager         ← ذاكرتين مختلفتين (OK)
```

### 🟢 الأدوات المستقلة (لا تحتاج تعديل)
```
✓ SemanticUITool               ← متصل بـ OmniAccessibilityService
✓ ShizukuCommandTool           ← متصل بـ PrivilegedExecutionManager
✓ OmniCoreAgentTool            ← متصل بـ OmniCoreService
✓ CompositeToolManager         ← يوحد كل الأدوات بشكل صحيح
```

---

## 📋 خطة العمل للإصلاح

### المرحلة 1: تنظيف الملفات المكررة
- [x] فحص ملفات AIDL المكررة
- [ ] حذف `ipc/IOmniCoreInterface.aidl`
- [ ] تحديث imports في الملفات المتأثرة

### المرحلة 2: دمج الأدوات المتشابهة (اختياري)
- [ ] دمج TelegramBotTool + TelegramPublisherTool
- [ ] دمج DiscordBotTool + DiscordPublisherTool
- [ ] دراسة دمج WhatsAppTool + WhatsAppBridgeTool

### المرحلة 3: التحقق من الوصلات
- [ ] فحص أن كل Tool مسجل في CompositeToolManager
- [ ] فحص أن كل Service مسجل في AndroidManifest
- [ ] فحص أن كل AIDL له ملف Service مقابل

### المرحلة 4: تحديث README
- [ ] كتابة README.md شامل
- [ ] إضافة أمثلة استخدام
- [ ] توثيق Architecture Decisions

---

## 🎯 النقاط الحرجة للـ AI Agents

### عند قراءة المشروع:
1. **ابدأ من `AgentPipeline.kt`** ← هذا هو القلب
2. **اقرأ `CompositeToolManager.kt`** ← يحتوي على كل الأدوات المتاحة
3. **افحص `AndroidManifest.xml`** ← لمعرفة Services المسجلة
4. **استخدم هذا الملف** بدلاً من قراءة 56k سطر

### عند إضافة أداة جديدة:
1. أنشئ `MyNewTool.kt` في `data/tools/`
2. نفذ interface `ToolManager`
3. سجلها في `CompositeToolManager`
4. أضفها للـ `getToolDefinitions()`
5. حدّث هذا الملف

### عند إضافة Service:
1. أنشئ Service في الموقع المناسب
2. سجله في `AndroidManifest.xml`
3. إذا كان AIDL: أضف `.aidl` file
4. حدّث هذا الملف

---

## 📚 مصادر إضافية

- `README.md` ← نظرة عامة للمستخدمين
- `ARCHITECTURE.md` ← هذا الملف (للمطورين)
- `docs/` ← (TODO: إضافة documentation)

---

**آخر تحديث:** 2026-04-01  
**الصيانة:** يجب تحديث هذا الملف عند إضافة/حذف أي Tool أو Service
