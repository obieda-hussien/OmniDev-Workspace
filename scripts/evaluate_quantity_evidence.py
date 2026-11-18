"""Replay the fixed v14 selected policy before evaluating a deterministic evidence veto."""
import csv
import json
import sys
from pathlib import Path

from fit_lexical_rescue import FIX, ROOT, source_rows
from fit_refusal_rescue import eligible, features
from fit_topic_rerank import read_rows
from prepare_ranked_support import normalized

NEW = FIX / 'quantity-evidence-v15/probes.tsv'
PRIOR = ROOT / 'neural-core/checkpoints/refusal-rescue-v12/report.json'


def corpus():
    queries, _ = source_rows()
    seen = set(map(normalized, queries.values()))
    seen.update(normalized(d['question']) for d in map(json.loads,(ROOT/'knowledge/omni-product.jsonl').read_text().splitlines()))
    fresh = {}
    groups = set()
    with NEW.open() as f:
        for row in csv.DictReader(f,delimiter='\t'):
            if row['group'] in groups or row['label'] not in ('0','1'):
                raise ValueError('Invalid group')
            groups.add(row['group'])
            for lang in ('en','ar','mixed'):
                id_ = 'q15-'+row['group']+'-'+lang
                query = row[lang]
                if not query or normalized(query) in seen:
                    raise ValueError('Duplicate query '+id_)
                seen.add(normalized(query))
                fresh[id_] = (row['topic'],row['label']=='1',lang)
                queries[id_] = query
    if len(queries)!=2232 or len(groups)!=12 or len(fresh)!=36:
        raise ValueError('Incomplete replay')
    return queries, fresh


def prepare(out):
    queries, fresh = corpus()
    (out/'queries.tsv').write_text(''.join(
        f"{k}\t{fresh[k][0] if k in fresh else ''}\t{q}\n" for k,q in queries.items()))


def audit(out, previous):
    queries, fresh = corpus()
    old = {r.id:r for r in read_rows(previous)}
    threshold = json.loads(PRIOR.read_text())['arms']['retrieval']['threshold']
    docs = {d['id']:d for d in map(json.loads,(ROOT/'knowledge/omni-product.jsonl').read_text().splitlines())}
    cohorts, named, witnesses = {}, {}, {}
    android_cases = []
    seen = set()
    for line in (out/'decisions.tsv').read_text().splitlines():
        k,topic,flag,action,oracle,source,sentence,value = line.split('\t')
        if k in seen or k not in queries or flag not in ('true','false'):
            raise ValueError('Invalid decision row')
        seen.add(k)
        accepted = flag == 'true'
        bucket = 'historical'
        if k in fresh:
            expected, supported, lang = fresh[k]
            android_cases.append('\t'.join((k,expected,queries[k],oracle,source or "-",sentence or "-",value or "-")))
            correct = supported and topic == expected
            bucket = 'fresh'
            n = named.setdefault('supported' if supported else 'unsupported',{})
            n[oracle] = n.get(oracle,0)+1
            if oracle=='SUPPORTED':
                if source != expected+':en' or not sentence or not value or value not in sentence or sentence not in docs[source]['answer']:
                    raise AssertionError('Invalid witness '+k)
                witnesses[k] = dict(source=source,sentence=sentence,value=value)
        else:
            r = old[k]
            lang = r.language
            correct = topic in r.labels
            if topic != (r.hits[0].topic if r.hits else '') or accepted != (
                    r.accepted or eligible(r) and features(r)[1]>=threshold):
                raise AssertionError('Frozen policy mismatch '+k)
        keys = [bucket, bucket+'_'+lang]
        if k.startswith('lex14-'):
            keys.append('v14_observed')
        for key in keys:
            m=cohorts.setdefault(key,dict(rows=0,correct_before=0,wrong_before=0,correct_after=0,
                                          wrong_after=0,lost=[],prevented=[]))
            m['rows']+=1
            if accepted:
                kind='correct' if correct else 'wrong'
                m[kind+'_before']+=1
                if action=='ABSTAIN':
                    m['lost' if correct else 'prevented'].append(k)
                else:
                    m[kind+'_after']+=1
    if seen != set(queries) or len(old)!=2196:
        raise AssertionError('Incomplete replay')
    (out/'android-cases.tsv').write_text('\n'.join(android_cases)+'\n')
    report=dict(cohorts=cohorts,named_reference=named,witnesses=witnesses,production_eligible=False)
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__':
    out=Path(sys.argv[2])
    if sys.argv[1]=='prepare':
        prepare(out)
    elif sys.argv[1]=='audit':
        audit(out,Path(sys.argv[3]))
    else:
        raise ValueError('Unknown command')
