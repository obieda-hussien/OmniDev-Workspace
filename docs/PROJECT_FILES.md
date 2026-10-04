# دليل ملفات OmniDev Workspace

OmniDev Workspace تطبيق Android يضم المحادثة، وكيل الأدوات، تنسيق الفرق، المساعد العائم، ذاكرة المحادثات والتكاملات. البناء وحدة Gradle واحدة `:app`؛ تقسيم `core/` و`domain/` و`data/` و`ui/` هو تنظيم حزم داخلها.

## الملفات على مستوى المستودع

| المجموعة | المسار | المحتوى |
|---|---|---|
| وصف التطبيق | `README.md` | الوظائف، الأرقام، الإعداد، البناء وخريطة الوثائق |
| تطبيق Android | `app/` | كود Kotlin، الموارد، Manifest، AIDL، C++ والاختبارات |
| إدارة البناء | `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle/` | الوحدات، نسخ التطبيق، الاعتمادات وGradle Wrapper |
| التحقق والإصدارات | `.github/workflows/`, `.github/scripts/` | جودة، اختبارات، lint، بناء، توقيع وتجهيز الاعتمادات |
| أدوات الصيانة | `scripts/` | إحصاء الملفات والمصادر وفحوص أمان المستودع |
| جسر WhatsApp | `whatsapp-bridge/` | جسر Termux، الإعداد والتشغيل |
| أدلة التشغيل والتكامل | `docs/` والوثائق المتخصصة في الجذر | المساعد، الوصول، الذاكرة، الأداء وبروتوكول OmniLink |
| مواد التخطيط | `00_INTEGRATION_ORDER.md`, `01_...` إلى `08_..._PROMPT.md` | مواصفات منظومة Omni التاريخية؛ تُقارن بالكود قبل استخدامها كتوجيه تنفيذ |

## نقاط الدخول

المسارات التالية تبدأ من `app/src/main/java/com/omnidev/workspace/`.

| الملف | المسؤولية |
|---|---|
| `OmniDevApp.kt` | تهيئة التطبيق والسياسات والخدمات المشتركة |
| `MainActivity.kt` | واجهة التطبيق، التنقل، استقبال النتائج والطلبات |
| `WorkspaceChatRuntime.kt` | إنشاء بيئتي المحادثة الرئيسية والمساعد وإعداد الأدوات والمحرك |
| `ui/chat/ChatViewModel.kt` | إرسال الرسائل، تنفيذ الوضع المختار، نتائج الأدوات والموافقات |
| `domain/engine/AgentPipeline.kt` | دورة الوكيل واستدعاءات الأدوات ومعالجة النتائج |
| `domain/engine/SwarmOrchestrator.kt` | خطة الفريق وتوزيع العمل وتجميع الناتج |
| `data/tools/CompositeToolManager.kt` | تجميع تعريفات الأدوات والتوجيه إلى منفذ كل أداة |
| `core/policy/TierPolicy.kt` | قدرات نسخة البناء وحدود الموافقة |

## تصنيف الكود حسب المسؤولية

| المجموعة | الحزم | ملفات أو وظائف مهمة |
|---|---|---|
| واجهة التطبيق | `ui/chat/`, `ui/settings/`, `ui/providers/`, `ui/navigation/` | المحادثة، الإعدادات، الموفرون ومسارات التنقل |
| المساعد العائم | `ui/assistant/`, `data/assistant/` | النافذة، الجلسة، الصوت، الكورة، الإرفاق والعودة من إعدادات Android |
| الصلاحيات والامتيازات | `core/policy/`, `core/privileged/`, `data/tools/`, `data/ipc/` | `PermissionManagerTool`, `DeviceAccessCatalog`, `PermissionRequestPlan`, `PrivilegedExecutionManager` |
| قرار الوضع | `domain/engine/` | `IntentClassifier`, `AdaptiveModeRouter`, `ModeDecisionModel`, `ModeOutcomeLearner` |
| ميزانيات واستمرارية الوكيل | `domain/engine/` | ضغط السياق، توكنز، نقل المهمة، تكرار الأدوات والتعثر |
| فهرسة المشروع | `data/repo/`, `data/builddoctor/`, `data/rollback/` | فهرسة الكود، استرجاع الأدلة، تشخيص البناء والتراجع |
| البيانات والذاكرة | `data/db/`, `data/repository/`, `data/brain/` | Room، DAOs، مستودعات البيانات والذاكرة |
| النماذج والموفرون | `data/model/`, `data/network/`, `data/localllm/`, `registry/` | إعداد الطلب، استكمال النموذج، النماذج المحلية والتسجيل |
| التحكم في الجهاز | `data/accessibility/`, `data/input/`, `data/admin/`, `data/media/` | Accessibility، IME، Device Admin وجلسات الميديا |
| التطبيقات المتصلة | `data/ipc/`, `data/mcp/`, `data/integration/`, `data/auth/` | OmniLink، MCP، التكامل والحسابات |
| الخلفية والجدولة | `data/background/`, `data/sync/`, `domain/engine/` | العمل الدوري، المزامنة وتنفيذ المهام المجدولة |
| الأداء والمراقبة | `ui/motion/`, `ui/analytics/`, `ui/brain/`, `data/debug/` | سياسة الحركة، التحليلات، الذاكرة المرئية والسجلات |

