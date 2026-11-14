# Omni product knowledge for future training

This is user documentation, not project source code, private chat data or learned weights. Answers describe implemented paths and explicit experimental limits. A saved answer does not prove a user's edition, permission, account, backend or task outcome. Check the installed build and current UI before acting. The export groups translations together; future train/evaluation splits must keep each group intact.

## history-titles
Status: implemented
Editions: all
Question-en: How are chat history titles created?
Answer-en: New in-app conversations receive a bounded local extractive title from user text. Greeting-only input keeps a generic title until a substantive request arrives. The current engine is a deterministic baseline, not the experimental neural intent classifier or a cloud title-generation call. Renaming a conversation makes the user's choice authoritative.
Question-ar: عنوان المحادثة في السجل بيتعمل إزاي؟
Answer-ar: المحادثة الجديدة داخل التطبيق بتاخد عنوان قصير مستخرج محليًا من كلام المستخدم. التحية لوحدها بتسيب عنوان عام لحد ما يبقى فيه طلب واضح. المحرك الحالي قواعد محلية خفيفة، ومش النموذج العصبي التجريبي ولا طلب للسحابة. لو سميت المحادثة بنفسك اسمك بيتحفظ ومش بيتغير تلقائيًا.

## history-topics
Status: implemented
Editions: all
Question-en: How do I filter conversations by topic?
Answer-en: Open conversation history and use Topics. Local labels include Development, Device, Research, Omni help, Messaging, Connected apps, Writing, Personal, General and Uncategorized. Open a conversation row's Category action to choose a label or return to Automatic. Topics are browsing metadata, not permission or completion decisions.
Question-ar: أفلتر المحادثات حسب الموضوع إزاي؟
Answer-ar: افتح سجل المحادثات واختار Topics. فيه تصنيفات للبرمجة والجهاز والبحث ومساعدة Omni والمراسلة والتطبيقات المتصلة والكتابة والشخصي والعام وغير المصنف. من قائمة المحادثة اختار Category عشان تحدد تصنيف أو ترجع للوضع التلقائي. التصنيف لتنظيم السجل ومش تصريح تنفيذ أو دليل إن المهمة خلصت.

## history-sources
Status: implemented
Editions: all
Question-en: What is the difference between Sources and Apps mentioned?
Answer-en: Sources filters the conversation's stored entry channel: App, Telegram, Discord, WhatsApp bridge or Connected apps. Apps mentioned filters saved service tags such as WhatsApp, Telegram, Discord, GitHub, Slack, Gmail, Notion, MCP, OmniLink and AndroidIDE. Talking about WhatsApp inside App does not change its source to WhatsApp. Search, source, topic and mentioned-app filters can be combined.
Question-ar: الفرق إيه بين مصدر المحادثة والتطبيقات المذكورة؟
Answer-ar: Sources بيفلتر قناة دخول المحادثة المسجلة: التطبيق أو تيليجرام أو ديسكورد أو جسر واتساب أو تطبيق متصل. Apps mentioned بيفلتر وسوم الخدمات المذكورة أو المرتبطة بالمصدر، زي واتساب وتيليجرام وديسكورد وجيتهوب وSlack وGmail وNotion وMCP وOmniLink وAndroidIDE. الكلام عن واتساب داخل التطبيق مش بيحوّل مصدر المحادثة لواتساب. تقدر تجمع البحث وفلاتر المصدر والموضوع والتطبيق المذكور.

## history-legacy
Status: implemented
Editions: all
Question-en: Will older chats or my chosen titles be replaced?
Answer-en: Existing messages, pins, source identities and provided or manually chosen titles are retained. Opening history lazily fills local metadata in small batches. Older or externally named chats can keep their original title and offer Use local suggested title. Deleting a conversation also deletes its derived metadata. New user messages update labels; assistant and tool text do not classify the chat.
Question-ar: المحادثات القديمة وأسماؤها هتتمسح أو تتغير؟
Answer-ar: الرسائل والتثبيت ومصدر المحادثة والأسماء القديمة أو اليدوية بتفضل محفوظة. فتح السجل بيكمل التصنيفات محليًا على دفعات صغيرة. المحادثة القديمة أو اللي اسمها جاي من تطبيق خارجي ممكن تفضل باسمها وتعرض اختيار Use local suggested title. مسح المحادثة بيمسح بيانات تصنيفها كمان. كلام المستخدم الجديد بيحدث التصنيف؛ ردود المساعد والأدوات مش بتصنّفه.

