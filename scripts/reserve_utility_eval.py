"""Reserve unused public positives and negatives before fitting utility-aware experts."""
import csv
import hashlib
import io
import json
import pathlib
import sys
from collections import Counter
from prepare_massive_refusal import ROOT, HASHES, SUPPORTED, UNSUPPORTED, UNSEEN, canonical, read
from reserve_residual_eval import blocked as old_blocked, DEST as RESIDUAL

DEST = ROOT / 'neural-core/fixtures/utility-expert'


def blocked():
    texts, ids = old_blocked()
    for r in read(RESIDUAL/'fresh.tsv'):
        texts.add(canonical(r['text'])); ids.add(r['source_id'])
    return texts, ids


def validate(rows):
    texts, ids = blocked(); seen, pairs = set(), {}
    if not rows or len(rows) > 480:
        raise ValueError('Utility reservation bounds')
    for r in rows:
        identity, text = r['source_id'], r['text']; key = canonical(text)
        if not identity.isdecimal() or len(identity) > 10 or identity in ids or r['locale'] not in ('ar-SA','en-US'):
            raise ValueError('Utility source identity contamination')
        if key in texts or key in seen or not key or len(text) > 512 or any(c in text for c in '\t\r\n'):
            raise ValueError('Utility text contamination')
        supported = r['intent'] in SUPPORTED
        if r['intent'] not in set(SUPPORTED)|set(UNSUPPORTED)|set(UNSEEN) or r['expected_domain'] != SUPPORTED.get(r['intent'],'-'):
            raise ValueError('Utility mapping changed')
        if r['source_partition'] != ('train' if supported else 'test'):
            raise ValueError('Utility upstream partition changed')
        seen.add(key); pairs.setdefault(identity,[]).append(r)
    for pair in pairs.values():
        if len(pair)!=2 or {r['locale'] for r in pair}!={'ar-SA','en-US'} or len({r['intent'] for r in pair})!=1:
            raise ValueError('Utility parallel group mismatch')


def verify():
    manifest=json.loads((DEST/'provenance.json').read_text())
    if manifest['source_files'] != HASHES:
        raise ValueError('Utility source pin changed')
    for name, expected in manifest['files'].items():
        if hashlib.sha256((ROOT/name).read_bytes()).hexdigest()!=expected:
            raise ValueError('Utility reserved bytes changed')
    rows=read(DEST/'fresh.tsv'); validate(rows)
    if len(rows)!=manifest['rows']:
        raise ValueError('Utility reservation count changed')
    print('Utility reservation verified:',len(rows),'rows;',manifest['counts'])


def main(source):
    for name, expected in HASHES.items():
        if hashlib.sha256((source/name).read_bytes()).hexdigest()!=expected:
            raise ValueError('Unpinned utility source')
    data={locale:{r['id']:r for r in map(json.loads,(source/(locale+'.jsonl')).read_text().splitlines())} for locale in ['ar-SA','en-US']}
    texts, ids=blocked();rows=[]
    for intent in list(SUPPORTED)+['play_music','email_sendemail','calendar_set','weather_query']:
        partition='train' if intent in SUPPORTED else 'test';limit=20 if partition=='train' else 10;count=0
        for identity in sorted(data['en-US'],key=int):
            r=data['en-US'][identity]
            if identity in ids or r['intent']!=intent or r['partition']!=partition:
                continue
            pair=[data[locale][identity] for locale in ['ar-SA','en-US']];keys=[canonical(x['utt']) for x in pair]
            if any(x['intent']!=intent or x['partition']!=partition for x in pair):
                raise ValueError('Utility original pair mismatch')
            if len(set(keys))!=2 or any(not key or key in texts for key in keys):
                continue
            for locale,x in zip(['ar-SA','en-US'],pair):rows.append(dict(source_id=identity,source_partition=partition,locale=locale,intent=intent,expected_domain=SUPPORTED.get(intent,'-'),text=x['utt']))
            texts.update(keys);count+=1
            if count==limit:break
    validate(rows);DEST.mkdir(exist_ok=True)
    out=io.StringIO();writer=csv.DictWriter(out,['source_id','source_partition','locale','intent','expected_domain','text'],delimiter='\t',lineterminator='\n');writer.writeheader();writer.writerows(rows)
    (DEST/'fresh.tsv').write_text(out.getvalue())
    paths=[DEST/'fresh.tsv',DEST/'protocol.json']
    manifest=dict(rows=len(rows),source_files=HASHES,counts=dict(Counter(r['intent'] for r in rows)),files={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
                  limits='Supported positives withheld from unused upstream TRAIN groups, not official test; unsupported rows from unused official test groups. All are untouched by our fits, with no old source-ID/text overlap. Positive coverage is volume up/mute only, Saudi Arabic and English, not all domains or Egyptian/production validation.')
    (DEST/'provenance.json').write_text(json.dumps(manifest,indent=2)+'\n');verify()


if __name__=='__main__':
    if len(sys.argv)!=2:raise SystemExit('Usage: reserve_utility_eval.py --verify | /path/to/pinned/source')
    verify() if sys.argv[1]=='--verify' else main(pathlib.Path(sys.argv[1]))
