#!/usr/bin/env python3
"""Turn a Gradle log into GitHub annotations and a step summary.

Job logs and artifacts are served from blob storage, which automation and sandboxed reviewers often
cannot reach. Check-run annotations are served by the normal API, so the first compile errors and
failing tests are republished there. This never changes the build result: it always exits 0.
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

MAX_ANNOTATIONS = 10  # GitHub shows at most 10 annotations per step.
MAX_SUMMARY_ITEMS = 40

ANSI = re.compile(r'\x1b\[[0-9;]*[A-Za-z]')
KOTLIN_ERROR = re.compile(r'^e: (?:file://)?(?P<file>/\S+?\.(?:kt|kts|java)):(?P<line>\d+):(?P<col>\d+):? (?P<msg>.*)$')
KOTLIN_ERROR_BARE = re.compile(r'^e: (?P<msg>\S.*)$')
TEST_FAILED = re.compile(r'^(?P<name>\S.* > .+) FAILED$')
TASK_FAILED = re.compile(r'^> Task (?P<task>:\S+) FAILED$')
WENT_WRONG = re.compile(r'^\* What went wrong:$')


def clean(line: str) -> str:
    return ANSI.sub('', line).rstrip()


def parse(text: str) -> list[dict]:
    lines = [clean(line) for line in text.splitlines()]
    findings: list[dict] = []
    seen: set[tuple] = set()

    def add(kind: str, message: str, file: str | None = None, line: int | None = None, col: int | None = None) -> None:
        key = (kind, file, line, message)
        if key in seen:
            return
        seen.add(key)
        findings.append({'kind': kind, 'message': message, 'file': file, 'line': line, 'col': col})

    workspace = os.environ.get('GITHUB_WORKSPACE', '').rstrip('/') + '/'
    for index, line in enumerate(lines):
        match = KOTLIN_ERROR.match(line)
        if match:
            path = match.group('file')
            if workspace != '/' and path.startswith(workspace):
                path = path[len(workspace):]
            add('compile', match.group('msg'), path, int(match.group('line')), int(match.group('col')))
            continue
        match = KOTLIN_ERROR_BARE.match(line)
        if match:
            add('compile', match.group('msg'))
            continue
        match = TEST_FAILED.match(line)
        if match:
            detail = next((lines[j].strip() for j in range(index + 1, min(index + 4, len(lines))) if lines[j].strip()), '')
            add('test', f"{match.group('name')} FAILED" + (f' - {detail}' if detail else ''))
            continue
        match = TASK_FAILED.match(line)
        if match:
            add('task', f"Task {match.group('task')} FAILED")
            continue
        if WENT_WRONG.match(line):
            detail = next((lines[j].strip() for j in range(index + 1, min(index + 4, len(lines))) if lines[j].strip()), '')
            if detail:
                add('gradle', detail)
    return findings


def escape_data(value: str) -> str:
    return value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def escape_property(value: str) -> str:
    return escape_data(value).replace(':', '%3A').replace(',', '%2C')


def annotation(finding: dict) -> str:
    props = []
    if finding['file']:
        props.append(f"file={escape_property(finding['file'])}")
        if finding['line']:
            props.append(f"line={finding['line']}")
        if finding['col']:
            props.append(f"col={finding['col']}")
    title = {'compile': 'Kotlin compile error', 'test': 'Unit test failed', 'task': 'Gradle task failed', 'gradle': 'Gradle'}[finding['kind']]
    props.append(f'title={escape_property(title)}')
    return f"::error {','.join(props)}::{escape_data(finding['message'][:900])}"


def order(findings: list[dict]) -> list[dict]:
    rank = {'compile': 0, 'test': 1, 'task': 2, 'gradle': 3}
    return sorted(findings, key=lambda f: rank[f['kind']])


def summary(findings: list[dict]) -> str:
    if not findings:
        return '### Gradle failure digest\n\nNo compile errors or failed tests were recognized in the log.\n'
    out = ['### Gradle failure digest', '']
    for finding in order(findings)[:MAX_SUMMARY_ITEMS]:
        where = f" `{finding['file']}:{finding['line']}`" if finding['file'] else ''
        out.append(f"- **{finding['kind']}**{where}: {finding['message'][:300]}")
    if len(findings) > MAX_SUMMARY_ITEMS:
        out.append(f'- ... and {len(findings) - MAX_SUMMARY_ITEMS} more')
    return '\n'.join(out) + '\n'


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print('usage: ci_failure_annotations.py <gradle-log>', file=sys.stderr)
        return 0
    path = Path(argv[1])
    if not path.is_file():
        print(f'::warning::Gradle log not found: {path}')
        return 0
    findings = parse(path.read_text(encoding='utf-8', errors='replace'))
    for finding in order(findings)[:MAX_ANNOTATIONS]:
        print(annotation(finding))
    summary_path = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary_path:
        with open(summary_path, 'a', encoding='utf-8') as handle:
            handle.write(summary(findings))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
