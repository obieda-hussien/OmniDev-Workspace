#!/usr/bin/env python3
"""Report Android CI results and fail when required stages did not pass."""
import json
import os
from pathlib import Path

REQUIRED = ("run-quality", "lint", "tests", "ui-instrumentation", "release-apk")
OPTIONAL = ("admin-release-telegram",)


def unsuccessful_stages(needs, allow_skipped_pipeline=False):
    results = {key: needs.get(key, {}).get("result", "missing") for key in REQUIRED + OPTIONAL}
    if allow_skipped_pipeline and all(value == "skipped" for value in results.values()):
        return []
    return [key for key, value in results.items()
            if value not in (("success", "skipped") if key in OPTIONAL else ("success",))]


def main():
    needs = json.loads(os.environ["PIPELINE_NEEDS_JSON"])
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
        summary.write("# Android CI\n\n| Stage | Result |\n|---|---|\n")
        for key in REQUIRED + OPTIONAL:
            summary.write(f"| {key} | {needs.get(key, {}).get('result', 'missing')} |\n")
    failed = unsuccessful_stages(needs, os.environ.get("ALLOW_SKIPPED_PIPELINE") == "true")
    if failed:
        print("::error::Android CI did not pass: " + ", ".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
