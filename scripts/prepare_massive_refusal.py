"""Import a bounded, parallel Arabic/English subset from the checksum-pinned MASSIVE 1.1 release."""
import csv
import hashlib
import io
import json
import pathlib
import sys
from collections import Counter
from prepare_bilingual_refusal import ROOT, canonical, read

DEST = ROOT / 'neural-core/fixtures/massive-refusal'
SOURCE = 'https://amazon-massive-nlu-dataset.s3.amazonaws.com/amazon-massive-dataset-1.1.tar.gz'
ARCHIVE_SHA256 = '4cba5faa11c71437928e17cb1b9b3d8b8e727e7ea363a3a9a8045e19c0491577'
HASHES = {
    'ar-SA.jsonl': 'b604b44d3bb94e4f71f64d041229e16ceb42d7b89385df7c82873d2514737186',
    'en-US.jsonl': 'c70f75c6a543a26e249ec383df67733ad9b1066f6c0406c2e04a3f03356e407e',
    'LICENSE': 'c2e6ea015269147de02117ebdd91f30ef09831251f5345fa8365273b1db1d435',
    'NOTICE.md': 'b90534ccd20c6f0e1e5239567af0d150496339542b75a15bfbc3e1e737593ddb',
}
SUPPORTED = {'audio_volume_up': 'device', 'audio_volume_down': 'device', 'audio_volume_mute': 'device',
             'general_greet': 'chat', 'general_joke': 'chat'}
UNSUPPORTED = ['transport_taxi', 'takeaway_order', 'play_music', 'email_sendemail', 'iot_coffee']
UNSEEN = ['calendar_set', 'weather_query']
FIELDS = ['split', 'group', 'source_partition', 'source_id', 'locale', 'intent', 'expected_domain', 'text']


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def old_texts():
    paths = ['bilingual-refusal/corpus.tsv', 'rich-text/fresh-english.tsv', 'decision-challenges.tsv']
    return {canonical(r['text']) for p in paths for r in read(ROOT / 'neural-core/fixtures' / p)}


def split_for(partition, identity):
    if partition == 'train':
        return 'train'
    if partition == 'test':
        return 'test'
    if partition != 'dev':
        raise ValueError('Unknown upstream partition')
    return 'development' if int(hashlib.sha256(identity.encode()).hexdigest()[:8], 16) % 2 == 0 else 'calibration'


def validate_rows(rows, blocked):
    if not rows or len(rows) > 5000:
        raise ValueError('Bounded subset exceeded')
    identities, texts, pairs = set(), set(), {}
    for r in rows:
        identity, locale = r['source_id'], r['locale']
        if not identity.isdecimal() or locale not in ('ar-SA', 'en-US') or (identity, locale) in identities:
            raise ValueError('Invalid or duplicate source identity')
        identities.add((identity, locale))
        if r['group'] != 'massive-' + identity or r['split'] != split_for(r['source_partition'], identity):
            raise ValueError('Parallel group or upstream partition leakage')
        if r['intent'] not in set(SUPPORTED) | set(UNSUPPORTED) | set(UNSEEN):
            raise ValueError('Unknown remapped intent')
        if r['expected_domain'] != SUPPORTED.get(r['intent'], '-'):
            raise ValueError('Changed domain mapping')
        if r['intent'] in UNSEEN and r['split'] != 'test':
            raise ValueError('Held-out topic contamination')
        key = canonical(r['text'])
        if not key or key in texts or key in blocked or any(c in r['text'] for c in '\t\r\n') or len(r['text']) > 512:
            raise ValueError('Normalized duplicate, old contamination or invalid text')
        texts.add(key)
        pair = pairs.setdefault(identity, [])
        if pair and any(r[k] != pair[0][k] for k in ('split', 'intent', 'expected_domain', 'source_partition')):
            raise ValueError('Parallel label or split mismatch')
        pair.append(r)
    if any({r['locale'] for r in pair} != {'ar-SA', 'en-US'} or len(pair) != 2 for pair in pairs.values()):
        raise ValueError('Incomplete translation pair')
    counts = Counter((r['split'], r['locale'], r['expected_domain'] == '-') for r in rows)
    if len(counts) != 16 or min(counts.values()) < 4:
        raise ValueError('Missing outcome/language split stratum')


