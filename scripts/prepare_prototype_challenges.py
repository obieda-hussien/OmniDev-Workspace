"""Freeze explicitly authored engineering challenges; never use them to fit prototypes or policies."""
import csv
import hashlib
import io
import json
from pathlib import Path
from reserve_utility_eval import ROOT, blocked, canonical, read

DEST=ROOT/'neural-core/fixtures/prototype-support'
DATA=[
('repository','branch-rules','Read the protection rules for the main branch in this GitHub project','هات قواعد الحماية لفرع مين في مشروع جيتهوب ده','هات branch protection rules للـmain في GitHub'),
('repository','tag-commit','Find the commit attached to the latest repository tag','شوف آخر علامة إصدار في المستودع بتشير لأي كوميت','اعرض commit بتاع آخر repository tag'),
('repository','review-thread','Inspect unresolved review threads on this pull request','راجع التعليقات اللي لسه مفتوحة على طلب الدمج ده','افحص unresolved review threads في الـpull request'),
('repository','two-reads','List repository tags and then inspect the commit for one tag','اعرض علامات إصدار المستودع وبعدها اقرأ كوميت علامة منهم','هات repo tags وبعدها inspect tag commit'),
('build','target-one-test','Execute only the Room migration unit test with the Gradle test filter','شغل اختبار ترحيل قاعدة روم لوحده بفلتر اختبارات جرادل','شغل Room migration test بس باستخدام Gradle --tests'),
('build','kotlin-warning','Compile the Kotlin test sources and show compiler warnings','جمّع مصادر اختبارات كوتلن واعرض تحذيرات المترجم','اعمل compile للـKotlin test sources وهات compiler warnings'),
('build','report-path','Run the JVM tests and locate the generated JUnit XML report','نفذ اختبارات جي في إم وحدد مكان تقرير جي يونت الناتج','شغل JVM tests وهات path بتاع JUnit XML report'),
('build','two-checks','Build the norm debug variant and then execute its unit tests','ابني نسخة نورم للتجربة وبعدها نفذ اختبارات الوحدة لنفس النسخة','اعمل assembleNormDebug وبعدها testNormDebugUnitTest'),
('device','orientation','Inspect the current Android screen rotation setting','افحص إعداد تدوير شاشة أندرويد الحالي','اقرأ Android screen rotation setting الحالي'),
('device','notification-access','Check which phone apps currently have notification access','شوف تطبيقات الموبايل اللي مسموحلها تقرأ الإشعارات','اعرض apps اللي عندها notification access على الجهاز'),
('device','display-timeout','Read the phone screen timeout before proposing a new value','اقرأ مدة إطفاء شاشة الموبايل قبل اقتراح قيمة جديدة','هات screen timeout الحالي قبل أي تغيير على Android'),
('device','two-observations','Inspect phone battery state and then read the display brightness','افحص حالة بطارية الموبايل وبعدها اقرأ درجة سطوع الشاشة','افحص battery state وبعدها اقرأ display brightness'),
('knowledge','room-docs','Find official documentation explaining Room migration validation and cite it','هات التوثيق الرسمي اللي بيشرح التحقق من ترحيل روم مع المصدر','دور على official Room migration validation docs مع citation'),
('knowledge','paper-limits','Retrieve the paper and quote the stated limitations of its experiment','هات الورقة العلمية واقتبس القيود المذكورة للتجربة','هات paper limitations واقتباس موثوق من المصدر'),
('knowledge','note-source','Locate the saved architecture note and identify its original source','دور على مذكرة المعمارية المحفوظة وحدد مصدرها الأصلي','هات saved architecture note وحدد original source'),
('knowledge','two-citations','Find the coroutine cancellation guide and quote two relevant passages','هات دليل إلغاء الكوروتين واقتبس منه فقرتين مناسبتين','دور في coroutine cancellation guide وهات two citations'),
('chat','greeting','Good evening, how has your day been going','مساء الفل عامل إيه في يومك','Good evening يا صاحبي عامل إيه'),
('chat','encouragement','Give me a short encouraging message before I start studying','قول لي كلمة تشجيع صغيرة قبل ما أفتح المذاكرة','اديني short encouragement قبل studying'),
('chat','wordplay','Tell me a light wordplay joke for a quick break','احكي لي نكتة خفيفة ألعب فيها بالكلمات في الاستراحة','هات wordplay joke خفيفة للـbreak'),
('chat','thanks','I appreciate the help, that explanation was clear','متشكر على مساعدتك الشرح ده كان واضح لي','Thanks على المساعدة الشرح كان clear'),
('-', 'taxi','Arrange an airport cab for next Tuesday after sunrise','رتب لي عربية أجرة للمطار يوم التلات بعد شروق الشمس','اعمل airport cab reservation ليوم Tuesday بعد sunrise'),
('-', 'bank','Transfer money from my bank account to pay the rent','حوّل مبلغ من حساب البنك عشان تدفع الإيجار','اعمل bank transfer عشان rent payment'),
('-', 'vague','Do the thing we have not described yet','اعمل الحاجة اللي لسه ما قلناش هي إيه','Handle الموضوع اللي مش محددينه لسه'),
('-', 'cross-domain','Inspect the GitHub diff and then turn down phone brightness','افحص فرق تعديلات جيتهوب وبعدها قلل سطوع الموبايل','راجع GitHub diff وبعدها قلل Android brightness'),
('-', 'hotel','Reserve a hotel room for two travellers next weekend','احجز أوضة فندق لشخصين في نهاية الأسبوع الجاي','اعمل hotel reservation لشخصين next weekend')]