## configure-provider
Status: implemented
Editions: all
Question-en: How do I configure a text model?
Answer-en: Open Providers, configure the selected provider's API key or supported account connection, refresh its model catalog when needed, then choose text models in Model Selection. An OpenAI-compatible server needs its own base URL. A configured key or listed model does not establish quota, account access or native tool support. Keep credentials out of chat and bug reports.
Question-ar: أفعّل نموذج الردود في Omni إزاي؟
Answer-ar: افتح Providers واضبط مفتاح المزود أو ربط الحساب المدعوم، وحدّث قائمة النماذج لو محتاجة، وبعدها اختار نموذج النص في Model Selection. السيرفر المتوافق مع OpenAI محتاج عنوانه الأساسي. وجود مفتاح أو اسم النموذج في القائمة مش ضمان للحصة أو صلاحية الحساب أو دعم استدعاء الأدوات. ما تبعتش المفاتيح في الشات أو تقرير المشكلة.

## choose-mode
Status: implemented
Editions: all
Question-en: When should I use Chat, Agent or Team?
Answer-en: Use ordinary Chat for conversation and explanations. Use Agent for scoped tool-based work when the edition and permissions allow it. Use Team for dependency-aware assignments that benefit from multiple workers. Team is not automatically preferable for a simple question. Lite does not acquire Agent or Team privileges through a history label or model suggestion.
Question-ar: أستخدم Chat ولا Agent ولا Team؟
Answer-ar: استخدم Chat للكلام والشرح العادي. Agent للشغل المحدد اللي محتاج أدوات لو النسخة والصلاحيات يسمحوا. Team للمهام المترابطة اللي تستفيد من أكتر من عامل. السؤال البسيط مش محتاج فريق تلقائيًا. تصنيف السجل أو اقتراح النموذج مش بيدي نسخة Lite صلاحيات Agent أو Team.

## edition-policy
Status: implemented
Editions: all
Question-en: Can changing an edition setting grant privileged access?
Answer-en: No. Edition capabilities, Android grants, service consent, account authorization and receiver trust remain separate requirements. Lite excludes advanced execution and Linked Accounts according to its build policy. OEM or Admin installation alone does not grant system or root rights. Shizuku, Accessibility and connected-app actions still require their actual prerequisites.
Question-ar: تغيير اسم النسخة أو إعداد فيها يديني صلاحيات أعلى؟
Answer-ar: لأ. إمكانيات النسخة وأذونات أندرويد وموافقة الخدمة وصلاحية الحساب وثقة التطبيق المستقبِل حاجات منفصلة. Lite ليها قيود البناء الخاصة بالتنفيذ المتقدم والحسابات المرتبطة. تثبيت OEM أو Admin لوحده مش بيمنح صلاحية نظام أو روت. شيزوكو وإمكانية الوصول والتطبيقات المتصلة لسه محتاجين شروطهم الفعلية.

## finish-evidence
Status: implemented
Editions: advanced
Question-en: Why can Omni reject a worker's completion claim?
Answer-en: The finish protocol checks runtime-owned identities, acceptance requirements and actual evidence. A model saying done, a generic successful tool response, repository metadata or tool discovery alone does not establish the objective. A team needs attributable verified worker receipts; synthesis cannot turn skipped or failed work into success.
Question-ar: ليه Omni بيرفض عامل بيقول إنه خلص؟
Answer-ar: بروتوكول الإنهاء بيراجع هوية التشغيل وشروط القبول والدليل الفعلي. كلمة خلصت من النموذج أو نجاح عام لأداة أو بيانات المستودع أو اكتشاف أدوات لوحده مش إثبات لتحقيق الطلب. الفريق محتاج إيصالات تحقق تخص كل محاولة فعلًا، والتلخيص مش بيحوّل الشغل المتفوت أو الفاشل لنجاح.

