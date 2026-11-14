"""Freeze unused public negative-only groups before trust fitting; no positive/generalization claim."""
import csv
import hashlib
import io
import json
import pathlib
import sys
from collections import Counter
from reserve_utility_eval import ROOT, HASHES, canonical, read, blocked as earlier, DEST as UTILITY

DEST=ROOT/'neural-core/fixtures/expert-trust'
TOPICS=('transport_ticket','lists_createoradd','email_addcontact','iot_cleaning')


def blocked():
    texts,ids=earlier()
    for r in read(UTILITY/'fresh.tsv'):
        texts.add(canonical(r['text']));ids.add(r['source_id'])
    texts.update(canonical(r['text']) for r in read(ROOT/'neural-core/fixtures/prototype-support/authored.tsv'))
    return texts,ids


def validate(rows):
    old,ids=blocked();seen=set();groups={}
    if len(rows)!=80:raise ValueError('Expected exactly80 negative-only observations')
    for r in rows:
        identity,key=r['source_id'],canonical(r['text'])
        if not identity.isdecimal() or len(identity)>10 or identity in ids or r['source_partition']!='test' or r['locale'] not in ('ar-SA','en-US'):raise ValueError('Trust source overlap/partition')
        if not key or key in old or key in seen or len(r['text'])>512 or any(x in r['text'] for x in '\t\r\n'):raise ValueError('Trust text overlap/bounds')
        if r['intent'] not in TOPICS or r['expected_domain']!='-':raise ValueError('Trust negative mapping changed')
        seen.add(key);groups.setdefault(identity,[]).append(r)
    for group in groups.values():
        if len(group)!=2 or {r['locale'] for r in group}!={'ar-SA','en-US'} or len({r['intent'] for r in group})!=1:raise ValueError('Trust parallel group changed')
    if Counter(r['intent'] for r in rows)!=dict.fromkeys(TOPICS,20):raise ValueError('Trust topic quotas changed')


def verify():
    p=json.loads((DEST/'provenance.json').read_text())
    if p['source_files']!=HASHES or p['positive_rows']!=0:raise ValueError('Trust source pins/coverage changed')
    for name,expected in p['files'].items():
        if hashlib.sha256((ROOT/name).read_bytes()).hexdigest()!=expected:raise ValueError('Trust frozen bytes changed')
    validate(read(DEST/'fresh.tsv'));print('Trust reservation:80 negative-only rows,40 parallel official-test groups; no fresh positives')


def reserve(source):
    for name,expected in HASHES.items():
        if hashlib.sha256((source/name).read_bytes()).hexdigest()!=expected:raise ValueError('Unpinned public source')
    data={locale:{r['id']:r for r in map(json.loads,(source/(locale+'.jsonl')).read_text().splitlines())} for locale in ('ar-SA','en-US')}
    texts,ids=blocked();rows=[]
    for intent in TOPICS:
        count=0
        for identity in sorted(data['en-US'],key=int):
            r=data['en-US'][identity]
            if identity in ids or r['intent']!=intent or r['partition']!='test':continue
            pair=[data[locale][identity] for locale in data];keys=[canonical(x['utt']) for x in pair]
            if any(x['intent']!=intent or x['partition']!='test' for x in pair):raise ValueError('Public translation mismatch')
            if len(set(keys))!=2 or any(not k or k in texts for k in keys):continue
            for locale,x in zip(data,pair):rows.append(dict(source_id=identity,source_partition='test',locale=locale,intent=intent,expected_domain='-',text=x['utt']))
            texts.update(keys);count+=1
            if count==10:break
    validate(rows);output=io.StringIO();w=csv.DictWriter(output,list(rows[0]),delimiter='\t',lineterminator='\n');w.writeheader();w.writerows(rows);(DEST/'fresh.tsv').write_text(output.getvalue())
    paths=[DEST/'fresh.tsv',DEST/'protocol.json']
    p=dict(source_files=HASHES,rows=80,groups=40,positive_rows=0,license='CC-BY-4.0; upstream LICENSE and NOTICE retained in massive-refusal',limits='Unused official-test negatives only; no fresh supported, mixed-language or Egyptian labels.',files={str(x.relative_to(ROOT)):hashlib.sha256(x.read_bytes()).hexdigest() for x in paths})
    (DEST/'provenance.json').write_text(json.dumps(p,indent=2)+'\n');verify()


if __name__=='__main__':
    if len(sys.argv)!=2:raise SystemExit('Usage: reserve_trust_eval.py --verify | PINNED_SOURCE')
    verify() if sys.argv[1]=='--verify' else reserve(pathlib.Path(sys.argv[1]))
