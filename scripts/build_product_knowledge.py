"""Export reviewed product FAQ only, without ingesting source code or private conversations."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path('docs/OMNI_PRODUCT_KNOWLEDGE.md')
OUTPUT = Path('knowledge/omni-product.jsonl')
ALLOWED = (SOURCE,)
FIELDS = ('Status', 'Editions', 'Question-en', 'Answer-en', 'Question-ar', 'Answer-ar')
ARABIC = re.compile(r'[\u0600-\u06ff\u0750-\u077f\u08a0-\u08ff]')


def records(root=ROOT, source=SOURCE):
    if source not in ALLOWED:
        raise ValueError('Only the reviewed product documentation allowlist is accepted')
    path = (root / source).resolve()
    if (not path.is_relative_to(root.resolve()) or path.suffix != '.md'
            or path.stat().st_size > 100_000):
        raise ValueError('Invalid knowledge source')
    raw = path.read_bytes()
    text = raw.decode('utf-8')
    if '```' in text:
        raise ValueError('Code fences are excluded from the support corpus')
    # This explicit translation exception does not permit Arabic authored prose elsewhere.
    english = '\n'.join(line for line in text.splitlines()
                        if not line.startswith(('Question-ar: ', 'Answer-ar: ')))
    if ARABIC.search(english):
        raise ValueError('Arabic is permitted only in reviewed translation fields')
    digest = hashlib.sha256(raw).hexdigest()
    result, seen = [], set()
    for section in text.split('\n## ')[1:]:
        identifier, *lines = section.splitlines()
        if (not identifier or len(identifier) > 80
                or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in identifier)):
            raise ValueError('Invalid topic identifier')
        values = {}
        for line in lines:
            if not line.strip():
                continue
            key, separator, value = line.partition(': ')
            if not separator or key not in FIELDS or key in values:
                raise ValueError('Invalid FAQ field')
            values[key] = value.strip()
        if (set(values) != set(FIELDS)
                or values['Status'] not in ('implemented', 'experimental', 'planned')
                or values['Editions'] not in ('all', 'advanced')):
            raise ValueError('Missing status, edition or translation')
        for language in ('en', 'ar'):
            question, answer = values['Question-' + language], values['Answer-' + language]
            if not question or not answer or len(question) > 400 or len(answer) > 4000:
                raise ValueError('Unbounded FAQ')
            identity = identifier + ':' + language
            if identity in seen:
                raise ValueError('Duplicate support record')
            seen.add(identity)
            result.append(dict(
                id=identity, group=identifier, language=language, question=question, answer=answer,
                feature_status=values['Status'], editions=values['Editions'],
                source_doc=source.as_posix(), source_heading=identifier, source_sha256=digest,
                runtime_execution_evidence=False,
            ))
    if not result or len(result) > 200:
        raise ValueError('Knowledge corpus bounds')
    return result


def export(root=ROOT, verify=False):
    payload = ''.join(json.dumps(row, ensure_ascii=False, sort_keys=True) + '\n'
                      for row in records(root))
    target = root / OUTPUT
    if verify:
        if target.read_text(encoding='utf-8') != payload:
            raise ValueError('Product knowledge export is stale')
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(payload, encoding='utf-8')
    print(f'Product documentation export verified: {len(payload.splitlines())} bilingual records; '
          'no code or user chats')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    export(verify=args.verify)
