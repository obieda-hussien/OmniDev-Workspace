"""Freeze source-anchored authored scenarios; labels are experimental annotations, not execution evidence."""
import csv
import hashlib
import io
import json
import pathlib
import sys
from collections import Counter
from reserve_trust_eval import ROOT, blocked, canonical, read, DEST as TRUST

DEST=ROOT/'neural-core/fixtures/contract-intents'
TOOLS='app/src/main/java/com/omnidev/workspace/data/tools/'
ANCHORS={
 'runtime':(TOOLS+'AgentRuntimeTool.kt','name = "agent_runtime"'),
 'github':(TOOLS+'GitHubManagerTool.kt','"github_manager",'),
 'build':(TOOLS+'BuildDoctorTools.kt','name = "build_diagnose"'),
 'settings':(TOOLS+'AdvancedSystemTools.kt','name = "system_settings_tool"'),
 'web':(TOOLS+'WebSearchTool.kt','name = "web_search"'),
 'memory':(TOOLS+'MemoryManager.kt','name = "search_knowledge"'),
 'annotation':('docs/TOOL_EXECUTION_CONTRACT.md','The model proposes operations.')}
# Fixed order assigns six train, two development, two calibration, two test scenarios per label.
SCENARIOS={
'repository':[
 ('remote-routes','runtime','List fetch and push addresses for every Git remote','هات عناوين السحب والدفع لكل ريموت في جيت','هات fetch وpush URLs لكل git remote'),
 ('short-history','runtime','Show the five most recent Git commit subjects','وريني عناوين آخر خمس كوميتات في جيت','هات آخر five commit subjects من git log'),
 ('stash-inventory','runtime','Inspect saved Git stash entries before changing branches','افحص تغييرات جيت المحفوظة مؤقتا قبل تبديل الفرع','شوف git stash list قبل تبديل branch'),
 ('account-repositories','github','List the second page of repositories for my GitHub account','اعرض الصفحة التانية من مستودعات حسابي على جيتهوب','هات page2 من GitHub account repos'),
 ('file-lines','github','Read lines20 through45 of app/build.gradle.kts from the GitHub repo','اقرأ السطور من عشرين لخمسة وأربعين من ملف البناء في مستودع جيتهوب','اقرأ lines20-45 من app/build.gradle.kts على GitHub'),
 ('branch-inventory','runtime','List local branches and remote-tracking branches in this checkout','اعرض الفروع المحلية والفروع المتتبعة للريموت في نسخة المشروع','هات local وremote-tracking branches للـcheckout'),
 ('tag-pattern','runtime','Find Git tags whose names start with release','دور على علامات جيت اللي اسمها بيبدأ بكلمة ريليز','هات git tags اللي بتبدأ بـrelease'),
 ('default-branch','github','Read the repository metadata to identify its actual default branch','اقرأ بيانات المستودع عشان تعرف اسم فرعه الافتراضي الحقيقي','اقرأ repo metadata وحدد default branch الحقيقي'),
 ('staged-patch','runtime','Display only the Git patch currently staged for commit','اعرض فرق تعديلات جيت اللي اتجهزت للكوميت بس','هات git diff --cached للتغييرات staged بس'),
 ('directory-list','github','List the entries under the docs directory on the selected GitHub ref','اعرض محتويات مجلد التوثيق على مرجع جيتهوب المختار','هات entries بتاعة docs على GitHub ref المختار'),
 ('review-comments','github','Read review comments attached to pull request17 in this GitHub repository','اقرأ تعليقات المراجعة على طلب الدمج رقم سبعتاشر في مستودع جيتهوب','هات review comments بتاعة GitHub pull request17'),
 ('commit-checks','github','Inspect check runs attached to the selected GitHub commit SHA','افحص نتائج الفحوص المرتبطة ببصمة الكوميت المختار على جيتهوب','اقرأ check runs للـGitHub commit SHA المختار')],
'build':[
 ('lint-report','runtime','Execute lintNormDebug and inspect the Android lint report','شغل فحص لينت لنسخة نورم التجريبية وراجع تقرير أندرويد','شغل lintNormDebug وراجع lint report'),
 ('compile-test-kotlin','runtime','Compile the Kotlin unit-test sources for the norm debug variant','جمّع مصادر اختبارات كوتلن لنسخة نورم التجريبية','اعمل compile لمصادر Kotlin unit tests في normDebug'),
 ('diagnose-output','build','Diagnose this saved Gradle failure output using the build doctor','شخّص سجل فشل جرادل المحفوظ بأداة تشخيص البناء','اعمل diagnose للـGradle failure output في build doctor'),
 ('one-test-filter','runtime','Run only the database migration test using a Gradle tests filter','نفذ اختبار ترحيل قاعدة البيانات لوحده بفلتر اختبارات جرادل','شغل database migration test بس بفلتر Gradle --tests'),
 ('clean-assemble','runtime','Clean the Gradle outputs and assemble the lite debug APK','امسح نواتج جرادل وابني ملف تطبيق لايت التجريبي','اعمل Gradle clean وبعده assembleLiteDebug APK'),
 ('dependency-tree','runtime','Print the Gradle dependency tree for the norm debug runtime classpath','اطبع شجرة اعتمادات جرادل لمسار تشغيل نسخة نورم التجريبية','هات Gradle dependency tree للـnormDebugRuntimeClasspath'),
 ('task-discovery','runtime','Show all Gradle tasks available for the neural-core module','اعرض كل مهام جرادل المتاحة لوحدة النواة العصبية','هات Gradle tasks --all لوحدة neural-core'),
 ('instrumentation-build','runtime','Assemble the norm debug instrumentation test APK without installing it','ابني ملف اختبارات أندرويد لنسخة نورم التجريبية من غير تثبيت','اعمل assembleNormDebugAndroidTest بس من غير install'),
 ('compile-java','runtime','Compile the Java test fixtures with the Java8 API release target','جمّع تجهيزات اختبار جافا باستهداف واجهات جافا تمانية','اعمل javac --release 8 لتجهيزات Java test fixtures'),
 ('unit-report','runtime','Run norm unit tests and read their generated XML result files','شغل اختبارات وحدة نورم واقرأ ملفات نتيجة إكس إم إل الناتجة','شغل testNormDebugUnitTest واقرأ XML results الناتجة'),
 ('ksp-generation','runtime','Run KSP code generation for normDebug and inspect its generated sources','شغل توليد الكود لكوتلن في نورم التجريبية وافحص المصادر الناتجة','شغل kspNormDebugKotlin وافحص generated sources'),
 ('aar-metadata','runtime','Run the norm debug AAR metadata compatibility check','نفذ فحص توافق بيانات مكتبات أندرويد لنسخة نورم التجريبية','شغل checkNormDebugAarMetadata لفحص compatibility')],
'device':[
 ('brightness-mode','settings','Read the Android setting that selects automatic screen brightness','اقرأ إعداد أندرويد اللي بيحدد سطوع الشاشة التلقائي','اقرأ Android screen_brightness_mode الحالي'),
 ('screen-timeout','settings','Check the Android screen-off timeout value in system settings','شوف مدة انتظار إطفاء شاشة أندرويد في إعدادات النظام','هات system screen_off_timeout على Android'),
 ('font-scale','settings','Inspect the current Android font scaling setting','افحص قيمة تكبير خط أندرويد الحالية','اقرأ Android font_scale الحالي'),
 ('wifi-flag','settings','Read the global Android Wi-Fi enabled flag','اقرأ علامة تشغيل الواي فاي العامة في إعدادات أندرويد','هات global wifi_on setting على Android'),
 ('ring-volume','settings','Read the stored Android ringtone volume setting','اقرأ إعداد مستوى صوت رنة أندرويد المحفوظ','هات Android system volume_ring setting'),
 ('airplane-flag','settings','Inspect the Android airplane mode flag without changing it','افحص علامة وضع الطيران في أندرويد من غير تغييرها','اقرأ Android airplane_mode_on flag بس'),
 ('bluetooth-flag','settings','Query the global Android Bluetooth enabled setting','استعلم عن إعداد تشغيل بلوتوث العام في أندرويد','اقرأ global bluetooth_on من Android settings'),
 ('haptic-flag','settings','Read whether Android haptic feedback is enabled','اقرأ إذا كان رد الفعل بالاهتزاز مفعّل في أندرويد','شوف Android haptic_feedback_enabled setting'),
 ('animation-scale','settings','Read the Android transition animation scale from global settings','اقرأ مقياس حركة الانتقال في إعدادات أندرويد العامة','هات global transition_animation_scale على Android'),
 ('accessibility-flag','settings','Inspect the secure Android accessibility enabled flag','افحص علامة تفعيل تسهيلات الاستخدام في إعدادات أندرويد الآمنة','اقرأ secure accessibility_enabled على Android'),
 ('usb-awake','settings','Read the Android stay-awake-while-plugged-in global setting','اقرأ إعداد أندرويد للبقاء مستيقظا أثناء توصيل الشاحن','هات global stay_on_while_plugged_in على Android'),
 ('touch-sounds','settings','Inspect whether Android touch sound effects are enabled','افحص إذا كانت أصوات لمس أندرويد مفعّلة','اقرأ Android sound_effects_enabled setting')],
'knowledge':[
 ('android-guide','web','Find the official Android guide for foreground service restrictions','دور على دليل أندرويد الرسمي لقيود خدمات المقدمة','هات official Android foreground service restrictions guide'),
 ('saved-kotlin-note','memory','Search saved knowledge for my Kotlin coroutine cancellation notes','دور في المعرفة المحفوظة على ملاحظات إلغاء كوروتين كوتلن','اعمل search_knowledge لملاحظات Kotlin coroutine cancellation'),
 ('ocr-paper','web','Find a research paper about lightweight OCR and include its source URL','دور على ورقة بحث عن قراءة النصوص الخفيفة وهات رابط المصدر','دور على lightweight OCR paper مع source URL'),
 ('permission-doc','web','Retrieve the official documentation describing Android notification permission','هات التوثيق الرسمي اللي بيشرح إذن إشعارات أندرويد','هات official Android notification permission documentation'),
 ('sqlite-guide','web','Find a reliable SQLite guide explaining full-text search ranking','هات دليل موثوق يشرح ترتيب نتائج البحث النصي في إس كيو لايت','دور على SQLite full-text search ranking guide موثوق'),
 ('saved-schema','memory','Look up the saved architecture explanation for the task-state schema','دور على شرح معمارية مخطط حالة المهام في المعرفة المحفوظة','ابحث في saved knowledge عن task-state schema architecture'),
 ('flow-buffering','web','Find the official Kotlin Flow buffering documentation and cite it','دور على توثيق تخزين فلو كوتلن المؤقت الرسمي مع مصدره','هات official Kotlin Flow buffering docs مع citation'),
 ('paper-methods','web','Locate the original paper describing small text classification models','حدد الورقة الأصلية اللي بتشرح نماذج تصنيف النصوص الصغيرة','هات original paper عن small text classification models'),
 ('room-validation','web','Search official Room migration validation guidance and identify the source','ابحث عن إرشادات روم الرسمية للتحقق من الترحيل وحدد مصدرها','هات official Room migration validation guidance ومصدرها'),
 ('saved-recovery','memory','Find my saved notes discussing safe task recovery after interruption','دور على ملاحظاتي المحفوظة عن استرجاع المهام الآمن بعد الانقطاع','ابحث في saved notes عن safe task recovery after interruption'),
 ('jvm-bytecode','web','Find primary documentation for the JVM method bytecode size limit','هات التوثيق الأصلي لحد حجم بايت كود الدالة في آلة جافا','دور على primary JVM method bytecode size limit docs'),
 ('saved-dns','memory','Retrieve the saved knowledge entry about diagnosing provider DNS failures','هات مدخلة المعرفة المحفوظة عن تشخيص فشل أسماء نطاقات مزود النموذج','هات saved knowledge عن provider DNS failure diagnosis')],
'chat':[
 ('casual-arrival','annotation','Hey friend, I just got home and wanted to say hi','يا صاحبي أنا لسه راجع البيت وبقول لك مساء الفل','Hey يا صاحبي وصلت home وبقول hi'),
 ('study-motivation','annotation','Give me a few encouraging words before a study session','قول لي كلمتين يشجعوني قبل ما أبدأ مذاكرة','اديني encouragement صغير قبل study session'),
 ('tired-smalltalk','annotation','I had a tiring day and feel like a little friendly chat','كان يوم متعب ونفسي في شوية كلام لطيف','كان tiring day وعايز friendly chat شوية'),
 ('coffee-pun','annotation','Tell me a silly pun about a sleepy coffee cup','قول لي إفيه سخيف عن فنجان قهوة نعسان','هات silly pun عن sleepy coffee cup'),
 ('friendly-thanks','annotation','Thanks for being patient while I worked through that explanation','متشكر على صبرك وأنا بحاول أفهم الشرح','Thanks على patience وأنا بفهم explanation'),
 ('good-luck','annotation','Wish me luck for tomorrow without looking anything up','اتمنى لي حظ حلو لبكرة من غير ما تدور على حاجة','قولي good luck لبكرة من غير search'),
 ('night-farewell','annotation','I am heading to sleep, say a friendly goodnight','أنا داخل أنام قول لي تصبح على خير بشكل لطيف','داخل sleep قولي friendly goodnight'),
 ('happy-news','annotation','I finally finished my chores and wanted to share the good mood','خلصت اللي ورايا أخيرا وحبيت أشاركك فرحتي','Finally خلصت chores وحبيت أشارك good mood'),
 ('morning-greeting','annotation','Morning friend, ready for a calm new day','صباح الورد يا صاحبي جاهز ليوم هادي','Morning يا صاحبي جاهز لـcalm day'),
 ('tiny-compliment','annotation','Say something kind to a friend having a rough day','قول كلمة لطيفة لصاحبك اللي يومه صعب','قول kind words لصاحبك بعد rough day'),
 ('celebrate-small-win','annotation','Help me celebrate a small personal win with a short cheerful reply','احتفل معايا بإنجاز صغير ورد عليا بكلمتين مفرحين','Celebrate معايا small win بـshort cheerful reply'),
 ('playful-rhyme','annotation','Make a short playful rhyme about rainy mornings','قول قافية خفيفة عن صباح فيه مطر','اعمل short playful rhyme عن rainy mornings')],
'-':[
 ('vague-object','annotation','Please handle that unspecified item for me','اتصرف في الحاجة اللي ما حددناش هي إيه','Handle الحاجة unspecified دي'),
 ('food-delivery','annotation','Order dinner delivery to the address I never provided','اطلب عشا يتوصل لعنوان أنا ما قلتوش','اعمل dinner delivery للـaddress اللي ما قلتوش'),
 ('bank-transfer','annotation','Move money between my unspecified bank accounts','حوّل فلوس بين حساباتي البنكية اللي ما حددتهاش','اعمل money transfer بين bank accounts مش محددة'),
 ('cross-repo-phone','annotation','Read GitHub repository tags and then mute the Android ringtone','اقرأ علامات مستودع جيتهوب وبعدها اكتم رنة أندرويد','هات GitHub tags وبعدها mute Android ringtone'),
 ('cross-tests-search','annotation','Run Gradle tests and then search the web for a holiday hotel','شغل اختبارات جرادل وبعدها دور على فندق للإجازة','شغل Gradle tests وبعدها web search لـholiday hotel'),
 ('missing-reference','annotation','Continue the unmentioned operation from an unknown conversation','كمل العملية اللي ما اتقالتش من محادثة مش معروفة','Continue operation مش مذكورة من unknown conversation'),
 ('cross-memory-system','annotation','Find my saved Kotlin notes and change phone screen timeout','دور على ملاحظات كوتلن المحفوظة وغيّر مدة إطفاء شاشة الموبايل','هات saved Kotlin notes وغيّر Android screen timeout'),
 ('unresolved-target','annotation','Change whichever value you think I might mean','غيّر أي قيمة تتخيل إني أقصدها','Change أي value تتخيل إني mean it'),
 ('unknown-operation','annotation','Do the previous thing even though no previous task was supplied','اعمل الحاجة السابقة رغم إن مفيش مهمة سابقة اتبعتت','اعمل previous thing مع إن مفيش previous task'),
 ('cross-git-brightness','annotation','List Git remotes and also adjust Android display brightness','اعرض ريموتات جيت وكمان ظبط سطوع شاشة أندرويد','هات git remotes وكمان adjust Android brightness'),
 ('contextless-again','annotation','Repeat the other request whose details are missing here','كرر الطلب التاني اللي تفاصيله مش موجودة هنا','Repeat الـother request اللي details بتاعته missing'),
 ('cross-search-font','annotation','Search for a JVM research paper and increase Android font scale','دور على ورقة بحث لآلة جافا وكبّر خط أندرويد','دور على JVM paper وكبّر Android font_scale')]
}


