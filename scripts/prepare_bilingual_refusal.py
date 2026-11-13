"""Pinned public English data plus explicitly authored/generated bilingual experiments. No network."""
import csv
import hashlib
import io
import json
import pathlib
import subprocess
import sys
import unicodedata
from collections import Counter

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEST = ROOT / 'neural-core/fixtures/bilingual-refusal'
REVISION = '828f8093932c8fe6ca7936c3d2e52903b1c523de'
SOURCE_HASH = '36923c3705a59e08fe9c3883d8bc2dd966ef93e22cb78ac41171782a698d56e0'
MAPPING = {'greeting': 'supported', 'thank_you': 'supported', 'tell_joke': 'supported',
           'change_volume': 'supported', 'book_flight': 'refuse', 'book_hotel': 'refuse',
           'restaurant_reservation': 'refuse', 'transfer': 'refuse'}
UNSEEN = ['weather', 'calendar', 'calculator', 'shopping_list']
SUPPORTED_DOMAIN = {'greeting': 'chat', 'thank_you': 'chat', 'tell_joke': 'chat', 'change_volume': 'device'}
HEADER = ['split', 'group', 'outcome', 'expected_domain', 'language', 'kind', 'parent_a', 'parent_b', 'text']
INPUTS = ['neural-core/fixtures/text-intents.tsv', 'neural-core/fixtures/clinc-subset/rejection.tsv',
          'neural-core/fixtures/decision-challenges.tsv', 'neural-core/fixtures/bilingual-refusal/authored.tsv',
          'neural-core/fixtures/bilingual-refusal/protocol.json']


def canonical(text):
    text = unicodedata.normalize('NFKC', text).lower()
    text = ''.join(c for c in text if c != '\u0640' and not '\u064b' <= c <= '\u065f' and c != '\u0670')
    text = text.translate(str.maketrans('\u0622\u0623\u0625\u0649', '\u0627\u0627\u0627\u064a'))
    return ' '.join(''.join(c if c.isalnum() or c == '_' else ' ' for c in text).split())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    with path.open(encoding='utf-8') as source:
        return list(csv.DictReader(source, delimiter='\t'))


def verify():
    manifest = json.loads((DEST / 'provenance.json').read_text())
    if manifest['revision'] != REVISION or manifest['source_sha256'] != SOURCE_HASH or manifest['mapping'] != MAPPING or manifest['unseen_test_intents'] != UNSEEN:
        raise ValueError('Public source contract changed')
    for name, checksum in manifest['files'].items():
        if digest(ROOT / name) != checksum:
            raise ValueError(f'Fixture provenance mismatch: {name}')
    rows = read(DEST / 'corpus.tsv')
    if dict(Counter(r['split'] for r in rows)) != manifest['split_counts']:
        raise ValueError('Split counts changed')
    validate(rows)
    print(f'Bilingual refusal provenance/splits verified: {len(rows)} observations')


def validate(rows):
    groups, texts = {}, set()
    for r in rows:
        if r['split'] not in {'train', 'development', 'calibration', 'test', 'regression'} or r['language'] not in {'ar', 'en', 'mixed'} or r['outcome'] not in {'supported', 'refuse'}:
            raise ValueError('Invalid row contract')
        if len(r['text']) > 2048 or not canonical(r['text']):
            raise ValueError('Empty/oversized row')
        if (r['outcome'] == 'supported') != (r['expected_domain'] in {'repository', 'build', 'device', 'knowledge', 'chat'}):
            raise ValueError('Expected domain mismatch')
        if r['outcome'] == 'refuse' and r['expected_domain'] != '-':
            raise ValueError('Refusal carries a domain')
        contract = (r['split'], r['outcome'])
        if groups.setdefault(r['group'], contract) != contract:
            raise ValueError('Group leakage/conflicting labels')
        key = canonical(r['text'])
        if key in texts:
            raise ValueError('Normalized duplicate')
        texts.add(key)
        if r['group'].startswith('clinc-'):
            upstream = r['group'].split('-')[1]
            if (upstream == 'test' and r['split'] not in {'test', 'regression'}) or (upstream == 'val' and r['split'] not in {'development', 'calibration'}) or (upstream == 'train' and r['split'] != 'train'):
                raise ValueError('Upstream partition leakage')
        if r['kind'].startswith('composed_'):
            for parent in [r['parent_a'], r['parent_b']]:
                if groups.get(parent, (None,))[0] != r['split']:
                    raise ValueError('Composition crosses split boundary or missing parent')
        if r['kind'] == 'unseen_topic' and (r['split'] != 'test' or not r['group'].split('-')[1] == 'test'):
            raise ValueError('Unseen topic entered fitting/selection')