## recover-task
Status: implemented
Editions: advanced
Question-en: How should I continue an interrupted task or team?
Answer-en: Review its recovery state, completed evidence, blocked dependencies and unresolved operations. Use only an offered safe continuation action. A started mutation with an unknown outcome needs exact reconciliation before retry. Process restart does not justify replaying writes or rerunning verified completed workers. An approval requirement remains in force after recovery.
Question-ar: أكمل مهمة أو فريق اتقطع إزاي؟
Answer-ar: راجع حالة الاسترجاع والدليل المحفوظ والاعتماديات المتوقفة والعمليات اللي نتيجتها مش معروفة. استخدم إجراء الاستكمال الآمن المتاح فعلًا. لو تعديل بدأ ونتيجته مجهولة لازم تحقق محدد قبل إعادة المحاولة. إعادة فتح التطبيق مش سبب لتكرار كتابة أو تشغيل عامل اتثبت إنه خلص. الموافقة المطلوبة بتفضل مطلوبة بعد الاسترجاع.

## github-access
Status: implemented
Editions: advanced
Question-en: Why does repository access fail even when Copilot works?
Answer-en: GitHub Copilot or Models credentials are separate from GitHub Agent Access. Enable the account-control integration, authorize its own account token and inspect repository selection plus read/write policy. A hidden or absent resource can return 404; that response alone does not diagnose authorization. Do not treat a repository listing as source-file inspection.
Question-ar: ليه الوصول للمستودع بيفشل رغم إن Copilot شغال؟
Answer-ar: بيانات Copilot أو GitHub Models منفصلة عن GitHub Agent Access. فعّل ربط التحكم في الحساب وصرّح بالتوكن الخاص بيه وراجع اختيار المستودعات وسياسة القراءة والكتابة. المورد المخفي أو الغائب ممكن يرجع404؛ الرد لوحده مش بيحدد مشكلة الصلاحية. عرض أسماء المستودعات مش قراءة لملفات السورس.

## git-environment
Status: implemented
Editions: advanced
Question-en: Does not-a-git-repository mean the remote repository is missing?
Answer-en: No. Check the actual selected local checkout and working directory first. Local Git commands need a real eligible checkout; a GitHub repository name is not a filesystem path. Use an authorized remote GitHub route when appropriate. Avoid repeatedly executing the same failing local command or interpreting every exit 128 as a missing checkout.
Question-ar: رسالة مش مستودع Git معناها الريبو اتمسح؟
Answer-ar: لأ. راجع نسخة المشروع المحلية ومجلد التنفيذ المختار. أمر جيت المحلي محتاج نسخة فعلية مسموح الوصول ليها؛ اسم مستودع جيتهوب مش مسار ملفات. استخدم مسار جيتهوب البعيد المصرح بيه لما يناسب المهمة. ما تكررش نفس الأمر الفاشل وما تعتبرش كل خروج128 دليل إن النسخة المحلية مش موجودة.

## shizuku-prerequisites
Status: implemented
Editions: advanced
Question-en: What is needed for Shizuku or rish actions?
Answer-en: Use an eligible edition, a running Shizuku service, the application's grant and a working bridge/Termux configuration where required. A Termux shell working does not establish Android privileged-backend readiness. rish cannot recreate a missing Shizuku binder or grant. Root is not required by the local history metadata engine and is not silently substituted for another route.
Question-ar: تشغيل أوامر شيزوكو أو rish محتاج إيه؟
Answer-ar: محتاج نسخة تسمح، وخدمة شيزوكو شغالة، وتصريح للتطبيق، وإعداد الجسر أو Termux المطلوب للمسار. نجاح شيل Termux مش دليل إن مسار أندرويد المميز جاهز. rish مش بيعوض غياب خدمة شيزوكو أو تصريحها. محرك عناوين السجل مش محتاج روت، والروت مش بيتاخد كبديل صامت لمسار تاني.

