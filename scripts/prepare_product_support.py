"""Validate the frozen authored protocol and derive documents only from the reviewed FAQ export."""
import csv
import hashlib
import json
from pathlib import Path
import unicodedata
import re
import build_product_knowledge as knowledge
ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'neural-core/fixtures/product-support'


def validate():
    knowledge.export(verify=True)
    protocol = json.loads((FIXTURES / 'protocol.json').read_text())
    for key, path in [('source_sha256', ROOT / 'knowledge/omni-product.jsonl'),
                      ('query_sha256', FIXTURES / 'queries.tsv')]:
        if hashlib.sha256(path.read_bytes()).hexdigest() != protocol[key]:
            raise ValueError('Frozen support data changed: ' + key)
    if protocol['source'] != 'knowledge/omni-product.jsonl':
        raise ValueError('Unlisted product knowledge origin')
    documents = knowledge.records()
    topics = {d['group'] for d in documents}
    with (FIXTURES / 'queries.tsv').open() as source:
        queries = list(csv.DictReader(source, delimiter='\t'))
    if len(queries) != protocol['query_count'] or protocol['production_eligible'] is not False:
        raise ValueError('Unexpected support protocol')
    seen, groups, identities = set(), {}, set()
    for row in queries:
        if (set(row) != {'id', 'group', 'split', 'language', 'expected_topic', 'query'}
                or row['split'] not in ('train', 'development', 'calibration', 'test')
                or row['language'] not in ('en', 'ar', 'mixed')
                or row['expected_topic'] not in topics | {'-'}
                or not 4 <= len(row['query']) <= 512 or row['id'] in identities
                or (row['expected_topic'] != '-' and row['group'] != row['expected_topic'])):
            raise ValueError('Invalid support query')
        normalized = re.sub(r'[^\w]+', ' ', unicodedata.normalize('NFKC', row['query']).casefold()).strip()
        if normalized in seen:
            raise ValueError('Duplicate support question')
        seen.add(normalized); identities.add(row['id'])
        signature = row['split'], row['expected_topic']
        if row['group'] in groups and groups[row['group']] != signature:
            raise ValueError('Translation/topic split leakage')
        groups[row['group']] = signature
    for split in ('train', 'development', 'calibration', 'test'):
        for language in ('en', 'ar', 'mixed'):
            for supported in (True, False):
                if not any(q['split'] == split and q['language'] == language and (q['expected_topic'] != '-') == supported for q in queries):
                    raise ValueError('Missing support/OOD language stratum')
    return documents, queries


def export(destination):
    documents, queries = validate()
    destination = Path(destination); destination.mkdir(parents=True, exist_ok=True)
    rows = []
    for d in documents:
        text = d['question'] + '\n' + d['answer']
        if len(text) > 2048:
            raise ValueError('Runtime document bound')
        rows.append('\t'.join([d['id'], d['group'], d['language'], d['question'],
                               text.replace('\n', '\\n'), d['feature_status'], d['editions']]))
    (destination / 'documents.tsv').write_text('\n'.join(rows) + '\n', encoding='utf-8')
    print('Verified support data:', len(documents), 'documents;', len(queries), 'authored questions')


if __name__ == '__main__':
    import sys
    export(sys.argv[1])