def prepare(source):
    revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != REVISION:
        raise ValueError('Unexpected source revision')
    raw = subprocess.check_output(['git', '-C', str(source), 'show', f'{REVISION}:data/data_full.json'])
    if hashlib.sha256(raw).hexdigest() != SOURCE_HASH:
        raise ValueError('Source bytes differ')
    upstream = json.loads(raw)
    rows, seen, excluded = [], set(), []
    def add(split, group, outcome, language, kind, text, a='-', b='-', domain='-'):
        key = canonical(text)
        if key in seen:
            excluded.append(group)
            return
        if any(c in text for c in '\t\r\n'):
            raise ValueError('Multiline input')
        seen.add(key)
        rows.append(dict(zip(HEADER, [split, group, outcome, domain, language, kind, a, b, text])))
    original = read(ROOT / INPUTS[0])
    for r in original:
        add('regression' if r['split'] == 'holdout' else r['split'], 'original:' + r['group'],
            'supported', r['language'], 'original', r['text'], domain=r['label'])
    for r in read(ROOT / INPUTS[1]):
        add('regression' if r['split'] == 'test' else r['split'], r['id'],
            'refuse' if r['label'] == '__reject__' else 'supported', 'en', 'prior_clinc', r['text'],
            domain='-' if r['label'] == '__reject__' else r['label'])
    # Exclude every old challenge, even though it is evaluated separately rather than trained.
    reserved = {canonical(r['text']) for r in read(ROOT / INPUTS[2])}
    for partition, target, start, limit in [('train', 'train', 25, 25), ('val', 'development', 10, 5),
                                             ('val', 'calibration', 15, 5), ('test', 'test', 10, 10)]:
        for intent, outcome in MAPPING.items():
            matches = [(i, text) for i, (text, label) in enumerate(upstream[partition]) if label == intent]
            added = 0
            for position, (index, text) in enumerate(matches[start:], start):
                if partition == 'val' and position >= start + limit:
                    break
                group = f'clinc-{partition}-{index}'
                if canonical(text) in seen | reserved:
                    excluded.append(group)
                    continue
                add(target, group, outcome, 'en', 'additional_clinc', text, domain=SUPPORTED_DOMAIN.get(intent, '-'))
                added += 1
                if added == limit:
                    break
            if added == 0:
                raise ValueError('No eligible upstream rows')
    for intent in UNSEEN:
        selected = 0
        for index, (text, label) in enumerate(upstream['test']):
            if label == intent and canonical(text) not in seen | reserved:
                add('test', f'clinc-test-{index}', 'refuse', 'en', 'unseen_topic', text)
                selected += 1
                if selected == 10:
                    break
        if selected != 10:
            raise ValueError('Incomplete unseen category')
    for r in read(DEST / 'authored.tsv'):
        for language in ['en', 'ar', 'mixed']:
            add(r['split'], 'authored:' + r['group'], 'refuse', language, 'authored_' + r['kind'], r[language])
    # Composition only joins parents from the same pre-existing split and language.
    connectors = {'en': ' ; and then ', 'ar': ' ؛ وبعد كده ', 'mixed': ' وبعدها '}
    for split in ['train', 'development', 'calibration', 'holdout']:
        for language in ['en', 'ar', 'mixed']:
            pool = [r for r in original if r['split'] == split and r['language'] == language]
            for i, left in enumerate(pool):
                for label in ['repository', 'build', 'device', 'knowledge', 'chat']:
                    candidates = [r for r in pool if r['label'] == label]
                    right = candidates[(i + 1) % len(candidates)]
                    same = left['label'] == label
                    # Repetition is intentional when a split has only one scenario per domain.
                    outcome = 'supported' if same else 'refuse'
                    group = 'compose:' + left['group'] + ':' + right['group']
                    add('regression' if split == 'holdout' else split, group, outcome, language,
                        'composed_same' if same else 'composed_cross', left['text'] + connectors[language] + right['text'],
                        'original:' + left['group'], 'original:' + right['group'], domain=left['label'] if same else '-')
    validate(rows)
    output = io.StringIO(); writer = csv.DictWriter(output, HEADER, delimiter='\t', lineterminator='\n')
    writer.writeheader(); writer.writerows(rows)
    (DEST / 'corpus.tsv').write_text(output.getvalue(), encoding='utf-8')
    files = {name: digest(ROOT / name) for name in INPUTS + ['neural-core/fixtures/bilingual-refusal/corpus.tsv']}
    manifest = dict(revision=REVISION, source_sha256=SOURCE_HASH, mapping=MAPPING, unseen_test_intents=UNSEEN,
                    files=files, split_counts=dict(Counter(r['split'] for r in rows)), excluded_ids=excluded,
                    note='English public utterances plus authored/synthetic bilingual fixtures; not a standard benchmark or human-reviewed Arabic evaluation.')
    (DEST / 'provenance.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    verify()


if __name__ == '__main__':
    if len(sys.argv) == 2 and sys.argv[1] == '--verify':
        verify()
    elif len(sys.argv) == 2:
        prepare(pathlib.Path(sys.argv[1]))
    else:
        raise SystemExit('Usage: prepare_bilingual_refusal.py --verify | /path/to/pinned/oos-eval')