## telegram-owner
Status: implemented
Editions: advanced
Question-en: How do I use Telegram safely with Omni?
Answer-en: Configure the Telegram integration and pair the owner through its short-lived code in a private bot chat. The listener checks the linked user and private chat. An outgoing Chat ID is a destination, not incoming-control permission. Agent/Team commands require an eligible non-root Target Context. Restart can leave delivery or effects uncertain; do not promise exactly-once execution.
Question-ar: أشغّل تيليجرام مع Omni إزاي؟
Answer-ar: اضبط ربط تيليجرام واربط المالك بكود الربط المؤقت في محادثة خاصة مع البوت. المستمع بيتحقق من المستخدم والمحادثة الخاصة المرتبطين. Chat ID للإرسال مش إذن للتحكم الوارد. أوامر Agent وTeam محتاجة نطاق عمل محدد مسموح مش جذر الجهاز. الانقطاع ممكن يسيب التسليم أو الأثر غير مؤكد؛ ما توعدش بتنفيذ مرة واحدة مضمون.

## whatsapp-bridge
Status: implemented
Editions: advanced
Question-en: What does the same-phone WhatsApp bridge require?
Answer-en: Run the companion Node.js bridge in Termux on the same phone, pair with WhatsApp Linked Devices, and configure its loopback URL, generated bearer key and own-account number in Workspace. The bridge binds to 127.0.0.1:3000. It accepts the linked own-account private conversation, not arbitrary contacts, groups or media. Keep local bridge credentials and pairing files private.
Question-ar: جسر واتساب على نفس الموبايل محتاج إيه؟
Answer-ar: شغّل جسر Node.js المصاحب في Termux على نفس الموبايل واربطه من أجهزة واتساب المرتبطة، واضبط عنوانه المحلي ومفتاحه المتولد ورقم حسابك في Workspace. الجسر بيسمع على127.0.0.1:3000. المسار ده للمحادثة الخاصة بحسابك المرتبط، مش أي أرقام أو جروبات أو ميديا. ملفات الربط ومفاتيح الجسر تفضل خاصة.

## discord-connected
Status: implemented
Editions: advanced
Question-en: Does a Discord or Connected apps history filter enable that service?
Answer-en: No. History filters describe stored conversations and subjects. Configure the real service integration or OmniLink connection separately, with its eligible edition, actual account access, receiver trust and consent. External sending is a mutation and needs the intended destination and requested effect. A service name in a title cannot authorize a send.
Question-ar: اختيار Discord أو Connected apps من السجل بيشغّل الربط؟
Answer-ar: لأ. الفلاتر بتوصف المحادثات والموضوعات المحفوظة. اضبط ربط الخدمة أو OmniLink بشكل منفصل، مع النسخة المناسبة وصلاحية الحساب وثقة التطبيق المستقبِل وموافقته. الإرسال الخارجي تعديل ومحتاج وجهة الطلب وأثره المحدد. اسم خدمة في عنوان الشات مش تصريح إرسال.

## mcp-tools
Status: implemented
Editions: all
Question-en: Why is a configured MCP tool unavailable?
Answer-en: Check server reachability and transport, exposed definitions, current tool policy, edition and permission. Discovery retrieves permitted schemas; it does not grant capability or execute a guessed tool. Corrected names and arguments must pass the exact current contract. Ambiguous proposals require correction rather than silent execution.
Question-ar: ليه أداة MCP مضافة ومش متاحة؟
Answer-ar: راجع وصول السيرفر ونوع الاتصال والتعريفات المعروضة وسياسة الأدوات والنسخة والأذونات. اكتشاف الأدوات بيجيب التعريفات المسموحة؛ مش بيمنح صلاحية أو ينفذ اسم متخمن. الاسم والوسائط المصححين لازم يعدوا العقد الحالي بالضبط. الاقتراح المبهم محتاج تصحيح، مش تنفيذ صامت.

