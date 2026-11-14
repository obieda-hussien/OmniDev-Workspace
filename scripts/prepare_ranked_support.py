"""Freeze-checked new-wording probes; old observed tests remain outside fitting/selection."""
import csv
import hashlib
import json
from pathlib import Path
import re
import unicodedata
import prepare_product_support as original
ROOT=Path(__file__).resolve().parents[1]
FIXTURES=ROOT/'neural-core/fixtures/product-support-v2'

def normalized(s):return re.sub(r'[^\w]+',' ',unicodedata.normalize('NFKC',s).casefold()).strip()
def validate():
    docs,old=original.validate();protocol=json.loads((FIXTURES/'protocol.json').read_text());path=FIXTURES/'fresh.tsv'
    if hashlib.sha256(path.read_bytes()).hexdigest()!=protocol['fresh_query_sha256']:raise ValueError('Fresh probe hash mismatch')
    if protocol['production_eligible'] is not False or protocol['independent_human_labels'] is not False:raise ValueError('False readiness/label claim')
    with path.open() as source:fresh=list(csv.DictReader(source,delimiter='\t'))
    if len(fresh)!=protocol['fresh_count']:raise ValueError('Fresh probe count')
    topics={d['group'] for d in docs};seen={normalized(q['query']) for q in old}|{normalized(d['question']) for d in docs};ids={q['id'] for q in old};groups={}
    for q in fresh:
        if (set(q)!={'id','group','split','language','expected_topic','query'} or q['split']!='test' or q['language'] not in ('en','ar','mixed') or q['expected_topic'] not in topics|{'-'} or not 4<=len(q['query'])<=512 or q['id'] in ids):raise ValueError('Invalid new probe')
        arabic=bool(re.search(r'[\u0600-\u06ff]',q['query'])); latin=bool(re.search(r'[A-Za-z]',q['query']))
        if q['language']=='en' and arabic or q['language']=='ar' and not arabic or q['language']=='mixed' and not (arabic and latin):raise ValueError('Mislabelled query language')
        if normalized(q['query']) in seen:raise ValueError('Observed duplicate in fresh probes')
        seen.add(normalized(q['query']));ids.add(q['id']);groups.setdefault(q['group'],set()).add(q['expected_topic'])
    if any(len(labels)!=1 for labels in groups.values()):raise ValueError('Incoherent translated labels')
    for language in ('en','ar','mixed'):
        if {q['expected_topic'] for q in fresh if q['language']==language and q['expected_topic']!='-'}!=topics:raise ValueError('Missing support topic')
        if sum(q['language']==language and q['expected_topic']!='-' for q in fresh)!=29 or sum(q['language']==language and q['expected_topic']=='-' for q in fresh)!=10:raise ValueError('Missing multilingual challenge coverage')
    return fresh

if __name__=='__main__':print('Reserved new-wording probes verified:',len(validate()))
