"""Compare lexical and numeric filters on the fixed v12 rescue candidates."""
import csv
import hashlib
import json
import math
import re
import sys
import unicodedata
from collections import Counter
from functools import lru_cache
from pathlib import Path

from fit_refusal_rescue import correct, eligible, features, sigmoid
from fit_topic_rerank import read_rows

ROOT = Path(__file__).resolve().parents[1]
FIX = ROOT / 'neural-core/fixtures'
DEV = {'telegram-owner', 'recover-task', 'github-access', 'git-environment'}
ARMS = ('control','numeric','lexical')
OLDFILES = ('product-support/queries','product-support-v2/fresh','hybrid-support-v3/fresh',
            'support-data-v4/queries','query-memory-v5/fresh','support-reliability-v6/fresh',
            'support-subword-v7/fresh','support-answerability-v8/contrast',
            'support-answerability-v8/fresh','support-answerability-v8/followup',
            'support-recall-v9/fresh','support-contrastive-v10/fresh')


def source_rows():
    queries, topics = {}, {}
    def add(id_, q, topic):
        if id_ in queries:
            raise ValueError('Duplicate fixture '+id_)
        queries[id_] = q
        if topic:
            topics[id_] = topic
    for name in OLDFILES:
        with (FIX/(name+'.tsv')).open() as handle:
            for row in csv.DictReader(handle,delimiter='\t'):
                add(row['id'], row['query'], row.get('topic'))
    wide = [('topic-rerank-v11','rerank11'),('refusal-rescue-v12','rescue12'),
            *((f'paired-rescue-v13/{s}','paired13') for s in ('train','dev','test')),
            ('lexical-rescue-v14','lex14')]
    for folder,prefix in wide:
        with (FIX/folder/'pairs.tsv').open() as handle:
            for row in csv.DictReader(handle,delimiter='\t'):
                for lang in ('en','ar','mixed'):
                    add(f"{prefix}-{row['topic']}-{row['label']}-{lang}",row[lang],row['topic'])
    return queries, topics


def tokens(text):
    normalized = unicodedata.normalize('NFKC',text).casefold().translate(str.maketrans('أإآى','اااي'))
    return re.findall(r'[^\W_]+', ''.join(c for c in normalized if not unicodedata.combining(c)))


@lru_cache(maxsize=32768)
def bucket(term):
    h = int.from_bytes(hashlib.blake2s(term.encode(),digest_size=4).digest(),'little')
    return 6 + h % 2048, 1 if h & 2048 else -1


def vector(row, query, doc, lexical):
    values = dict(enumerate(features(row)))
    if lexical:
        hashed = Counter()
        for token in tokens(query):
            tag = '+' if token in doc else '-'
            padded = '^'+token+'$'
            for term in ['w'+token]+['c'+padded[i:i+3] for i in range(len(padded)-2)]:
                index, sign = bucket(tag+term)
                hashed[index] += sign
        norm = math.sqrt(sum(v*v for v in hashed.values()))
        if norm:
            values.update({i:v/norm for i,v in hashed.items() if v})
    return tuple(values.items())


def raw_score(weights, x):
    return sum(weights[i]*v for i,v in x)


def train(train_ids, x, rows, size):
    counts = Counter(correct(rows[k]) for k in train_ids)
    if len(counts)!=2:
        raise ValueError('Both correctness classes required')
    weights = [0.]*size
    for _ in range(120):
        grad = [.01*w for w in weights]
        grad[0] = 0.
        for k in train_ids:
            y = correct(rows[k])
            delta = (sigmoid(raw_score(weights,x[k]))-y)/(2*counts[y])
            for i,v in x[k]:
                grad[i] += delta*v
        weights = [w-.4*g for w,g in zip(weights,grad)]
    return [round(w,9) for w in weights]


def metrics(ids, rows, scores, threshold):
    recovered = [k for k in ids if k in scores and scores[k]>=threshold]
    wrong = [k for k in recovered if not correct(rows[k])]
    return dict(n=len(ids), gained=len(recovered)-len(wrong), wrong=len(wrong),
                unsupported=sum(not rows[k].labels for k in wrong), wrong_ids=wrong,
                base_correct=sum(rows[k].accepted and correct(rows[k]) for k in ids),
                base_wrong=sum(rows[k].accepted and not correct(rows[k]) for k in ids))


def run(rows,out):
    rows = {r.id:r for r in rows}
    queries, topics = source_rows()
    if set(rows)!=set(queries) or len(rows)!=2196:
        raise ValueError('Incomplete source alignment')
    docs = {}
    for doc in map(json.loads,(ROOT/'knowledge/omni-product.jsonl').read_text().splitlines()):
        docs.setdefault(doc['group'],set()).update(tokens(doc['question']+' '+doc['answer']))
    fresh = [k for k in rows if rows[k].split=='fresh14']
    hold = {topics[k] for k in fresh}
    if len(fresh)!=36 or len(hold)!=6 or hold & DEV:
        raise ValueError('Invalid test topics')
    train_ids = sorted(k for k,t in topics.items() if t not in hold|DEV and rows[k].hits)
    dev = sorted(k for k,t in topics.items() if t in DEV)
    prior = json.loads((ROOT/'neural-core/checkpoints/refusal-rescue-v12/report.json').read_text())
    threshold = prior['arms']['retrieval']['threshold']
    candidates = [k for k,r in rows.items() if eligible(r) and features(r)[1]>=threshold]
    arms, weights_by_arm, keys = {}, {}, {}
    for arm in ARMS:
        weights = []
        scores = {k:1. for k in candidates}
        if arm!='control':
            ids = sorted(set(candidates)|set(train_ids))
            x = {k:vector(rows[k],queries[k],docs[rows[k].hits[0].topic],arm=='lexical') for k in ids}
            weights = train(train_ids,x,rows,2054 if arm=='lexical' else 6)
            scores = {k:sigmoid(raw_score(weights,x[k])) for k in candidates}
        grid = [0] if arm=='control' else [i/20 for i in range(21)]+[2.]
        def key(t):
            m = metrics(dev,rows,scores,t)
            return m['gained']-4*m['wrong'], -m['wrong'], t
        cutoff = max(grid,key=key)
        utility, neg_wrong, _ = key(cutoff)
        keys[arm] = (utility,neg_wrong,-ARMS.index(arm))
        weights_by_arm[arm] = weights
        cohorts = dict(development=dev,historical=[k for k in rows if k not in fresh],fresh=fresh)
        cohorts.update({s:[k for k in fresh if rows[k].language==s] for s in ('en','ar','mixed')})
        arms[arm] = dict(cutoff=cutoff,metrics={s:metrics(ids,rows,scores,cutoff) for s,ids in cohorts.items()})
    selected = max(ARMS,key=keys.get)
    report = dict(selected=selected, training=len(train_ids),train_topics=sorted({topics[k] for k in train_ids}),
                  dev_topics=sorted(DEV),test_topics=sorted(hold), production_eligible=False,arms=arms)
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    (out/'weights.json').write_text(json.dumps(weights_by_arm, allow_nan=False)+'\n')


if __name__=='__main__':
    run(read_rows(Path(sys.argv[1])),Path(sys.argv[2]))