## provider-failure
Status: implemented
Editions: all
Question-en: What should I check after a provider or DNS failure?
Answer-en: Inspect the exact provider/model error, connectivity and hostname resolution, account authentication, quota/rate limit and context size. A DNS lookup failure differs from a temporary timeout. Preserve verified progress and avoid repeated model requests to an unresolved hostname. Re-establish readiness before safe continuation; never infer that a lost response proves a tool succeeded.
Question-ar: أعمل إيه لو المزود أو DNS فشل؟
Answer-ar: راجع خطأ المزود والنموذج نفسه والاتصال وحل اسم النطاق وتوثيق الحساب والحصة أو حد الطلبات وحجم السياق. فشل اسم النطاق غير مهلة اتصال مؤقتة. حافظ على التقدم المتحقق وما تفضلش تبعت طلبات لعنوان مش بيتحل. اتأكد من الجاهزية قبل الاستكمال الآمن؛ فقد الرد مش دليل إن الأداة نجحت.

## model-routing
Status: experimental
Editions: all
Question-en: Are the tiny neural intent models already controlling Omni?
Answer-en: No. The neural-core module contains optional experiments and portable checkpoints with explicit failed quality gates. Their current role is evaluated classification, refusal, ranking or trust research. They are not a general conversational LLM, production tool authorization or proof of task completion. History title/category rules are a separate local baseline and do not enable those experimental weights.
Question-ar: النماذج العصبية الصغيرة بتتحكم في Omni دلوقتي؟
Answer-ar: لأ. neural-core فيه تجارب وأوزان محمولة واختبارات جودة لسه فيها شروط فاشلة. دورها الحالي تجارب تصنيف ورفض وترتيب وثقة. دي مش نموذج محادثة عام ولا صلاحية أدوات ولا إثبات إن المهمة خلصت. قواعد عناوين وتصنيف السجل محرك محلي منفصل ومش بتفعّل الأوزان التجريبية.

## local-model-size
Status: implemented
Editions: all
Question-en: Does chat labeling require a large local model on a 4 GB phone?
Answer-en: The current history engine uses bounded local rules and at most six short user inputs. It has no cloud call, large weight download or permanent service. Whole-application memory and optional downloaded language models are separate costs. Actual phone latency and peak heap must be measured; desktop timings are not mobile benchmarks.
Question-ar: تصنيف السجل محتاج موديل تقيل على موبايل4 جيجا؟
Answer-ar: المحرك الحالي قواعد محلية محدودة وبيقرأ بحد أقصى ست رسائل قصيرة من المستخدم. مش بيطلب السحابة أو تحميل أوزان كبيرة أو خدمة دائمة. استهلاك التطبيق كله وأي نماذج لغة محملة اختياريًا حاجة تانية. سرعة الموبايل وذروة الرام محتاجين قياس فعلي؛ قياس الكمبيوتر مش اختبار موبايل.

## future-knowledge-training
Status: planned
Editions: all
Question-en: What data may be used to teach Omni product support later?
Answer-en: Use the reviewed product guide and FAQ export with source-document hashes, feature status and edition constraints. Keep translations in the same data group. The export script accepts only the explicit documentation allowlist and excludes source-code files, databases, private conversations, credentials, logs and code fences. This corpus is documentation-derived assistance, not executed evidence or already trained weights.
Question-ar: هنعلّم نموذج المساعدة معلومات Omni من إيه بعدين؟
Answer-ar: من دليل المنتج والأسئلة المتراجعة وتصديرها مع بصمة التوثيق وحالة الميزة وقيود النسخة. ترجمات نفس السؤال تفضل في مجموعة واحدة. سكربت التصدير بيقبل ملفات التوثيق المحددة بس وبيستبعد ملفات السورس وقواعد البيانات والمحادثات الخاصة والمفاتيح والسجلات وكتل الكود. المحتوى ده مساعدة مبنية على التوثيق، مش دليل تنفيذ ولا أوزان اتدرّبت بالفعل.

## support-diagnostics
Status: implemented
Editions: all
Question-en: How should a larger model help with a difficult Omni problem?
Answer-en: Establish the installed edition/revision, Android version, active mode, provider/model, relevant permissions and the exact redacted error. Retrieve the matching documented feature and prerequisites, then propose a scoped diagnostic or supported setting change. Verify the observed result before reporting success. If the documentation or evidence is insufficient, state the specific blocker instead of inventing a capability or promising a fix.
Question-ar: النموذج الكبير يساعد في مشكلة صعبة في Omni إزاي؟
Answer-ar: يحدد النسخة والإصدار وإصدار أندرويد ووضع التشغيل والمزود والنموذج والأذونات والخطأ بعد إخفاء الأسرار. يرجع لتوثيق الميزة وشروطها، ويقترح تشخيص محدد أو تعديل إعداد مدعوم. يتحقق من النتيجة قبل ما يقول نجح. لو التوثيق أو الدليل مش كفاية يوضح العائق المحدد، بدل اختراع ميزة أو وعد بإصلاح مضمون.

