#!/usr/bin/env python3
"""Check English entry docs, local links/anchors, issue forms and metadata. No network."""
from __future__ import annotations

from collections import Counter
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit

from sync_repo_metadata import read_manifest

ROOT = Path(__file__).resolve().parents[1]
DOCUMENTS = [
    'README.md', 'CONTRIBUTING.md', 'CONTRIBUTORS.md', 'LICENSE.md', 'SECURITY.md',
    'PRIVACY.md', 'SUPPORT.md', 'CODE_OF_CONDUCT.md', 'THIRD_PARTY_NOTICES.md',
    'docs/DEVELOPMENT.md', 'docs/REPOSITORY_MAINTENANCE.md', 'ATTRIBUTION.md',
    '.github/PULL_REQUEST_TEMPLATE.md',
]


def prose(text: str) -> str:
    return re.sub(r'^```[^\n]*\n.*?^```\s*$', '', text, flags=re.M | re.S)


def anchors(text: str) -> set[str]:
    counts = Counter()
    values = set()
    for heading in re.findall(r'^#{1,6}\s+(.+?)\s*#*$', prose(text), re.M):
        heading = re.sub(r'\[([^]]+)\]\([^)]*\)', r'\1', heading)
        heading = re.sub(r'<[^>]*>', '', heading).lower()
        slug = ''.join(c for c in heading if c.isalnum() or c in ' _-').replace(' ', '-')
        count = counts[slug]
        values.add(slug if not count else f'{slug}-{count}')
        counts[slug] += 1
    return values


def main() -> int:
    errors = []
    link_count = 0
    for name in DOCUMENTS:
        path = ROOT / name
        if not path.is_file():
            errors.append(f'{name}: missing document')
            continue
        text = path.read_text(encoding='utf-8')
        if len(re.findall(r'^```', text, re.M)) % 2:
            errors.append(f'{name}: unbalanced fenced code blocks')
        for target in re.findall(r'!?\[[^\]\n]*\]\(([^)\n]+)\)', prose(text)):
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc:
                continue
            link_count += 1
            local = (path.parent / unquote(parsed.path)).resolve() if parsed.path else path
            if not local.is_relative_to(ROOT):
                errors.append(f'{name}: link escapes repository: {target}')
            elif not local.exists():
                errors.append(f'{name}: missing local target: {target}')
            elif parsed.fragment and local.is_file() and local.suffix == '.md':
                if unquote(parsed.fragment) not in anchors(local.read_text(encoding='utf-8')):
                    errors.append(f'{name}: missing heading anchor: {target}')
    try:
        manifest = read_manifest()
    except (ValueError, OSError) as error:
        errors.append(f'Metadata: {error}')
        manifest = {'labels': []}
    try:
        import yaml
    except ImportError:
        errors.append('PyYAML is required to validate issue forms (install it in a local environment).')
    else:
        managed = {label['name'] for label in manifest['labels']}
        templates = ROOT / '.github' / 'ISSUE_TEMPLATE'
        for path in sorted(templates.glob('*.yml')):
            try:
                data = yaml.safe_load(path.read_text(encoding='utf-8'))
                if path.name == 'config.yml':
                    if not isinstance(data.get('blank_issues_enabled'), bool) or not data.get('contact_links'):
                        errors.append(f'{path.name}: invalid issue chooser configuration')
                    continue
                if not data.get('name') or not data.get('description') or not isinstance(data.get('body'), list):
                    errors.append(f'{path.name}: missing issue-form metadata/body')
                    continue
                ids = set()
                for field in data['body']:
                    if field.get('type') == 'markdown':
                        continue
                    field_id = field.get('id')
                    if not field_id or field_id in ids or not field.get('attributes', {}).get('label'):
                        errors.append(f'{path.name}: invalid/duplicate issue field')
                    ids.add(field_id)
                for label in data.get('labels', []):
                    if label not in managed:
                        errors.append(f'{path.name}: unmanaged requested label {label}')
            except (ValueError, yaml.YAMLError, AttributeError, TypeError) as error:
                errors.append(f'{path.name}: {error}')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print(f'Validated {len(DOCUMENTS)} entry documents, {link_count} local links/anchors, issue forms and repository metadata.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