def rows():
    result=[]
    for domain,scenario,*texts in DATA:
        for language,text in zip(['en','ar','mixed'],texts):
            result.append(dict(id='authored-'+scenario+'-'+language,group='authored-'+scenario,language=language,expected_domain=domain,kind='supported' if domain!='-' else 'requires_refusal',text=text))
    return result


def validate(data):
    old,_=blocked();old|={canonical(r['text']) for r in read(ROOT/'neural-core/fixtures/utility-expert/fresh.tsv')}
    seen=set();ids=set();groups={}
    if len(data)!=75:raise ValueError('Authored challenge bounds changed')
    for r in data:
        key=canonical(r['text'])
        if not key or key in old or key in seen or r['id'] in ids or len(r['text'])>512 or any(c in r['text'] for c in '\t\r\n'):raise ValueError('Authored challenge duplicate/contamination: '+r['id'])
        if r['language'] not in ['ar','en','mixed'] or r['expected_domain'] not in ['-','repository','build','device','knowledge','chat'] or r['kind']!=('requires_refusal' if r['expected_domain']=='-' else 'supported'):raise ValueError('Authored label changed')
        seen.add(key);ids.add(r['id']);groups.setdefault(r['group'],[]).append(r)
    for g in groups.values():
        if len(g)!=3 or {r['language'] for r in g}!={'ar','en','mixed'} or len({r['expected_domain'] for r in g})!=1:raise ValueError('Authored semantic group changed')


def verify():
    p=json.loads((DEST/'provenance.json').read_text())
    if p['author']!='assistant-authored synthetic engineering challenge' or p['training_use']!='none; held out from prototype fit and threshold/route selection':raise ValueError('Authored provenance changed')
    for path,expected in p['files'].items():
        if hashlib.sha256((ROOT/path).read_bytes()).hexdigest()!=expected:raise ValueError('Authored source/protocol hash changed')
    validate(read(DEST/'authored.tsv'));print('Authored challenge provenance verified: 75 rows, 25 semantic groups; not independent traffic')


if __name__=='__main__':
    import sys
    if sys.argv[1:]==['--verify']:verify()
    elif not sys.argv[1:]:
        data=rows();validate(data);s=io.StringIO();w=csv.DictWriter(s,list(data[0]),delimiter='\t',lineterminator='\n');w.writeheader();w.writerows(data);(DEST/'authored.tsv').write_text(s.getvalue())
        paths=[DEST/'authored.tsv',DEST/'protocol.json',ROOT/'docs/TOOL_EXECUTION_CONTRACT.md'];p=dict(rows=75,groups=25,author='assistant-authored synthetic engineering challenge',annotation='Labels follow the narrow five-domain single-step experiment, not tool authorization.',training_use='none; held out from prototype fit and threshold/route selection',files={str(x.relative_to(ROOT)):hashlib.sha256(x.read_bytes()).hexdigest() for x in paths});(DEST/'provenance.json').write_text(json.dumps(p,indent=2)+'\n');verify()
    else:raise SystemExit('Usage: prepare_prototype_challenges.py [--verify]')
