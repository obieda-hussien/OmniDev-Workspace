"""Bind train-only lexical exemplars and reserve a new authored evaluation before fitting."""
import csv,hashlib,json,re
from pathlib import Path
import prepare_product_support as original
import prepare_support_data_v4 as expanded
from prepare_ranked_support import normalized
ROOT=Path(__file__).resolve().parents[1]
FIXTURES=ROOT/'neural-core/fixtures/query-memory-v5'

def validate():
 docs,old=original.validate();new=expanded.validate()
 for name in ['product-support-v2/fresh.tsv','hybrid-support-v3/fresh.tsv']:
  with (ROOT/'neural-core/fixtures'/name).open() as f:old+=list(csv.DictReader(f,delimiter='\t'))
 observed=old+new;p=json.loads((FIXTURES/'protocol.json').read_text());path=FIXTURES/'fresh.tsv'
 if p['source']!='knowledge/omni-product.jsonl' or hashlib.sha256((ROOT/p['source']).read_bytes()).hexdigest()!=p['source_sha256'] or hashlib.sha256(path.read_bytes()).hexdigest()!=p['fresh_sha256'] or any(p[k] is not False for k in ['production_eligible','independent_human_labels','physical_device_tested']):raise ValueError('Query-memory reservation mismatch')
 with path.open() as f:rows=list(csv.DictReader(f,delimiter='\t'))
 topics={d['group'] for d in docs};seen={normalized(q['query']) for q in observed}|{normalized(d['question']) for d in docs};ids={q['id'] for q in observed};groups={}
 if len(rows)!=p['fresh_count']:raise ValueError('Missing fresh memory probes')
 for q in rows:
  if set(q)!={'id','group','language','primary','secondary','query'} or q['id'] in ids or q['language'] not in ('en','ar','mixed') or not 4<=len(q['query'])<=512:raise ValueError('Invalid memory probe')
  labels=set() if q['primary']=='-' else set(q['primary'].split(','))
  if not labels<=topics or len(labels)>2 or q['secondary']!='-':raise ValueError('Unknown memory requirement')
  signature=tuple(sorted(labels));g=groups.setdefault(q['group'],[signature,set()])
  if signature!=g[0] or q['language'] in g[1]:raise ValueError('Translation label mismatch')
  g[1].add(q['language']);ar=bool(re.search(r'[\u0600-\u06ff]',q['query']));latin=bool(re.search('[A-Za-z]',q['query']))
  if q['language']=='en' and ar or q['language']=='ar' and not ar or q['language']=='mixed' and not(ar and latin):raise ValueError('Memory language mismatch')
  key=normalized(q['query'])
  if key in seen:raise ValueError('Observed/duplicate memory query: '+q['id'])
  ids.add(q['id']);seen.add(key)
 if any(g[1]!={'en','ar','mixed'} for g in groups.values()):raise ValueError('Incomplete memory translations')
 for lang in ('en','ar','mixed'):
  part=[q for q in rows if q['language']==lang]
  if len(part)!=46 or sum(q['primary']=='-' for q in part)!=11 or sum(',' in q['primary'] for q in part)!=6 or set().union(*(set(q['primary'].split(',')) for q in part if q['primary']!='-'))!=topics:raise ValueError('Missing memory stratum')
 exemplars=[dict(id=q['id'],group='old:'+q['group'],topic=q['expected_topic'],text=q['query']) for q in old if q.get('split')=='train' and q.get('expected_topic','-')!='-']
 exemplars += [dict(id=q['id'],group='new:'+q['group'],topic=q['primary'],text=q['query']) for q in new if q['split']=='train' and q['primary']!='-']
 if len(exemplars)!=351:raise ValueError('TRAIN-only exemplar corpus mismatch')
 return rows,exemplars

def export(out):
 rows,examples=validate();out=Path(out);out.mkdir(parents=True,exist_ok=True)
 with (out/'exemplars.tsv').open('w') as f:
  w=csv.DictWriter(f,fieldnames=['id','group','topic','text'],delimiter='\t',lineterminator='\n');w.writeheader();w.writerows(examples)
 print('Query memory verified:',len(examples),'TRAIN exemplars;',len(rows),'new authored probes')
if __name__=='__main__':
 import sys
 export(sys.argv[1] if len(sys.argv)>1 else 'build/query-memory-v5')
