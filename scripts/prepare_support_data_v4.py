"""Validate reserved multilingual scenarios; data volume is not an independence claim."""
import csv
import hashlib
import json
import re
from pathlib import Path
from collections import Counter
import prepare_product_support as original
from prepare_ranked_support import normalized

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'neural-core/fixtures/support-data-v4'
SCHEMA = {'id', 'group', 'split', 'language', 'primary', 'secondary', 'query'}


def validate():
    docs, old = original.validate()
    protocol = json.loads((FIXTURES / 'protocol.json').read_text())
    path = FIXTURES / 'queries.tsv'
    if (protocol['source'] != 'knowledge/omni-product.jsonl'
            or hashlib.sha256((ROOT / protocol['source']).read_bytes()).hexdigest() != protocol['source_sha256']
            or hashlib.sha256(path.read_bytes()).hexdigest() != protocol['query_sha256']
            or any(protocol[k] is not False for k in ('production_eligible', 'independent_human_labels', 'physical_device_tested'))):
        raise ValueError('Unpinned data or unsupported readiness claim')
    with path.open() as f:
        rows = list(csv.DictReader(f, delimiter='\t'))
    for relative in ['product-support-v2/fresh.tsv', 'hybrid-support-v3/fresh.tsv']:
        with (ROOT / 'neural-core/fixtures' / relative).open() as f:
            old += list(csv.DictReader(f, delimiter='\t'))
    seen = {normalized(q['query']) for q in old} | {normalized(d['question']) for d in docs}
    identities = {q['id'] for q in old}
    topics = {d['group'] for d in docs}
    groups = {}
    if len(rows) != protocol['query_count'] or Counter(q['split'] for q in rows) != protocol['counts']:
        raise ValueError('Missing data partition')
    for q in rows:
        if (set(q) != SCHEMA or q['id'] in identities
                or q['split'] not in ('train', 'development', 'calibration', 'fresh')
                or q['language'] not in ('en', 'ar', 'mixed') or not 4 <= len(q['query']) <= 512):
            raise ValueError('Invalid scenario schema')
        primary = set() if q['primary'] == '-' else set(q['primary'].split(','))
        secondary = set() if q['secondary'] == '-' else set(q['secondary'].split(','))
        if (not primary <= topics or not secondary <= topics or primary & secondary
                or len(primary) > 2 or not primary and secondary):
            raise ValueError('Foreign or conflicting requirements')
        signature = (q['split'], q['primary'], q['secondary'])
        group = groups.setdefault(q['group'], [signature, set()])
        if signature != group[0] or q['language'] in group[1]:
            raise ValueError('Scenario/translation split leakage')
        group[1].add(q['language'])
        ar = bool(re.search(r'[\u0600-\u06ff]', q['query']))
        latin = bool(re.search('[A-Za-z]', q['query']))
        if q['language'] == 'en' and ar or q['language'] == 'ar' and not ar or q['language'] == 'mixed' and not (ar and latin):
            raise ValueError('Language label mismatch')
        key = normalized(q['query'])
        if key in seen:
            raise ValueError('Duplicate or previously observed query: ' + q['id'])
        seen.add(key)
        identities.add(q['id'])
    if any(g[1] != {'en', 'ar', 'mixed'} for g in groups.values()):
        raise ValueError('Incomplete translation group')
    for split in protocol['counts']:
        for lang in ('en', 'ar', 'mixed'):
            subset = [q for q in rows if q['split'] == split and q['language'] == lang]
            if not any(q['primary'] == '-' for q in subset) or set().union(*(set(q['primary'].split(',')) for q in subset if q['primary'] != '-')) != topics:
                raise ValueError('Missing topic/refusal language stratum')
    return rows


if __name__ == '__main__':
    print('Expanded support scenarios verified:', len(validate()))