## voice-wake
Status: implemented
Editions: advanced
Question-en: How do I use Hi Omni and local voice?
Answer-en: Use the voice settings of an eligible build, grant microphone access and explicitly install the required recognition/voice assets. The wake listener is user-started foreground work; configure enrollment with consistent positive examples and genuinely different contrasts. Device language, Android TTS and installed Vosk resources affect results. Reboot or force-stop may require explicit restart; no continuous-listening or dialect-quality guarantee is implied.
Question-ar: أشغّل Hi Omni والصوت المحلي إزاي؟
Answer-ar: استخدم إعدادات الصوت في نسخة تسمح وامنح إذن الميكروفون وثبّت ملفات التعرف والصوت المطلوبة بشكل صريح. مستمع التنبيه تشغيل أمامي يبدأه المستخدم؛ أمثلة التدريب الإيجابية تبقى متشابهة والاختلافات سلبية بجد. لغة الجهاز وTTS وملفات Vosk بتأثر على النتيجة. بعد إعادة تشغيل أو إيقاف إجباري ممكن تحتاج تشغّله بنفسك؛ مفيش ضمان استماع دائم أو جودة لهجة من غير قياس.

## screen-assistant
Status: implemented
Editions: all
Question-en: Why can the assistant panel open but not tap another app?
Answer-en: Opening the Android assistant panel, using an overlay and performing live screen actions have separate prerequisites. Choose the assistant role where supported, grant overlay access for the restoration bubble if needed, and enable eligible Accessibility/backend access for actions. Lite does not gain advanced screen control through the panel. Protected or ambiguous system controls require a supported handoff rather than invented success.
Question-ar: ليه نافذة المساعد بتفتح بس مش بتضغط في تطبيق تاني؟
Answer-ar: فتح المساعد والظهور فوق التطبيقات وتنفيذ لمس الشاشة ليهم شروط منفصلة. اختار دور المساعد لو مدعوم، وامنح إذن الظهور لفقاعة الرجوع لو محتاجها، وفعّل إمكانية الوصول أو مسار التنفيذ المسموح للأفعال. نافذة المساعد مش بتدي Lite تحكم شاشة متقدم. أدوات النظام المحمية أو المبهمة محتاجة تسليم مدعوم للمستخدم، مش ادعاء نجاح.

## learned-routines
Status: implemented
Editions: advanced
Question-en: Can a learned routine run without a model request?
Answer-en: Reviewed supported recipes can replay locally with versioned selectors, explicit approval and outcome checks. Teach or capture the action, review and approve its recipe, then use its exact trigger or Run control. Unsupported or uncertain steps pause for help. A recorded action is not permission to replay an unresolved external mutation.
Question-ar: المهمة المتعلمة ممكن تشتغل من غير طلب للموديل؟
Answer-ar: الوصفات المدعومة والمتراجعة ممكن تتعاد محليًا بمحددات لها إصدار وموافقة صريحة وفحص نتيجة. علّم أو سجّل الفعل وراجع وصفته ووافق عليها، وبعدها استخدم التفعيل المحدد أو Run. الخطوات غير المدعومة أو غير المؤكدة بتقف للمساعدة. تسجيل فعل مش تصريح لتكرار تعديل خارجي نتيجته مش معروفة.

