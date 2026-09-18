#!/usr/bin/env python3
"""Fail closed before making this repository public.

The guard checks:
1. Current tracked files for high-confidence credentials.
2. Sensitive filenames that should never be committed.
3. GitHub Actions for dangerous triggers/permissions and unpinned actions.
4. Optional full Git history, including deleted files and old added secrets.

It NEVER prints the matched secret value.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
MAX_FILE_BYTES = 5 * 1024 * 1024

ALLOW_SENSITIVE_NAMES = {
    ".env.example",
    ".env.sample",
    ".env.template",
}

BLOCKED_EXACT_NAMES = {
    ".env",
    "local.properties",
    "keystore.properties",
    "signing.properties",
    "secrets.properties",
    "credentials.json",
    "google-services.json",
}

BLOCKED_SUFFIXES = (
    ".jks",
    ".keystore",
    ".p12",
    ".pfx",
    ".pem",
    ".key",
    ".mobileprovision",
)

BLOCKED_NAME_PATTERNS = (
    re.compile(r"(?i)^service[-_]?account.*\.json$"),
    re.compile(r"(?i)^firebase-adminsdk.*\.json$"),
)

SECRET_PATTERNS = (
    ("GitHub fine-grained PAT", re.compile(r"\bgithub_pat_[A-Za-z0-9_]{40,255}\b")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,255}\b")),
    ("Anthropic API key", re.compile(r"\bsk-ant-[A-Za-z0-9_-]{32,255}\b")),
    ("OpenAI-style API key", re.compile(r"\bsk-(?!ant-)(?:proj-)?[A-Za-z0-9_-]{32,255}\b")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("Slack token", re.compile(r"\bxox[baprs]-[0-9A-Za-z-]{10,255}\b")),
    ("Stripe live secret", re.compile(r"\bsk_live_[0-9A-Za-z]{20,255}\b")),
    (
        "Private key block",
        re.compile(r"-----BEGIN (?:RSA |OPENSSH |EC |DSA |PGP )?PRIVATE KEY-----"),
    ),
)

WORKFLOW_WRITE_PERMISSION = re.compile(
    r"(?im)^\s*(?:actions|checks|contents|deployments|id-token|issues|packages|"
    r"pull-requests|repository-projects|security-events|statuses):\s*write\s*$"
)
WORKFLOW_USES = re.compile(r"(?m)^\s*-?\s*uses:\s*([^\s#]+)")
FULL_SHA_REF = re.compile(r"^[^@\s]+@[0-9a-fA-F]{40}$")


def run_git(args: list[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=ROOT,
        check=check,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def tracked_files() -> list[str]:
    result = run_git(["ls-files", "-z"])
    return [p for p in result.stdout.split("\0") if p]


def is_sensitive_path(path: str) -> bool:
    name = Path(path).name
    lower = name.lower()

    if lower in ALLOW_SENSITIVE_NAMES:
        return False
    if lower in BLOCKED_EXACT_NAMES:
        return True
    if lower.startswith(".env."):
        return True
    if lower.endswith(BLOCKED_SUFFIXES):
        return True
    return any(pattern.match(name) for pattern in BLOCKED_NAME_PATTERNS)


def should_scan_text(path: str) -> bool:
    normalized = path.replace("\\", "/")
    if normalized.startswith("app/src/main/cpp/llama.cpp/"):
        return False
    if "/build/" in f"/{normalized}/":
        return False
    return True


def line_secret_labels(line: str) -> list[str]:
    return [label for label, pattern in SECRET_PATTERNS if pattern.search(line)]


def scan_current_files() -> list[str]:
    findings: list[str] = []
    for rel in tracked_files():
        if is_sensitive_path(rel):
            findings.append(f"blocked tracked path: {rel}")

        if not should_scan_text(rel):
            continue

        path = ROOT / rel
        if not path.is_file():
            continue
        try:
            if path.stat().st_size > MAX_FILE_BYTES:
                continue
            data = path.read_bytes()
        except OSError:
            continue
        if b"\x00" in data[:8192]:
            continue

        text = data.decode("utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), start=1):
            for label in line_secret_labels(line):
                findings.append(f"{label}: {rel}:{lineno}")
    return findings


def scan_workflows() -> list[str]:
    findings: list[str] = []
    workflow_dir = ROOT / ".github" / "workflows"
    if not workflow_dir.exists():
        return ["workflow directory missing: .github/workflows"]

    workflow_files = sorted(
        p for p in workflow_dir.iterdir()
        if p.is_file() and p.suffix.lower() in {".yml", ".yaml"}
    )

    for path in workflow_files:
        rel = path.relative_to(ROOT).as_posix()
        text = path.read_text(encoding="utf-8", errors="replace")

        if "permissions:" not in text:
            findings.append(f"workflow has no explicit permissions block: {rel}")
        if re.search(r"(?im)^\s*permissions:\s*write-all\s*$", text):
            findings.append(f"workflow uses permissions: write-all: {rel}")
        if WORKFLOW_WRITE_PERMISSION.search(text):
            findings.append(f"workflow grants a write permission: {rel}")
        if re.search(r"(?im)^\s*pull_request_target\s*:", text):
            findings.append(f"workflow uses pull_request_target: {rel}")
        if re.search(r"(?im)^\s*persist-credentials\s*:\s*true\s*$", text):
            findings.append(f"workflow persists checkout credentials: {rel}")
        if "${{ secrets." in text:
            findings.append(f"workflow consumes repository secrets: {rel}")

        for match in WORKFLOW_USES.finditer(text):
            ref = match.group(1)
            if ref.startswith("./") or ref.startswith("docker://"):
                continue
            if not FULL_SHA_REF.fullmatch(ref):
                findings.append(f"workflow action is not pinned to a full commit SHA: {rel} -> {ref}")

    return findings


def scan_history_paths() -> list[str]:
    result = run_git(["log", "--all", "--name-only", "--format="])
    findings: list[str] = []
    seen: set[str] = set()
    for raw in result.stdout.splitlines():
        path = raw.strip()
        if not path or path in seen:
            continue
        seen.add(path)
        if is_sensitive_path(path):
            findings.append(f"sensitive path exists in Git history: {path}")
    return findings


def scan_history_content() -> list[str]:
    cmd = [
        "git",
        "log",
        "--all",
        "--full-history",
        "-p",
        "--no-ext-diff",
        "--no-renames",
        "--format=@@COMMIT@@ %H",
    ]
    proc = subprocess.Popen(
        cmd,
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        errors="replace",
    )
    assert proc.stdout is not None

    findings: list[str] = []
    seen: set[tuple[str, str, str]] = set()
    commit = "unknown"
    current_path: str | None = None

    for raw in proc.stdout:
        line = raw.rstrip("\n")
        if line.startswith("@@COMMIT@@ "):
            commit = line.split(" ", 1)[1].strip()
            current_path = None
            continue
        if line.startswith("diff --git "):
            current_path = None
            continue
        if line.startswith("+++ b/"):
            current_path = line[6:].strip()
            continue
        if not line.startswith("+") or line.startswith("+++"):
            continue
        if not current_path or not should_scan_text(current_path):
            continue

        added = line[1:]
        for label in line_secret_labels(added):
            key = (commit, current_path, label)
            if key in seen:
                continue
            seen.add(key)
            findings.append(f"{label} added in history: commit {commit} path {current_path}")

    stderr = proc.stderr.read() if proc.stderr is not None else ""
    return_code = proc.wait()
    if return_code != 0:
        findings.append(f"git history scan failed with exit {return_code}: {stderr.strip()[:300]}")

    return findings


def unique(items: Iterable[str]) -> list[str]:
    return list(dict.fromkeys(items))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--history",
        action="store_true",
        help="also inspect all reachable Git history for deleted/old secrets",
    )
    args = parser.parse_args()

    findings: list[str] = []
    findings.extend(scan_current_files())
    findings.extend(scan_workflows())

    if args.history:
        findings.extend(scan_history_paths())
        findings.extend(scan_history_content())

    findings = unique(findings)
    if findings:
        print("PUBLIC-REPO SECURITY GATE: FAIL")
        print("Secret values are intentionally omitted from this output.")
        for item in findings[:100]:
            print(f" - {item}")
        if len(findings) > 100:
            print(f" - ... and {len(findings) - 100} more findings")
        return 1

    mode = "current tree + workflows + full history" if args.history else "current tree + workflows"
    print(f"PUBLIC-REPO SECURITY GATE: PASS ({mode})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