def verify():
    provenance = json.loads((DEST / 'provenance.json').read_text())
    if provenance['source_url'] != SOURCE or provenance['archive_sha256'] != ARCHIVE_SHA256 or provenance['source_files'] != HASHES:
        raise ValueError('Source pin changed')
    for name, checksum in provenance['files'].items():
        if sha(DEST / name) != checksum:
            raise ValueError('Subset, license or protocol checksum mismatch: ' + name)
    rows = read(DEST / 'corpus.tsv')
    validate_rows(rows, old_texts())
    if len(rows) != provenance['rows']:
        raise ValueError('Row count changed')
    print(f'MASSIVE public subset verified: {len(rows)} rows, {len(rows)//2} parallel semantic groups')


def main(source):
    for name, expected in HASHES.items():
        if sha(source / name) != expected:
            raise ValueError('Unpinned source: ' + name)
    by_locale = {locale: {r['id']: r for r in (json.loads(line) for line in (source / (locale + '.jsonl')).read_text().splitlines())}
                 for locale in ('ar-SA', 'en-US')}
    if set(by_locale['ar-SA']) != set(by_locale['en-US']):
        raise ValueError('Source translations missing')
    blocked, rows, excluded = old_texts(), [], []
    for partition, limit in [('train', 80), ('dev', 32), ('test', 40)]:
        for intent in list(SUPPORTED) + UNSUPPORTED + (UNSEEN if partition == 'test' else []):
            count = 0
            for identity in sorted(by_locale['en-US'], key=int):
                english = by_locale['en-US'][identity]
                if english['partition'] != partition or english['intent'] != intent:
                    continue
                pair = [by_locale[locale][identity] for locale in ('ar-SA', 'en-US')]
                if any(p['partition'] != partition or p['intent'] != intent or p['id'] != identity for p in pair):
                    raise ValueError('Original parallel group mismatch')
                keys = [canonical(p['utt']) for p in pair]
                if len(set(keys)) != 2 or any(not key or key in blocked for key in keys):
                    excluded.append(identity)
                    continue
                for locale, p in zip(('ar-SA', 'en-US'), pair):
                    rows.append(dict(split=split_for(partition, identity), group='massive-' + identity,
                                     source_partition=partition, source_id=identity, locale=locale, intent=intent,
                                     expected_domain=SUPPORTED.get(intent, '-'), text=p['utt']))
                blocked.update(keys); count += 1
                if count == limit:
                    break
    validate_rows(rows, old_texts())
    DEST.mkdir(parents=True, exist_ok=True)
    out = io.StringIO(); writer = csv.DictWriter(out, FIELDS, delimiter='\t', lineterminator='\n')
    writer.writeheader(); writer.writerows(rows)
    (DEST / 'corpus.tsv').write_text(out.getvalue(), encoding='utf-8')
    for name in ('LICENSE', 'NOTICE.md'):
        (DEST / name).write_bytes((source / name).read_bytes())
    paths = ['corpus.tsv', 'LICENSE', 'NOTICE.md', 'protocol.json']
    provenance = dict(source_url=SOURCE, archive_sha256=ARCHIVE_SHA256, source_files=HASHES, rows=len(rows),
                      counts=dict(Counter(r['split'] for r in rows)), excluded_group_ids=excluded,
                      supported_mapping=SUPPORTED, unsupported=UNSUPPORTED, test_only_topics=UNSEEN,
                      files={p: sha(DEST / p) for p in paths},
                      changes='Bounded selection, original text/IDs, domain remapping, dev split by semantic-ID hash, whole-pair deduplication. No worker IDs, slots or judgments retained.',
                      limits='Saudi Arabic localization and English only. Two supported domains, no repository/build/knowledge coverage; not Egyptian, standard MASSIVE scores or production tool capability labels.')
    (DEST / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    verify()


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: prepare_massive_refusal.py --verify | /path/to/extracted/pinned/1.1/data-and-license')
    verify() if sys.argv[1] == '--verify' else main(pathlib.Path(sys.argv[1]))
