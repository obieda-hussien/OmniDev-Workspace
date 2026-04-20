#!/usr/bin/env python3
"""
secret_hunter.py — Fast static scanner for leaked secrets, hard-coded
credentials, and insecure patterns across the OmniDev-Workspace source tree.

Usage:
    python3 scripts/secret_hunter.py                  # pretty text output
    python3 scripts/secret_hunter.py --json           # machine-readable JSON
    python3 scripts/secret_hunter.py --src app/src    # custom source root
    python3 scripts/secret_hunter.py --fail-on HIGH   # exit 1 on HIGH+

Exit codes:
    0 → scan finished, no findings at/above the fail-on threshold
    1 → findings detected at/above the fail-on threshold
    2 → bad invocation (missing dir, etc.)

The scanner is intentionally dependency-free so it can run inside CI and on
developer laptops without a virtualenv.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

# ── Severity ordering (used for sorting + --fail-on threshold) ───────────────
SEVERITY_RANK = {"INFO": 0, "LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}

# ── Detection rules: (severity, name, regex) ─────────────────────────────────
# Rules are intentionally conservative; prefer false-negatives over noise.
RULES: list[tuple[str, str, str]] = [
    # --- High-confidence provider tokens ----------------------------------
    ("CRITICAL", "AWS Access Key",         r"AKIA[0-9A-Z]{16}"),
    ("CRITICAL", "GCP Service Account",    r"\"type\"\s*:\s*\"service_account\""),
    ("CRITICAL", "Google API Key",         r"AIza[0-9A-Za-z_\-]{35}"),
    ("CRITICAL", "Slack Token",            r"xox[abpr]-[0-9A-Za-z\-]{10,48}"),
    ("CRITICAL", "Stripe Secret Key",      r"sk_live_[0-9A-Za-z]{24,}"),
    ("CRITICAL", "GitHub PAT",             r"ghp_[0-9A-Za-z]{36}"),
    ("CRITICAL", "GitHub Fine-grained",    r"github_pat_[0-9A-Za-z_]{22,}"),
    ("CRITICAL", "OpenAI Key",             r"sk-[A-Za-z0-9]{20,}"),
    ("CRITICAL", "Anthropic Key",          r"sk-ant-[A-Za-z0-9\-_]{20,}"),
    ("HIGH",     "Private Key Block",      r"-----BEGIN (RSA|OPENSSH|EC|DSA|PGP) PRIVATE KEY-----"),
    ("HIGH",     "JWT-like",               r"eyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"),

    # --- Generic hard-coded secrets (value context) -----------------------
    ("HIGH",     "Hard-coded password",    r"(?i)password\s*=\s*[\"'][^\"'\s]{6,}[\"']"),
    ("HIGH",     "Hard-coded api_key",     r"(?i)(api[_-]?key|apikey)\s*=\s*[\"'][A-Za-z0-9_\-]{16,}[\"']"),
    ("HIGH",     "Hard-coded secret",      r"(?i)(secret|token)\s*=\s*[\"'][A-Za-z0-9_\-]{16,}[\"']"),
    ("MEDIUM",   "Basic Auth URL",         r"https?://[^\s:]+:[^\s@]+@[\w.-]+"),

    # --- Android / Kotlin-specific smells ---------------------------------
    ("MEDIUM",   "Exported component",     r"android:exported\s*=\s*\"true\""),
    ("MEDIUM",   "allowBackup=true",       r"android:allowBackup\s*=\s*\"true\""),
    ("MEDIUM",   "cleartextTraffic=true",  r"android:usesCleartextTraffic\s*=\s*\"true\""),
    ("MEDIUM",   "Debuggable=true",        r"android:debuggable\s*=\s*\"true\""),
    ("LOW",      "TODO/FIXME security",    r"(?i)//\s*(TODO|FIXME|HACK)[^\n]{0,80}(security|auth|password|token)"),
    ("LOW",      "println leak",           r"println\([^)]*(token|password|secret|apikey|api_key)", ),

    # --- Gradle / properties ----------------------------------------------
    ("HIGH",     "Gradle plaintext pwd",   r"(?i)(signing|store|key)Password\s*=\s*[^\s].+"),
]

DEFAULT_EXTS = ('.java', '.kt', '.xml', '.json', '.yaml', '.yml', '.properties', '.gradle', '.kts')
DEFAULT_IGNORE_DIRS = {'.git', 'build', '.gradle', 'node_modules', '.idea', 'captures', '.externalNativeBuild'}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Scan source tree for leaked secrets & insecure patterns.")
    p.add_argument("--src", default="app/src",
                   help="Source root to scan (default: app/src)")
    p.add_argument("--json", dest="json_out", action="store_true",
                   help="Emit JSON instead of pretty text")
    p.add_argument("--fail-on", default="NONE",
                   choices=["NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL"],
                   help="Exit with status 1 if any finding has this severity or higher")
    p.add_argument("--ext", action="append", default=None,
                   help="Restrict to these extensions (repeatable). Default: common code/config.")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    src_dir = os.path.abspath(args.src)
    if not os.path.isdir(src_dir):
        print(f"error: source dir not found: {src_dir}", file=sys.stderr)
        return 2

    exts = tuple(args.ext) if args.ext else DEFAULT_EXTS
    compiled = [(sev, name, re.compile(pat)) for sev, name, pat in RULES]

    findings: list[dict] = []
    scanned = 0

    for root, dirs, files in os.walk(src_dir):
        # prune ignored dirs in-place for speed
        dirs[:] = [d for d in dirs if d not in DEFAULT_IGNORE_DIRS]
        for fname in files:
            if not fname.endswith(exts):
                continue
            fpath = os.path.join(root, fname)
            try:
                with open(fpath, encoding='utf-8', errors='replace') as fh:
                    text = fh.read()
                scanned += 1
                for severity, name, pat in compiled:
                    for m in pat.finditer(text):
                        line_no = text[:m.start()].count('\n') + 1
                        findings.append({
                            "severity": severity,
                            "rule": name,
                            "file": os.path.relpath(fpath, src_dir),
                            "line": line_no,
                            "match": m.group(0)[:100],
                        })
            except Exception:
                # Swallow unreadable files; the scan is best-effort.
                pass

    if args.json_out:
        print(json.dumps({"files_scanned": scanned, "findings": findings}, indent=2))
    else:
        print(f"Scanned {scanned} files | {len(findings)} findings")
        # Sort highest severity first, then by file/line
        findings_sorted = sorted(
            findings,
            key=lambda x: (-SEVERITY_RANK.get(x['severity'], 0), x['file'], x['line']),
        )
        for f in findings_sorted:
            print(f"[{f['severity']}] {f['rule']} @ {f['file']}:{f['line']} → {f['match']}")

    # Gate exit code on --fail-on threshold
    if args.fail_on != "NONE":
        threshold = SEVERITY_RANK[args.fail_on]
        hit = any(SEVERITY_RANK.get(f['severity'], 0) >= threshold for f in findings)
        if hit:
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