def old_texts():
    texts,_=blocked()
    texts.update(canonical(r['text']) for r in read(TRUST/'fresh.tsv'))
    return texts


def rows():
    result=[]
    for label,scenarios in SCENARIOS.items():
        for index,(scenario,anchor,*texts) in enumerate(scenarios):
            split='train' if index<6 else 'development' if index<8 else 'calibration' if index<10 else 'test'
            for language,text in zip(['en','ar','mixed'],texts):
                group='contract-'+('refuse' if label=='-' else label)+'-'+scenario
                result.append(dict(split=split,group=group,language=language,expected_domain=label,anchor=anchor,text=text))
    return result


def validate(data):
    if len(data)!=216:raise ValueError('Expected216 authored rows')
    old=old_texts();seen=set();groups={};counts=Counter()
    for r in data:
        key=canonical(r['text']);label=r['expected_domain'];split=r['split']
        if not key or key in old or key in seen or len(r['text'])>512 or any(c in r['text'] for c in '\t\r\n'):raise ValueError('Contract corpus duplicate/contamination: '+r['group'])
        if label not in SCENARIOS or split not in ('train','development','calibration','test') or r['language'] not in ('ar','en','mixed') or r['anchor'] not in ANCHORS:raise ValueError('Contract corpus schema changed')
        expected={'repository':{'runtime','github'},'build':{'runtime','build'},'device':{'settings'},'knowledge':{'web','memory'},'chat':{'annotation'},'-':{'annotation'}}[label]
        if r['anchor'] not in expected:raise ValueError('Wrong source domain anchor')
        groups.setdefault(r['group'],[]).append(r);seen.add(key);counts[split,label,r['language']]+=1
    for group in groups.values():
        if len(group)!=3 or {r['language'] for r in group}!={'ar','en','mixed'} or len({(r['split'],r['expected_domain'],r['anchor']) for r in group})!=1:raise ValueError('Related variants crossed partition/label')
    for split,number in [('train',6),('development',2),('calibration',2),('test',2)]:
        for label in SCENARIOS:
            for language in ('ar','en','mixed'):
                if counts[split,label,language]!=number:raise ValueError('Missing class/language/partition stratum')


