"""Validate a separately reserved compound challenge without rewriting observed relevance labels."""
import csv,hashlib,json,re
from pathlib import Path
import prepare_product_support as original
from prepare_ranked_support import normalized
ROOT=Path(__file__).resolve().parents[1]
FIXTURES=ROOT/'neural-core/fixtures/hybrid-support-v3'
def validate():
    docs,old=original.validate();path=FIXTURES/'fresh.tsv';protocol=json.loads((FIXTURES/'protocol.json').read_text())
    if hashlib.sha256(path.read_bytes()).hexdigest()!=protocol['fresh_sha256'] or protocol['production_eligible'] is not False or protocol['independent_human_labels'] is not False:raise ValueError('Frozen hybrid protocol mismatch')
    with path.open() as f:rows=list(csv.DictReader(f,delimiter='\t'))
    with (ROOT/'neural-core/fixtures/product-support-v2/fresh.tsv').open() as f:observed=old+list(csv.DictReader(f,delimiter='\t'))
    topics={d['group'] for d in docs};seen={normalized(q['query']) for q in observed}|{normalized(d['question']) for d in docs};identities={q['id'] for q in observed};groups={}
    if len(rows)!=protocol['fresh_count']:raise ValueError('Missing hybrid rows')
    for q in rows:
        if set(q)!={'id','group','language','primary','secondary','query'} or q['id'] in identities or q['language'] not in ('en','ar','mixed') or not 4<=len(q['query'])<=512:raise ValueError('Invalid hybrid query')
        p=set() if q['primary']=='-' else set(q['primary'].split(','));s=set() if q['secondary']=='-' else set(q['secondary'].split(','))
        if not p<=topics or not s<=topics or p&s or len(p)>2 or not p and s:raise ValueError('Invalid relevance requirements')
        signature=(tuple(sorted(p)),tuple(sorted(s)))
        if q['group'] in groups and groups[q['group']]!=signature:raise ValueError('Translation label mismatch')
        groups[q['group']]=signature;arabic=bool(re.search(r'[\u0600-\u06ff]',q['query']));latin=bool(re.search('[A-Za-z]',q['query']))
        if q['language']=='en' and arabic or q['language']=='ar' and not arabic or q['language']=='mixed' and not(arabic and latin):raise ValueError('Hybrid language mismatch')
        if normalized(q['query']) in seen:raise ValueError('Observed/duplicate query in hybrid challenge')
        seen.add(normalized(q['query']));identities.add(q['id'])
    for lang in ('en','ar','mixed'):
        subset=[q for q in rows if q['language']==lang]
        if len(subset)!=36 or sum(q['primary']=='-' for q in subset)!=12 or sum(',' in q['primary'] for q in subset)!=12:raise ValueError('Missing compound/language stratum')
        if set().union(*(set(q['primary'].split(',')) for q in subset if q['primary']!='-'))!=topics:raise ValueError('Missing FAQ requirement')
    return rows
if __name__=='__main__':print('Hybrid compound challenge verified:',len(validate()))
