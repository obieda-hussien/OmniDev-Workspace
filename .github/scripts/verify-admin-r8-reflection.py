#!/usr/bin/env python3
"""Verify classes instantiated by kotlinx.coroutines FastServiceLoader survive R8."""

from pathlib import Path
import re
import sys


REQUIRED = (
    "kotlinx.coroutines.android.AndroidDispatcherFactory",
    "kotlinx.coroutines.android.AndroidExceptionPreHandler",
)


def verify_mapping(mapping_text: str) -> None:
    lines = mapping_text.splitlines()
    for class_name in REQUIRED:
        expected = f"{class_name} -> {class_name}:"
        try:
            start = lines.index(expected)
        except ValueError as error:
            candidates = [line for line in lines if line.startswith(class_name + " -> ")]
            raise ValueError(
                f"R8 renamed or removed reflective provider {class_name}: {candidates or 'no mapping entry'}"
            ) from error

        member_lines = []
        for line in lines[start + 1:]:
            if line and not line[0].isspace() and " -> " in line and line.rstrip().endswith(":"):
                break
            member_lines.append(line)

        if not any(re.search(r"<init>\(\)\s*->\s*<init>", line) for line in member_lines):
            raise ValueError(
                f"R8 removed or renamed the public zero-argument constructor for {class_name}"
            )


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: verify-admin-r8-reflection.py <mapping.txt>", file=sys.stderr)
        return 2
    mapping = Path(sys.argv[1])
    if not mapping.is_file():
        print(f"::error::R8 mapping file not found: {mapping}", file=sys.stderr)
        return 1
    try:
        verify_mapping(mapping.read_text(encoding="utf-8", errors="replace"))
    except ValueError as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    print("Admin R8 mapping preserves both coroutine service-provider class names and public zero-argument constructors.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