def verify():
    p=json.loads((DEST/'provenance.json').read_text())
    if p['author']!='assistant-authored source-anchored engineering examples' or p['independent_human_annotations'] is not False or p['runtime_execution_verified'] is not False or p['rows']!=216 or p['groups']!=72:raise ValueError('Authorship claim changed')
    expected_files={'neural-core/fixtures/contract-intents/corpus.tsv','neural-core/fixtures/contract-intents/protocol.json'}|{path for path,_ in ANCHORS.values()}
    if set(p['files'])!=expected_files:raise ValueError('Missing or unrelated corpus/source pin')
    for path,expected in p['files'].items():
        if hashlib.sha256((ROOT/path).read_bytes()).hexdigest()!=expected:raise ValueError('Frozen source/corpus hash changed:'+path)
    if set(p['anchors'])!=set(ANCHORS):raise ValueError('Contract anchors changed')
    for name,(path,needle) in ANCHORS.items():
        lines=(ROOT/path).read_text().splitlines();a=p['anchors'][name]
        if a['path']!=path or lines[a['line']-1].find(needle)<0:raise ValueError('Contract anchor moved or vanished')
    validate(read(DEST/'corpus.tsv'));print('Contract authored corpus verified:216 rows,72 grouped scenarios; source anchors are not execution proof')


