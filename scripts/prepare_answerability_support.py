"""Freeze paired answerability annotations; no contrast/test text enters proposal indexes."""
import csv,hashlib,json,re
from pathlib import Path
from prepare_ranked_support import normalized
ROOT=Path(__file__).resolve().parents[1];FIXTURES=ROOT/'neural-core/fixtures';DATA=FIXTURES/'support-answerability-v8'
def read(path):
    with path.open() as f:return list(csv.DictReader(f,delimiter='\t'))
def validate(rows=None,protocol=None):
    p=json.loads((DATA/'protocol.json').read_text()) if protocol is None else protocol
    rows=read(DATA/'contrast.tsv')+read(DATA/'fresh.tsv') if rows is None else rows
    if (p['version']!=8 or p['contrast_count']!=174 or p['fresh_count']!=72 or p['source']!='knowledge/omni-product.jsonl'
        or any(p[k] is not False for k in ('production_eligible','independent_human_labels','physical_device_tested'))
        or p['source_sha256']!=hashlib.sha256((ROOT/p['source']).read_bytes()).hexdigest()
        or any(p[name+'_sha256']!=hashlib.sha256((DATA/(name+'.tsv')).read_bytes()).hexdigest() for name in ('contrast','fresh'))):raise ValueError('Answerability reservation/source mismatch')
    docs=[json.loads(s) for s in (ROOT/p['source']).read_text().splitlines()];topics={d['group'] for d in docs}
    previous=[]
    for name in ('product-support/queries.tsv','product-support-v2/fresh.tsv','hybrid-support-v3/fresh.tsv','support-data-v4/queries.tsv','query-memory-v5/fresh.tsv','support-reliability-v6/fresh.tsv','support-subword-v7/fresh.tsv'):previous+=read(FIXTURES/name)
    seen={normalized(q['query']) for q in previous}|{normalized(d['question']) for d in docs};ids={q['id'] for q in previous};groups={};counts={}
    order=sorted(topics,key=lambda topic:hashlib.sha256(topic.encode()).hexdigest())
    assignment={t:('train' if i<16 else 'development' if i<22 else 'temperature' if i<25 else 'policy') for i,t in enumerate(order)}
    if len(rows)!=246:raise ValueError('Missing answerability rows')
    for r in rows:
        if (set(r)!={'id','group','split','language','topic','label','query'} or r['id'] in ids or r['topic'] not in topics or r['language'] not in ('en','ar','mixed')
            or r['label'] not in ('0','1') or r['split'] not in ('train','development','temperature','policy','fresh') or not 4<=len(r['query'])<=512
            or any(c in r['query'] for c in '\t\r\n') or not r['id'].startswith(r['group']+'-')):raise ValueError('Invalid answerability row')
        if r['split']!='fresh' and r['split']!=assignment[r['topic']]:raise ValueError('Answerability partition drift')
        signature=(r['topic'],r['split']);g=groups.setdefault(r['group'],[signature,set()]);key=(r['language'],r['label'])
        if g[0]!=signature or key in g[1]:raise ValueError('Pair/translation leakage or duplicate')
        g[1].add(key);ar=bool(re.search(r'[\u0600-\u06ff]',r['query']));latin=bool(re.search('[A-Za-z]',r['query']))
        if r['language']=='en' and ar or r['language']=='ar' and not ar or r['language']=='mixed' and not(ar and latin):raise ValueError('Answerability language mismatch')
        q=normalized(r['query'])
        if q in seen:raise ValueError('Duplicate/observed answerability question')
        seen.add(q);ids.add(r['id']);counts[r['split']]=counts.get(r['split'],0)+1
    expected={(lang,label) for lang in ('en','ar','mixed') for label in ('0','1')}
    if len(groups)!=41 or any(g[1]!=expected for g in groups.values()) or counts!={'train':96,'development':36,'temperature':18,'policy':24,'fresh':72}:raise ValueError('Incomplete balanced contrast groups')
    if {r['topic'] for r in rows if r['split']!='fresh'}!=topics:raise ValueError('Missing contrast topics')
    return rows
def validate_followup(rows=None,protocol=None):
    original=validate();p=json.loads((DATA/'followup-protocol.json').read_text()) if protocol is None else protocol
    rows=read(DATA/'followup.tsv') if rows is None else rows
    if p['version']!='8-preservation-followup' or p['groups']!=8 or p['count']!=48 or any(not re.fullmatch('[0-9a-f]{64}',p[k]) for k in ('initial_selected_source_sha256','initial_selection_sha256')) or p['followup_sha256']!=hashlib.sha256((DATA/'followup.tsv').read_bytes()).hexdigest() or any(p[k] is not False for k in ('production_eligible','independent_human_labels','physical_device_tested')):raise ValueError('Follow-up reservation mismatch')
    seen={normalized(r['query']) for r in original};ids={r['id'] for r in original};topics={r['topic'] for r in original};groups={}
    for name in ('product-support/queries.tsv','product-support-v2/fresh.tsv','hybrid-support-v3/fresh.tsv','support-data-v4/queries.tsv','query-memory-v5/fresh.tsv','support-reliability-v6/fresh.tsv','support-subword-v7/fresh.tsv'):
        for r in read(FIXTURES/name):seen.add(normalized(r['query']));ids.add(r['id'])
    for r in rows:
        if set(r)!={'id','group','split','language','topic','label','query'} or r['split']!='followup' or r['topic'] not in topics or r['label'] not in ('0','1') or r['language'] not in ('en','ar','mixed') or not 4<=len(r['query'])<=512 or r['id'] in ids or normalized(r['query']) in seen or any(c in r['query'] for c in '\t\r\n') or not r['id'].startswith(r['group']+'-'):raise ValueError('Invalid/observed follow-up row')
        ar=bool(re.search(r'[\u0600-\u06ff]',r['query']));latin=bool(re.search('[A-Za-z]',r['query']))
        if r['language']=='en' and ar or r['language']=='ar' and not ar or r['language']=='mixed' and not(ar and latin):raise ValueError('Follow-up language mismatch')
        group=groups.setdefault(r['group'],[r['topic'],set()]);key=(r['language'],r['label'])
        if group[0]!=r['topic'] or key in group[1]:raise ValueError('Follow-up translation conflict')
        group[1].add(key);seen.add(normalized(r['query']));ids.add(r['id'])
    if len(rows)!=48 or len(groups)!=8 or any(g[1]!={(lang,label) for lang in ('en','ar','mixed') for label in ('0','1')} for g in groups.values()):raise ValueError('Incomplete follow-up groups')
    return rows
if __name__=='__main__':print('Answerability pairs verified:',len(validate()),'initial and',len(validate_followup()),'follow-up rows')
