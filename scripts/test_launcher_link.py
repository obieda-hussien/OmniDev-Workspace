#!/usr/bin/env python3
"""Run focused OmniLink and launcher regressions with the project's Gradle toolchain."""

from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
TEST_CLASSES = (
    "com.omnidev.workspace.data.tools.LauncherExecutionLedgerTest",
    "com.omnidev.workspace.data.tools.OmniLinkDiscoveryTest",
    "com.omnidev.workspace.data.ipc.OmniLinkTierCapabilityPolicyTest",
)


def main() -> int:
    command = [
        "bash", str(ROOT / ".github/scripts/gradle-with-retry.sh"),
        *sys.argv[1:], ":app:testLiteDebugUnitTest",
    ]
    for name in TEST_CLASSES:
        command.extend(("--tests", name))
    return subprocess.run(command, cwd=ROOT).returncode


if __name__ == "__main__":
    sys.exit(main())
