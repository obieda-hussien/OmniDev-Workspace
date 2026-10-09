#!/usr/bin/env python3
"""Reproduce the declared local tool catalog and bundled skills without running Android."""
from __future__ import annotations
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/java/com/omnidev/workspace'
ARABIC = re.compile(r'[\u0600-\u06ff\u0750-\u077f\u08a0-\u08ff]')


def tokens(text: str):
    """Keep source offsets while skipping comments and treating strings atomically."""
    pattern = re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_][\w]*|[^\s]', re.S)
    return [m for m in pattern.finditer(text) if not m.group().startswith(('//', '/*'))]


def definitions(path: Path) -> list[str]:
    source = path.read_text()
    ts = tokens(source)
    names = []
    for i, token in enumerate(ts):
        if token.group() != 'ToolDefinition' or i + 1 >= len(ts) or ts[i + 1].group() != '(':
            continue
        depth = 0
        end = None
        for following in ts[i + 1:]:
            if following.group() == '(':
                depth += 1
            elif following.group() == ')':
                depth -= 1
                if depth == 0:
                    end = following.end()
                    break
        if end is None:
            raise ValueError(f'Unclosed ToolDefinition in {path}')
        call = source[ts[i + 1].end():end - 1]
        match = re.match(r'\s*(?:name\s*=\s*)?"([a-z][a-z0-9_]*)"', call)
        if not match:
            raise ValueError(f'Nonliteral tool name in registered provider {path}')
        if ARABIC.search(call):
            raise ValueError(f'Non-English tool metadata in {path}: {match[1]}')
        names.append(match[1])
    return names


def inventory() -> dict:
    composite = MAIN / 'data/tools/CompositeToolManager.kt'
    source = composite.read_text()
    method = source.split('private fun buildAllToolDefinitions()', 1)[1].split('override suspend fun executeTool', 1)[0]
    # Include only providers actually referenced by the catalog builder. Resolve
    # constructor fields and the few locally constructed provider properties.
    fields = dict(re.findall(r'(\w+)\s*:\s*(\w+)\??', source.split(') : ToolManager', 1)[0]))
    fields.update({
        'learnedRoutineTool': 'LearnedRoutineTool',
        'executionDiagnostics': 'OmniExecutionDiagnostics',
        'clipboardTool': 'ClipboardTool',
    })
    providers = set()
    for receiver in re.findall(r'(\w+)\.(?:getToolDefinitions|getDefinitions|getToolDefs|definitions|definition)\(', method):
        if receiver != 'it':
            providers.add(fields.get(receiver, receiver))
    for field in re.findall(r'(\w+)\?\.let\s*\{\s*addAll\(it\.(?:getDefinitions|definitions|getToolDefinitions)', method):
        if field not in fields:
            raise ValueError(f'Unresolved provider field: {field}')
        providers.add(fields[field])
    paths = {}
    for path in MAIN.rglob('*.kt'):
        text = path.read_text()
        for owner in re.findall(r'\b(?:class|object)\s+(\w+)', text):
            if owner in providers:
                paths.setdefault(owner, set()).add(path)
    missing = providers - paths.keys()
    if missing:
        raise ValueError(f'Missing registered providers: {sorted(missing)}')
    selected = {composite}
    for owner, values in paths.items():
        if len(values) != 1:
            raise ValueError(f'Ambiguous provider {owner}')
        selected.update(values)
    # Some source files host several providers; each is referenced by Composite.
    # For the inline Composite definitions, only scan the catalog builder body.
    catalog = {}
    for path in sorted(selected):
        names = definitions(path)
        for name in names:
            if name in catalog:
                raise ValueError(f'Duplicate local tool name: {name}')
            catalog[name] = str(path.relative_to(ROOT))
    gate = (MAIN / 'data/tools/TierToolGate.kt').read_text()
    hidden_body = gate.split('HIDDEN_LEGACY_TOOL_ALIASES:', 1)[1].split(')', 1)[0]
    hidden = set(re.findall(r'"([a-z][a-z0-9_]*)"', hidden_body))
    skills = sorted(path.parent.name for path in (ROOT / 'app/src/main/assets/agent-skills').glob('*/SKILL.md'))
    return {'declared_local_tools': len(catalog),
            'catalog_tools_excluding_hidden_aliases': len(catalog.keys() - hidden),
            'hidden_registered_aliases': sorted(catalog.keys() & hidden),
            'bundled_skills': len(skills), 'skills': skills,
            'tools': dict(sorted(catalog.items()))}


def markdown(data: dict) -> str:
    lines = ['# Built-in tool and skill catalog', '',
             '| Metric | Count |', '| --- | ---: |',
             f"| Unique declared local tool names | {data['declared_local_tools']} |",
             f"| Catalog names excluding hidden legacy aliases | {data['catalog_tools_excluding_hidden_aliases']} |",
             f"| Bundled skills | {data['bundled_skills']} |", '',
             '## Count scope', '',
             'This source inventory follows the providers referenced by `CompositeToolManager.buildAllToolDefinitions()`. '
             'It counts unique `ToolDefinition` names, not tool actions, source files or parameter definitions. '
             'The catalog includes optional Android/integration providers and God Mode definitions. '
             'A running session can expose fewer tools because of build flavor, initialized components, chat capability settings and God Mode. '
             'Android permissions and service credentials can further limit execution.', '',
             'Hidden registered legacy aliases: ' + ', '.join(f'`{name}`' for name in data['hidden_registered_aliases']) + '.', '',
             'MCP tools are discovered from connected servers at runtime and are excluded from these counts. '
             'Runtime helpers such as `discover_tools` and `request_execution_mode` are also excluded. '
             'The five default MCP services are server configurations, not five tool definitions. '
             'See [default MCP services](MCP_DEFAULT_SERVICES.md).', '',
             'Reproduce from the checked-out source:', '', '```sh',
             'python3 scripts/tool_catalog.py',
             'python3 scripts/tool_catalog.py --format markdown', '```', '',
             '## Bundled skills', '',
             'Only shipped `app/src/main/assets/agent-skills/*/SKILL.md` packages count here; '
             'user-installed/imported skills are additional.', '']
    lines.extend(f'- `{name}`' for name in data['skills'])
    lines.extend(['', '## Local tool definitions', '', '| Name | Source |', '| --- | --- |'])
    lines.extend(f'| `{name}` | `{path}` |' for name, path in data['tools'].items())
    return '\n'.join(lines) + '\n'


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--format', choices=['json', 'markdown'], default='json')
    args = parser.parse_args()
    data = inventory()
    print(markdown(data) if args.format == 'markdown' else json.dumps(data, indent=2),
          end='' if args.format == 'markdown' else '\n')


if __name__ == '__main__':
    main()
