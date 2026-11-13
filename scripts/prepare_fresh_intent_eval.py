"""Reserve unused official English test utterances without accessing any model scores."""
import csv
import hashlib
import io
import json
import pathlib
import subprocess
import sys
from prepare_bilingual_refusal import ROOT, REVISION, SOURCE_HASH, MAPPING, SUPPORTED_DOMAIN, UNSEEN, canonical, read

DEST = ROOT / 'neural-core/fixtures/rich-text'
CORPUS = ROOT / 'neural-core/fixtures/bilingual-refusal/corpus.tsv'
CHALLENGES = ROOT / 'neural-core/fixtures/decision-challenges.tsv'
PROTOCOL = ROOT / 'neural-core/fixtures/bilingual-refusal/rich-text-protocol.json'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify():
    manifest = json.loads((DEST / 'provenance.json').read_text())
    if manifest['revision'] != REVISION or manifest['source_sha256'] != SOURCE_HASH:
        raise ValueError('Unexpected public source')
    for path, checksum in manifest['files'].items():
        if sha(ROOT / path) != checksum:
            raise ValueError(f'Evaluation provenance changed: {path}')
    rows = read(DEST / 'fresh-english.tsv')
    blocked = {canonical(r['text']) for r in read(CORPUS) + read(CHALLENGES)}
    validate_rows(rows, blocked)
    if len(rows) != manifest['rows']:
        raise ValueError('Fresh row count changed')
    print(f'Fresh English evaluation verified: {len(rows)} untouched upstream test utterances')


def validate_rows(rows, blocked):
    seen, ids = set(), set()
    for row in rows:
        key = canonical(row['text'])
        if key in blocked or key in seen or row['id'] in ids or not row['id'].startswith('clinc-test-'):
            raise ValueError('Fresh evaluation contamination')
        if row['intent'] not in set(MAPPING) | set(UNSEEN) or row['expected_domain'] != SUPPORTED_DOMAIN.get(row['intent'], '-'):
            raise ValueError('Unexpected label remapping')
        seen.add(key)
        ids.add(row['id'])


def main(source):
    if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != REVISION:
        raise ValueError('Wrong source revision')
    raw = subprocess.check_output(['git', '-C', str(source), 'show', f'{REVISION}:data/data_full.json'])
    if hashlib.sha256(raw).hexdigest() != SOURCE_HASH:
        raise ValueError('Wrong source bytes')
    data = json.loads(raw)
    blocked = {canonical(r['text']) for r in read(CORPUS) + read(CHALLENGES)}
    rows, excluded, counts = [], [], {}
    for intent in list(MAPPING) + UNSEEN:
        matches = [(i, text) for i, (text, label) in enumerate(data['test']) if label == intent]
        for index, text in matches[20:30]:
            group = f'clinc-test-{index}'
            key = canonical(text)
            if key in blocked:
                excluded.append(group)
                continue
            if any(c in text for c in '\t\r\n'):
                raise ValueError('Invalid utterance')
            blocked.add(key)
            rows.append(dict(id=group, intent=intent, expected_domain=SUPPORTED_DOMAIN.get(intent, '-'), text=text))
            counts[intent] = counts.get(intent, 0) + 1
        if counts.get(intent, 0) < 5:
            raise ValueError('Insufficient untouched test examples')
    DEST.mkdir(parents=True, exist_ok=True)
    output = io.StringIO()
    writer = csv.DictWriter(output, ['id', 'intent', 'expected_domain', 'text'], delimiter='\t', lineterminator='\n')
    writer.writeheader(); writer.writerows(rows)
    (DEST / 'fresh-english.tsv').write_text(output.getvalue(), encoding='utf-8')
    paths = [CORPUS, CHALLENGES, PROTOCOL, DEST / 'fresh-english.tsv']
    manifest = dict(revision=REVISION, source_sha256=SOURCE_HASH, rows=len(rows), counts=counts, excluded_ids=excluded,
                    files={str(path.relative_to(ROOT)): sha(path) for path in paths},
                    note='New utterances from existing remapped topics; English only, not the standard CLINC benchmark. Read model scores only after selection/export.')
    (DEST / 'provenance.json').write_text(json.dumps(manifest, indent=2) + '\n')
    verify()


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: prepare_fresh_intent_eval.py --verify | /path/to/pinned/oos-eval')
    if sys.argv[1] == '--verify':
        verify()
    else:
        main(pathlib.Path(sys.argv[1]))
