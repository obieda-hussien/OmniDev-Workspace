#!/usr/bin/env python3
"""Count committed Git blobs reproducibly; never inspect caches or secret files."""
from __future__ import annotations

import argparse
from collections import defaultdict
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LANGUAGES = {'.kt': 'Kotlin', '.kts': 'Kotlin build scripts', '.java': 'Java',
             '.xml': 'XML', '.py': 'Python', '.sh': 'Shell', '.mjs': 'JavaScript',
             '.js': 'JavaScript', '.md': 'Markdown', '.json': 'JSON',
             '.yml': 'YAML', '.yaml': 'YAML', '.cpp': 'C++', '.h': 'C/C++ headers',
             '.c': 'C', '.toml': 'TOML', '.properties': 'Properties'}
SOURCE = {'.kt', '.kts', '.java', '.py', '.sh', '.mjs', '.js', '.cpp', '.h', '.c'}


def git(*args: str, input: bytes | None = None) -> bytes:
    return subprocess.run(['git', *args], cwd=ROOT, input=input, check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def count(ref: str) -> dict:
    commit = git('rev-parse', '--verify', '--end-of-options', ref + '^{commit}').decode().strip()
    entries = []
    gitlinks = []
    for record in git('ls-tree', '-rz', commit).split(b'\0'):
        if not record:
            continue
        meta, path = record.split(b'\t', 1)
        mode, kind, oid = meta.decode().split()
        name = path.decode('utf-8', errors='surrogateescape')
        if kind == 'commit':
            gitlinks.append(name)
        elif kind == 'blob':
            entries.append((mode, oid, name))
    raw = git('cat-file', '--batch', input=''.join(oid + '\n' for _, oid, _ in entries).encode())
    offset = 0
    groups = defaultdict(lambda: {'files': 0, 'lines': 0, 'nonblank_lines': 0, 'bytes': 0})
    totals = {'tracked_files': len(entries), 'text_files': 0, 'binary_files': 0,
              'symlinks': 0, 'bytes': 0, 'physical_lines': 0, 'nonblank_lines': 0,
              'source_files': 0, 'source_lines': 0, 'app_kotlin_files': 0,
              'app_kotlin_lines': 0, 'test_source_files': 0, 'test_source_lines': 0}
    for mode, oid, name in entries:
        end = raw.index(b'\n', offset)
        header = raw[offset:end].decode().split()
        if header[0] != oid or header[1] != 'blob':
            raise ValueError('Unexpected Git blob response')
        size = int(header[2])
        data = raw[end + 1:end + 1 + size]
        offset = end + size + 2
        totals['bytes'] += size
        if mode == '120000':
            totals['symlinks'] += 1
            continue
        try:
            if b'\0' in data:
                raise UnicodeError('Binary data')
            data.decode('utf-8')
        except UnicodeError:
            totals['binary_files'] += 1
            continue
        lines = data.count(b'\n') + int(bool(data) and not data.endswith(b'\n'))
        nonblank = sum(bool(line.strip()) for line in data.split(b'\n'))
        totals['text_files'] += 1
        totals['physical_lines'] += lines
        totals['nonblank_lines'] += nonblank
        suffix = Path(name).suffix.lower()
        language = LANGUAGES.get(suffix, 'Other text')
        group = groups[language]
        for key, value in [('files', 1), ('lines', lines), ('nonblank_lines', nonblank), ('bytes', size)]:
            group[key] += value
        if suffix in SOURCE:
            totals['source_files'] += 1
            totals['source_lines'] += lines
        if name.startswith('app/') and suffix == '.kt':
            totals['app_kotlin_files'] += 1
            totals['app_kotlin_lines'] += lines
        if suffix in SOURCE and any(part in {'test', 'androidTest'} for part in Path(name).parts):
            totals['test_source_files'] += 1
            totals['test_source_lines'] += lines
    if offset != len(raw):
        raise ValueError('Unconsumed Git blob data')
    return {'commit': commit, 'totals': totals, 'languages': dict(sorted(groups.items())),
            'submodules_excluded': sorted(gitlinks)}


def markdown(result: dict) -> str:
    t = result['totals']
    rows = [('Tracked files', t['tracked_files']), ('UTF-8 text files', t['text_files']),
            ('Binary/non-UTF-8 files', t['binary_files']), ('Symbolic links', t['symlinks']),
            ('Total tracked blob bytes', t['bytes']), ('Physical text lines', t['physical_lines']),
            ('Nonblank text lines', t['nonblank_lines']), ('Source/script files', t['source_files']),
            ('Source/script physical lines', t['source_lines']),
            ('App Kotlin files (including tests)', t['app_kotlin_files']),
            ('App Kotlin physical lines (including tests)', t['app_kotlin_lines']),
            ('Test source files', t['test_source_files']), ('Test source physical lines', t['test_source_lines'])]
    parts = ['# Repository statistics', '', f"Snapshot commit: `{result['commit']}`.", '',
             '| Metric | Count |', '| --- | ---: |']
    parts.extend(f'| {name} | {value:,} |' for name, value in rows)
    parts.extend(['', '## Text breakdown', '', '| Format | Files | Physical lines | Nonblank lines | Bytes |',
                  '| --- | ---: | ---: | ---: | ---: |'])
    parts.extend(f"| {name} | {g['files']:,} | {g['lines']:,} | {g['nonblank_lines']:,} | {g['bytes']:,} |"
                 for name, g in result['languages'].items())
    parts.extend(['', '## Method and scope', '',
                  'Counts come from committed Git blobs at the exact snapshot above, not the working directory. '
                  'Untracked files, build output, downloaded models, caches and Git history are excluded. '
                  'Tracked binary assets are counted as files/bytes but have no line count. '
                  'Symlinks are counted separately and not followed. Git submodule contents are excluded.', '',
                  'Physical lines include comments and blank lines, with a final unterminated line counted. '
                  'Nonblank lines include comments. These are not parser-derived statements or comment-free SLOC. '
                  'Source/script totals cover Kotlin, Kotlin scripts, Java, Python, shell, JavaScript, C/C++ and headers; '
                  'XML/resources, data and documentation are reported separately. Test counts overlap source totals.', '',
                  'This report remains tied to the exact commit above; it is not a live size badge. '
                  'Rerun the command for a newer commit when an updated count is needed.', '',
                  'Reproduce this snapshot:', '', '```sh',
                  f"python3 scripts/repository_stats.py --ref {result['commit']} --format markdown", '```', '',
                  'Count the currently checked-out commit:', '', '```sh',
                  'python3 scripts/repository_stats.py --format json', '```', ''])
    if result['submodules_excluded']:
        parts.extend(['Excluded gitlinks: ' + ', '.join(f'`{p}`' for p in result['submodules_excluded']) + '.', ''])
    return '\n'.join(parts).rstrip()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ref', default='HEAD', help='Commit or ref; default HEAD')
    parser.add_argument('--format', choices=['json', 'markdown'], default='json')
    args = parser.parse_args()
    result = count(args.ref)
    print(markdown(result) if args.format == 'markdown' else json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
