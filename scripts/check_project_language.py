#!/usr/bin/env python3
"""Reject Arabic authored prose while preserving reviewed multilingual input literals."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re
import subprocess
from tool_catalog import ARABIC, inventory

ROOT = Path(__file__).resolve().parents[1]
TOKEN = re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[^\s]', re.S)


def functional_literals(source: str) -> list[str]:
    result = []
    for token in TOKEN.finditer(source):
        value = token.group()
        if not ARABIC.search(value):
            continue
        if value.startswith(('//', '/*')) or not value.startswith(('"', "'")):
            raise ValueError('Arabic prose/comment outside a reviewed input literal')
        result.append(value)
    return sorted(set(result))


def fingerprint(literals: list[str]) -> str:
    return hashlib.sha256('\n'.join(literals).encode()).hexdigest()


def main() -> None:
    reviewed = json.loads((ROOT / 'scripts/multilingual_input_literals.json').read_text())
    paths = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT).decode().split('\0')
    failures = []
    seen = set()
    for name in sorted(set(paths) - {''}):
        path = ROOT / name
        if not path.is_file():
            continue
        if path.suffix in {'.md', '.xml', '.toml', '.kts', '.pro'}:
            if ARABIC.search(path.read_text()):
                failures.append(f'{name}: authored prose/resources must be English')
        elif path.suffix == '.kt' and '/src/main/' in name:
            try:
                literals = functional_literals(path.read_text())
            except ValueError as error:
                failures.append(f'{name}: {error}')
                continue
            if literals:
                seen.add(name)
                if name not in reviewed or fingerprint(literals) != reviewed[name]['sha256']:
                    failures.append(f'{name}: unexpected non-English literal; review input matching versus display prose')
    for name in reviewed.keys() - seen:
        failures.append(f'{name}: reviewed multilingual literals changed or disappeared')
    data = inventory()  # Also checks English tool/parameter metadata in registered providers.
    expected = __import__('tool_catalog').markdown(data).strip()
    if (ROOT / 'docs/TOOL_AND_SKILL_CATALOG.md').read_text().strip() != expected:
        failures.append('Regenerate docs/TOOL_AND_SKILL_CATALOG.md with scripts/tool_catalog.py --format markdown')
    if failures:
        raise SystemExit('\n'.join(failures))
    print(f"English prose/resources/metadata validated; preserved input literals in {len(seen)} files, "
          f"{data['declared_local_tools']} local definitions and {data['bundled_skills']} bundled skills.")


if __name__ == '__main__':
    main()
