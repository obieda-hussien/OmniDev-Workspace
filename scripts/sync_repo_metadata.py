#!/usr/bin/env python3
"""Preview versioned repository metadata; apply only with explicit --apply and gh auth."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / '.github' / 'repository-metadata.json'
EXPECTED_REPOSITORY = 'obieda-hussien/OmniDev-Workspace'


def read_manifest() -> dict:
    data = json.loads(MANIFEST.read_text(encoding='utf-8'))
    if data.get('repository') != EXPECTED_REPOSITORY:
        raise ValueError('Manifest must target the expected OmniDev Workspace repository.')
    if not isinstance(data.get('title'), str) or not data['title'].strip():
        raise ValueError('A product title is required.')
    if not isinstance(data.get('description'), str) or not 1 <= len(data['description']) <= 350:
        raise ValueError('Description must contain 1–350 characters.')
    if not isinstance(data.get('homepage'), str) or not data['homepage'].startswith('https://'):
        raise ValueError('Homepage must be an HTTPS URL.')
    topics = data.get('topics')
    if not isinstance(topics, list) or not 1 <= len(topics) <= 20:
        raise ValueError('Topics must contain 1–20 entries.')
    if len(set(topics)) != len(topics) or any(
        not isinstance(t, str) or not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,49}', t) for t in topics
    ):
        raise ValueError('Topics must be unique lowercase GitHub topic names.')
    labels = data.get('labels')
    if not isinstance(labels, list) or not labels:
        raise ValueError('At least one label is required.')
    names = set()
    for label in labels:
        name = label.get('name') if isinstance(label, dict) else None
        if not isinstance(name, str) or not 1 <= len(name) <= 50 or name in names:
            raise ValueError('Label names must be unique and contain 1–50 characters.')
        if not re.fullmatch(r'[0-9A-Fa-f]{6}', label.get('color', '')):
            raise ValueError(f'Invalid color for label {name}.')
        if not isinstance(label.get('description'), str) or len(label['description']) > 100:
            raise ValueError(f'Invalid description for label {name}.')
        names.add(name)
    return data


def api(method: str, endpoint: str, payload: dict | None = None, paginate: bool = False):
    command = ['gh', 'api', '--method', method, endpoint]
    if payload is not None:
        command += ['--input', '-']
    if paginate:
        command += ['--paginate', '--slurp']
    result = subprocess.run(command, input=json.dumps(payload) if payload is not None else None,
                            text=True, capture_output=True, timeout=120, check=False)
    if result.returncode:
        raise RuntimeError(f'GitHub CLI request failed: {method} {endpoint}. Check gh authentication and repository permissions.')
    return json.loads(result.stdout) if result.stdout.strip() else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Apply settings/labels using authenticated gh; default is preview.')
    args = parser.parse_args()
    data = read_manifest()
    if not args.apply:
        print(json.dumps(data, indent=2, ensure_ascii=False))
        print('\nPreview only. Use --apply with authenticated gh to update GitHub. No settings changed.')
        return 0
    if not shutil.which('gh'):
        raise RuntimeError('GitHub CLI (gh) is required for --apply. Preview requires no authentication.')
    base = f"repos/{data['repository']}"
    repo = api('GET', base)
    if repo.get('full_name') != EXPECTED_REPOSITORY or not repo.get('permissions', {}).get('admin'):
        raise RuntimeError('Verified repository administration permission is required; no settings changed.')
    pages = api('GET', f'{base}/labels?per_page=100', paginate=True)
    existing = {label['name'] for page in pages for label in page}
    api('PATCH', base, {'description': data['description'], 'homepage': data['homepage']})
    print('Updated description and homepage.')
    api('PUT', f'{base}/topics', {'names': data['topics']})
    print('Updated complete topic set.')
    for label in data['labels']:
        if label['name'] in existing:
            endpoint = f"{base}/labels/{quote(label['name'], safe='')}"
            api('PATCH', endpoint, {'color': label['color'], 'description': label['description']})
        else:
            api('POST', f'{base}/labels', label)
        print(f"Synced label: {label['name']}")
    actual = api('GET', base)
    if actual.get('description') != data['description'] or actual.get('homepage') != data['homepage']:
        raise RuntimeError('Repository metadata read-back did not match the manifest.')
    topics = api('GET', f'{base}/topics')
    if set(topics.get('names', [])) != set(data['topics']):
        raise RuntimeError('Topic read-back did not match the manifest.')
    pages = api('GET', f'{base}/labels?per_page=100', paginate=True)
    current = {label['name']: label for page in pages for label in page}
    for label in data['labels']:
        found = current.get(label['name'], {})
        if found.get('color', '').lower() != label['color'].lower() or found.get('description') != label['description']:
            raise RuntimeError(f"Label read-back mismatch: {label['name']}")
    print('Verified repository metadata, topics and managed labels. Unrelated labels preserved.')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        print(f'Error: {error}', file=sys.stderr)
        raise SystemExit(1)
