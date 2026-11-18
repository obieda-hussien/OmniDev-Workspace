"""Validate the frozen selector protocol; no test row can populate its exemplar memory."""
import csv
import hashlib
import json
import re
from pathlib import Path
from prepare_ranked_support import normalized

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'neural-core/fixtures'
FRESH = FIXTURES / 'support-reliability-v6/fresh.tsv'
PROTOCOL = FRESH.with_name('protocol.json')


def read(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def validate(rows=None, protocol=None):
    rows = read(FRESH) if rows is None else rows
    protocol = json.loads(PROTOCOL.read_text()) if protocol is None else protocol
    if (protocol['version'] != 6 or protocol['fresh_count'] != 129
        or protocol['supported'] != 99 or protocol['unsupported'] != 30
        or protocol['source'] != 'knowledge/omni-product.jsonl'
        or any(protocol[key] is not False for key in (
            'production_eligible', 'independent_human_labels', 'physical_device_tested'))
        or protocol['fresh_sha256'] != hashlib.sha256(FRESH.read_bytes()).hexdigest()
        or protocol['source_sha256'] != hashlib.sha256((ROOT / protocol['source']).read_bytes()).hexdigest()):
        raise ValueError('Selector protocol/source mismatch')
    docs = [json.loads(line) for line in (ROOT / protocol['source']).read_text().splitlines()]
    topics = {d['group'] for d in docs}
    previous = []
    for path in ('product-support/queries.tsv', 'product-support-v2/fresh.tsv',
                 'hybrid-support-v3/fresh.tsv', 'support-data-v4/queries.tsv', 'query-memory-v5/fresh.tsv'):
        previous += read(FIXTURES / path)
    seen = {normalized(r['query']) for r in previous} | {normalized(d['question']) for d in docs}
    ids = {r['id'] for r in previous}
    groups = {}
    if len(rows) != 129:
        raise ValueError('Missing selector challenge rows')
    for row in rows:
        if (set(row) != {'id', 'group', 'language', 'primary', 'secondary', 'query'}
            or not re.fullmatch(r'reliability6-\d+-(en|ar|mixed)', row['id'])
            or row['id'] in ids or row['language'] not in ('en', 'ar', 'mixed')
            or row['secondary'] != '-' or not 4 <= len(row['query']) <= 512
            or any(c in row['query'] for c in '\t\r\n')):
            raise ValueError('Invalid selector row')
        primary = set() if row['primary'] == '-' else set(row['primary'].split(','))
        if not primary <= topics or len(primary) > 2:
            raise ValueError('Unknown selector topic')
        signature = tuple(sorted(primary))
        group = groups.setdefault(row['group'], [signature, set()])
        if group[0] != signature or row['language'] in group[1]:
            raise ValueError('Selector translation conflict')
        group[1].add(row['language'])
        ar = bool(re.search(r'[\u0600-\u06ff]', row['query']))
        latin = bool(re.search('[A-Za-z]', row['query']))
        if (row['language'] == 'en' and ar or row['language'] == 'ar' and not ar
            or row['language'] == 'mixed' and not (ar and latin)):
            raise ValueError('Selector language mismatch')
        key = normalized(row['query'])
        if key in seen:
            raise ValueError('Selector challenge duplicates observed data')
        seen.add(key)
        ids.add(row['id'])
    if len(groups) != 43 or any(g[1] != {'en', 'ar', 'mixed'} for g in groups.values()):
        raise ValueError('Incomplete selector groups')
    for lang in ('en', 'ar', 'mixed'):
        part = [r for r in rows if r['language'] == lang]
        if (len(part) != 43 or sum(r['primary'] == '-' for r in part) != 10
            or sum(',' in r['primary'] for r in part) != 4
            or set().union(*(set(r['primary'].split(',')) for r in part if r['primary'] != '-')) != topics):
            raise ValueError('Selector language/topic coverage missing')
    calibration = [r for r in read(FIXTURES / 'support-data-v4/queries.tsv') if r['split'] == 'calibration']
    names = sorted({r['group'] for r in calibration}, key=lambda g: hashlib.sha256(g.encode()).hexdigest())
    if len(names) != 33:
        raise ValueError('Selector calibration group count')
    temperature = {r['id'] for r in calibration if r['group'] in set(names[:16])}
    decision = {r['id'] for r in calibration if r['group'] in set(names[16:])}
    if len(temperature) != 48 or len(decision) != 51 or temperature & decision:
        raise ValueError('Selector calibration overlap')
    return rows, sorted(temperature), sorted(decision)


if __name__ == '__main__':
    rows, temperature, decision = validate()
    print(f'Selector reservation verified: {len(rows)} authored rows; disjoint CAL48/CAL51')