`data/tools/` يضم عائلات أدوات متعددة، لذلك أسماء الملفات ومسارات الاستدعاء أهم من اعتبار الحزمة كلها أداة واحدة. إعدادات النسخة، صلاحيات Android وربط التكامل تحدد ما يظهر وما يمكن تنفيذه.

## مصادر Android والاختبارات

| المسار | الوظيفة |
|---|---|
| `app/src/main/AndroidManifest.xml` | التصريحات والمكوّنات المشتركة |
| `app/src/{lite,norm,pro,oem,admin}/` | سياسات وManifest ومصادر كل نسخة |
| `app/src/liteNorm/`, `app/src/proOem/`, `app/src/proOemAdmin/` | مصادر مشتركة بين مجموعات نسخ البناء |
| `app/src/main/res/` | الأيقونات، النصوص، XML والسمات |
| `app/src/main/aidl/` | عقود Binder للخدمات والربط |
| `app/src/main/cpp/` | ربط التنفيذ الأصلي للنماذج المحلية |
| `app/src/main/assets/agent-skills/` | المهارات المرفقة للوكيل |
| `app/src/test/` | اختبارات JVM للمحرك والسياسات والتخطيط والأدوات |
| `app/src/androidTest/` | اختبارات Compose والنوافذ والسلوك على جهاز أو محاكي |

## أي وثيقة أقرأ؟

| الهدف | الوثيقة |
|---|---|
| فهم التطبيق وإعداده | [README](../README.md) |
| فهم الطبقات والعقود | [المعمارية](../PROJECT_ARCHITECTURE.md) |
| العثور بسرعة على نقطة الدخول | [الخريطة الذهنية](../MENTAL_MAP.md) |
| فهم AUTO والتعلم من النتائج | [محرك القرار](../DECISION_ENGINE.md) |
| تشغيل المساعد العائم | [المساعد على الشاشة](SCREEN_ASSISTANT.md) |
| تجهيز الصلاحيات وفهم حالتها | [الوصول إلى الجهاز](../DEVICE_ACCESS.md) |
| فهم ذاكرة المحادثات | [استرجاع التاريخ](../CHAT_HISTORY_RECALL.md) |
| فهم الحركة واختبار الأداء | [دليل الأداء](../UI_PERFORMANCE.md) |
| ربط تطبيقات Omni | [OmniLink](../OMNILINK_V3_INTEGRATION.md)، [البروتوكول](../LINK_PROTOCOL.md) |
| ربط اللانشر | [تكامل اللانشر](LAUNCHER_INTEGRATION.md) |
| إعداد Telegram وWhatsApp | [Telegram](../TELEGRAM_INTEGRATION.md)، [WhatsApp](../whatsapp-bridge/README.md) |
| دراسة تقسيم التطبيق إلى وحدات مستقلة | [خطة التفكيك](../MODULARIZATION_ROADMAP.md) |
| مراجعة حقوق المكونات والمساهمين | [النسب والحقوق](../ATTRIBUTION.md) |

للأعداد القابلة للتكرار شغّل `python3 scripts/repo_metrics.py`. لحالة البناء راجع نتيجة المهام في GitHub Actions. مواد الرؤية والتخطيط تصف المقترحات؛ ملفات المصدر وعقود التشغيل تصف التنفيذ.
