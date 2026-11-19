"""Export source tool descriptions and argument metadata for offline training."""
import argparse
import importlib.util
import json
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SKIPPED = {"android_security_research", "enhanced_attack_surface", "security_analyzer", "install_tool"}


def calls(source, name, tokens):
    ts = tokens(source)
    for index, token in enumerate(ts[:-1]):
        opening = ts[index + 1]
        if token.group() != name or opening.group() != "(":
            continue
        depth = 1
        for following in ts[index + 2:]:
            depth += (following.group() == "(") - (following.group() == ")")
            if depth == 0:
                yield source[opening.end():following.start()]
                break


def arguments(source, positional, tokens):
    depth = start = 0
    parts = []
    for token in tokens(source):
        value = token.group()
        if value in ("(", "[", "{"):
            depth += 1
        elif value in (")", "]", "}"):
            depth -= 1
        elif value == "," and depth == 0:
            parts.append(source[start:token.start()].strip())
            start = token.end()
    parts.append(source[start:].strip())
    result = {}
    for index, part in enumerate(parts):
        field = re.fullmatch(r"(\w+)\s*=\s*(.*)", part, re.S)
        if field:
            result[field[1]] = field[2]
        elif part and index < len(positional):
            result[positional[index]] = part
    return result


def text_literals(expr, tokens):
    pieces = []
    for token in tokens(expr):
        s = token.group()
        if s.startswith('"""'):
            pieces.append(s[3:-3])
        elif s.startswith('"'):
            pieces.append(s[1:-1].replace(r'\n', " ").replace(r'\"', '"').replace(r'\$', "$"))
    text = " ".join("".join(pieces).split())
    text = text.replace("${'$'}", "$")
    return re.sub(r"\$\{[^}]+\}|\$[A-Z][A-Z0-9_]+", "(runtime value)", text)


def export(root, inventory, tokens):
    cards = {}
    per_file = {}
    for name, location in sorted(inventory["tools"].items()):
        if name in SKIPPED or name in inventory["hidden_registered_aliases"]:
            continue
        if location not in per_file:
            per_file[location] = {}
            source = (root / location).read_text(encoding="utf-8")
            for call in calls(source, "ToolDefinition", tokens):
                a = arguments(call, ("name", "description", "parameters"), tokens)
                per_file[location][text_literals(a.get("name", ""), tokens)] = a
        definition = per_file[location][name]
        long = text_literals(definition["description"], tokens)
        if not long:
            raise ValueError(f"Missing description for {name}")
        short = re.split(r"(?<=[.!?])\s+", long, maxsplit=1)[0]
        parameters = []
        for call in calls(definition.get("parameters", ""), "ToolParameter", tokens):
            a = arguments(call, ("name", "type", "description", "required"), tokens)
            p = {k: text_literals(a[k], tokens) for k in ("name", "type", "description")}
            p["required"] = a.get("required") == "true"
            p["required_for_actions"] = [text_literals(t.group(), tokens)
                                         for t in tokens(a.get("requiredForActions", ""))
                                         if t.group().startswith('"')]
            parameters.append(p)
        cards[name] = {"source": location, "short_description": short,
                       "long_description": long, "parameters": parameters}
    if len(cards) + len(set(inventory["tools"]) & SKIPPED) != inventory["catalog_tools_excluding_hidden_aliases"]:
        raise ValueError("Training catalog coverage does not reconcile with the source inventory")
    return cards


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--output", type=Path, default=HERE / "catalog-cards.json")
    options = parser.parse_args()
    spec = importlib.util.spec_from_file_location("tool_catalog", options.root / "scripts/tool_catalog.py")
    catalog = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(catalog)
    inventory = catalog.inventory()
    output = {"source_revision": options.source_revision,
              "declared_tools": inventory["declared_local_tools"],
              "hidden_aliases": inventory["hidden_registered_aliases"],
              "excluded_tools": sorted(set(inventory["tools"]) & SKIPPED),
              "cards": export(options.root, inventory, catalog.tokens)}
    options.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
