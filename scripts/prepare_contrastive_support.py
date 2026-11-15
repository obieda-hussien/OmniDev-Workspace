"""Validate the pre-training recall experiment reservation and existing-data exclusions."""
import csv,hashlib,json,re
from pathlib import Path
from prepare_recall_support import validate as validate_prior
from prepare_answerability_support import read,FIXTURES,ROOT
from prepare_ranked_support import normalized
DATA=FIXTURES/'support-contrastive-v10'
def validate(rows=None,protocol=None):
    validate_prior();p=json.loads((DATA/'protocol.json').read_text()) if protocol is None else protocol
    rows=read(DATA/'fresh.tsv') if rows is None else rows
    sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
    if p['version']!=10 or p['count']!=96 or p['groups']!=16 or p['fresh_sha256']!=sha(DATA/'fresh.tsv') or p['source']!='knowledge/omni-product.jsonl' or p['source_sha256']!=sha(ROOT/p['source']) or p['frozen_v9_sha256']!=sha(ROOT/'neural-core/checkpoints/recall-support-v9/TrainedRecallAwareSupport.java') or p['contrast_sha256']!=sha(FIXTURES/'support-answerability-v8/contrast.tsv') or any(p[k] is not False for k in ('production_eligible','independent_human_labels','physical_device_tested')):raise ValueError('Contrastive reservation/source drift')
    old=[]
    for name in ('product-support/queries.tsv','product-support-v2/fresh.tsv','hybrid-support-v3/fresh.tsv','support-data-v4/queries.tsv','query-memory-v5/fresh.tsv','support-reliability-v6/fresh.tsv','support-subword-v7/fresh.tsv','support-answerability-v8/contrast.tsv','support-answerability-v8/fresh.tsv','support-answerability-v8/followup.tsv','support-recall-v9/fresh.tsv'):old+=read(FIXTURES/name)
    docs=[json.loads(s) for s in (ROOT/p['source']).read_text().splitlines()];topics={d['group'] for d in docs}
    seen={normalized(r['query']) for r in old}|{normalized(d['question']) for d in docs};ids={r['id'] for r in old};groups={}
    for r in rows:
        if set(r)!={'id','group','split','language','topic','label','query'} or r['split']!='fresh' or r['topic'] not in topics or r['language'] not in ('en','ar','mixed') or r['label'] not in ('0','1') or not 4<=len(r['query'])<=512 or r['id'] in ids or normalized(r['query']) in seen or any(c in r['query'] for c in '\t\r\n') or not r['id'].startswith(r['group']+'-'):raise ValueError('Invalid/observed contrastive question')
        ar=bool(re.search(r'[\u0600-\u06ff]',r['query']));latin=bool(re.search('[A-Za-z]',r['query']))
        if r['language']=='en' and ar or r['language']=='ar' and not ar or r['language']=='mixed' and not(ar and latin):raise ValueError('Contrastive language mismatch')
        g=groups.setdefault(r['group'],[r['topic'],set()]);key=(r['language'],r['label'])
        if g[0]!=r['topic'] or key in g[1]:raise ValueError('Contrastive pair conflict')
        g[1].add(key);seen.add(normalized(r['query']));ids.add(r['id'])
    if len(rows)!=96 or len(groups)!=16 or len({g[0] for g in groups.values()})!=16 or any(g[1]!={(lang,label) for lang in ('en','ar','mixed') for label in ('0','1')} for g in groups.values()):raise ValueError('Incomplete recall pairs')
    return rows
if __name__=='__main__':print('Contrastive reservation verified:',len(validate()),'new authored questions')
