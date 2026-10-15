#!/usr/bin/env python3
"""Report physical line counts for tracked repository files, excluding generated output.

Usage: python3 scripts/repo_metrics.py
Only paths returned by git ls-files are counted. A line is a byte sequence terminated
by LF or a final unterminated sequence; blank lines and comments count as lines.
"""
from collections import Counter
from pathlib import Path
import subprocess
import re

SOURCE = {'.kt', '.java', '.aidl', '.c', '.cpp', '.h', '.hpp'}
TEST_ROOT = re.compile(r'(?:test|androidTest)(?:[A-Z][A-Za-z0-9]*)?')
paths = [Path(raw.decode('utf-8', 'surrogateescape')) for raw in
         subprocess.check_output(['git', 'ls-files', '-z']).split(b'\0') if raw]
counts = Counter()
for path in paths:
    if not path.is_file():
        continue
    counts['tracked_files'] += 1
    suffix = path.suffix.lower()
    if suffix in SOURCE:
        lines = len(path.read_bytes().splitlines())
        role = 'test' if any(TEST_ROOT.fullmatch(part) for part in path.parts) else 'production'
        counts[f'{role}_source_files'] += 1
        counts[f'{role}_source_lines'] += lines
    if suffix == '.kt':
        counts['kotlin_files'] += 1
        counts['kotlin_lines'] += len(path.read_bytes().splitlines())
    if suffix == '.md':
        counts['markdown_files'] += 1
        counts['markdown_lines'] += len(path.read_bytes().splitlines())
for name in ('tracked_files', 'production_source_files', 'production_source_lines',
             'test_source_files', 'test_source_lines', 'kotlin_files', 'kotlin_lines',
             'markdown_files', 'markdown_lines'):
    print(f'{name}: {counts[name]:,}')