## media-jobs
Status: implemented
Editions: all
Question-en: How do I enable image, video or music generation?
Answer-en: Configure the corresponding supported provider/model in Model Selection and enable the desired media type. Actual account access, quota and model capabilities still apply. Requests create persistent job cards in the originating conversation. If a known provider operation is interrupted, inspect that operation or Check existing job before submitting a second create request.
Question-ar: أفعّل توليد الصور أو الفيديو أو الموسيقى إزاي؟
Answer-ar: اضبط المزود والنموذج المدعوم لنوع الميديا في Model Selection وفعّل النوع المطلوب. صلاحية الحساب والحصة وقدرة النموذج لسه مطلوبة. الطلب بيعمل بطاقة عملية محفوظة في نفس المحادثة. لو عملية معروفة عند المزود اتقطعت افحصها أو استخدم Check existing job قبل ما تبعت إنشاء جديد.

## profile-reference
Status: implemented
Editions: all
Question-en: How do profile preferences and reference photos work?
Answer-en: Settings → Your profile stores optional background, response-style preferences and reference photos. Reference-aware generation requires enabling reference use and selecting a provider/model that actually supports image inputs. Keep permissions and private photos under the user's control. A stored photo is not evidence that every provider received or used it.
Question-ar: أضبط البروفايل والصور المرجعية إزاي؟
Answer-ar: Settings ثم Your profile لحفظ معلومات اختيارية وتفضيلات الرد والصور المرجعية. التوليد اللي يستفيد من الصور محتاج تفعيل استخدامها ونموذج يدعم مدخلات الصور فعلًا. الصور الخاصة والأذونات تحت تحكم المستخدم. حفظ صورة مش دليل إن كل مزود استلمها أو استخدمها.

## little-omni
Status: implemented
Editions: all
Question-en: What is Little Omni?
Answer-en: Little Omni is the local virtual companion with motion, expressions and persistent habits. Enable the companion in Settings. It is distinct from the external assistant overlay, remote text model and experimental tool-intent checkpoints. Companion behavior does not authorize device actions or establish task completion.
Question-ar: إيه Little Omni؟
Answer-ar: Little Omni هو الرفيق المحلي بحركة وتعبيرات وعادات محفوظة؛ بيتفعّل من Settings. ده منفصل عن نافذة المساعد فوق التطبيقات ونموذج المحادثة السحابي وأوزان تصنيف الأدوات التجريبية. تصرفات الرفيق مش بتصرّح بأفعال على الجهاز ومش دليل إن مهمة خلصت.

## memory-recall
Status: implemented
Editions: all
Question-en: How does saved knowledge differ from chat recall?
Answer-en: Chat recall searches and pages original saved messages with source references and conversation-scope limits. Knowledge snippets are separately stored facts or rules. Neither automatically establishes truth or permission, and external conversations must not browse unrelated archives. Product-support exports come from reviewed documentation, not automatic training on either store.
Question-ar: إيه الفرق بين الذاكرة والبحث في الشات؟
Answer-ar: استرجاع الشات بيبحث ويعرض الرسائل الأصلية المحفوظة بمراجعها وحدود نطاق المحادثة. مدخلات المعرفة حقائق أو قواعد محفوظة بشكل منفصل. الاتنين مش إثبات تلقائي للحقيقة أو إذن جديد، والمحادثة الخارجية مش مسموح لها تتصفح أرشيف غير متعلق بيها. تصدير معرفة المنتج من التوثيق المتراجع، مش تدريب تلقائي على المخزنين دول.

## local-inference
Status: implemented
Editions: advanced
Question-en: Does loading the local inference library prove a model works?
Answer-en: No. Native-enabled inference needs its actual compatible build, model file, memory and device support. Loading a library alone is not an inference result. Tiny neural specialists and lightweight history labels have different purposes and budgets from a downloaded conversational language model. Measure the chosen model on the real phone before promising speed or memory use.
Question-ar: تحميل مكتبة التشغيل المحلي دليل إن الموديل اشتغل؟
Answer-ar: لأ. الاستدلال المحلي الأصلي محتاج بناء متوافق وملف نموذج فعلي وذاكرة ودعم الجهاز. تحميل المكتبة لوحده مش نتيجة استدلال. المتخصصات العصبية الصغيرة وتصنيف السجل الخفيف غير نموذج محادثة كبير محمّل. لازم تقيس النموذج المختار على الموبايل الحقيقي قبل وعود السرعة أو الرام.
