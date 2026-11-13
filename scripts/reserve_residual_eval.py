"""Reserve unused official MASSIVE test groups before fitting the bounded residual adapter."""
import csv
import hashlib
import io
import json
import pathlib
import sys
from collections import Counter
from prepare_massive_refusal import ROOT, DEST as PRIOR, HASHES, SUPPORTED, UNSUPPORTED, UNSEEN, old_texts, canonical, read

DEST = ROOT / 'neural-core/fixtures/residual-adapter'


def blocked():
    prior = read(PRIOR / 'corpus.tsv')
    return old_texts() | {canonical(r['text']) for r in prior}, {r['source_id'] for r in prior}


def validate(rows):
    texts, ids = blocked(); seen, pairs = set(), {}
    if not rows or len(rows) > 480:
        raise ValueError('Fresh reservation bounds exceeded')
    for r in rows:
        identity, key = r['source_id'], canonical(r['text'])
        if identity in ids or not identity.isdecimal() or len(identity) > 10 or r['source_partition'] != 'test' or r['locale'] not in ('ar-SA', 'en-US'):
            raise ValueError('Fresh source identity/partition invalid')
        if key in texts or key in seen or not key or len(r['text']) > 512 or any(c in r['text'] for c in '\t\r\n'):
            raise ValueError('Fresh text contamination')
        if r['intent'] not in set(SUPPORTED) | set(UNSUPPORTED) | set(UNSEEN) or r['expected_domain'] != SUPPORTED.get(r['intent'], '-'):
            raise ValueError('Fresh mapping changed')
        seen.add(key); pairs.setdefault(identity, []).append(r)
    for pair in pairs.values():
        if len(pair) != 2 or {r['locale'] for r in pair} != {'ar-SA', 'en-US'} or len({r['intent'] for r in pair}) != 1:
            raise ValueError('Fresh translation pair changed')


def verify():
    manifest = json.loads((DEST / 'provenance.json').read_text())
    if manifest['source_files'] != HASHES:
        raise ValueError('Fresh source pins changed')
    for path, expected in manifest['files'].items():
        if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != expected:
            raise ValueError('Fresh reservation/protocol hash changed')
    rows = read(DEST / 'fresh.tsv'); validate(rows)
    if len(rows) != manifest['rows']:
        raise ValueError('Fresh count changed')
    print('Residual reservation verified:', len(rows), 'rows; counts', manifest['counts'])


def main(source):
    for name, expected in HASHES.items():
        if hashlib.sha256((source/name).read_bytes()).hexdigest() != expected:
            raise ValueError('Unpinned source bytes')
    sources = {locale: {r['id']: r for r in map(json.loads, (source/(locale+'.jsonl')).read_text().splitlines())} for locale in ('ar-SA','en-US')}
    texts, ids = blocked(); rows = []
    for intent in list(SUPPORTED) + UNSUPPORTED + UNSEEN:
        count = 0
        for identity in sorted(sources['en-US'], key=int):
            r = sources['en-US'][identity]
            if identity in ids or r['partition'] != 'test' or r['intent'] != intent:
                continue
            pair = [sources[locale][identity] for locale in ('ar-SA','en-US')]; keys = [canonical(x['utt']) for x in pair]
            if any(x['partition'] != 'test' or x['intent'] != intent for x in pair):
                raise ValueError('Upstream parallel mismatch')
            if len(set(keys)) != 2 or any(not k or k in texts for k in keys):
                continue
            for locale, x in zip(('ar-SA','en-US'), pair):
                rows.append(dict(source_id=identity, source_partition='test', locale=locale, intent=intent, expected_domain=SUPPORTED.get(intent,'-'), text=x['utt']))
            texts.update(keys); count += 1
            if count == 20:
                break
    validate(rows); DEST.mkdir(exist_ok=True)
    output = io.StringIO(); writer = csv.DictWriter(output, ['source_id','source_partition','locale','intent','expected_domain','text'], delimiter='\t',lineterminator='\n'); writer.writeheader(); writer.writerows(rows)
    (DEST/'fresh.tsv').write_text(output.getvalue())
    paths = [DEST/'fresh.tsv', DEST/'protocol.json', PRIOR/'corpus.tsv']
    manifest = dict(source_files=HASHES, rows=len(rows), counts=dict(Counter(r['intent'] for r in rows)),
                    files={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
                    limits='Previously unused official test utterances, same existing remapped topics, Saudi Arabic/English translations. Coverage is determined by remaining unique groups; not all domains or Egyptian traffic.')
    (DEST/'provenance.json').write_text(json.dumps(manifest,indent=2)+'\n');verify()


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: reserve_residual_eval.py --verify | /path/to/pinned/source')
    verify() if sys.argv[1] == '--verify' else main(pathlib.Path(sys.argv[1]))