if __name__=='__main__':
    if sys.argv[1:]==['--verify']:verify()
    elif not sys.argv[1:]:
        data=rows();validate(data);out=io.StringIO();w=csv.DictWriter(out,list(data[0]),delimiter='\t',lineterminator='\n');w.writeheader();w.writerows(data);(DEST/'corpus.tsv').write_text(out.getvalue())
        paths=[DEST/'corpus.tsv',DEST/'protocol.json']+[ROOT/path for path,_ in ANCHORS.values()]
        anchors={name:dict(path=path,line=next(i+1 for i,line in enumerate((ROOT/path).read_text().splitlines()) if needle in line)) for name,(path,needle) in ANCHORS.items()}
        p=dict(author='assistant-authored source-anchored engineering examples',independent_human_annotations=False,runtime_execution_verified=False,rows=216,groups=72,source_main='d08e99e92491c94757299fdf56f7815ae41fe34b',anchors=anchors,files={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},limits='Authored wording and annotation; source anchors prove definition presence only. Not human traffic, actual execution, authorization or semantic-OOD benchmark. All variants remain grouped; old task families can recur.')
        (DEST/'provenance.json').write_text(json.dumps(p,indent=2)+'\n');verify()
    else:raise SystemExit('Usage: prepare_contract_intents.py [--verify]')
