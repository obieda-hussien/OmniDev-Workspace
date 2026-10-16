#!/usr/bin/env python3
"""Execute focused OmniLink discovery and launcher host-policy JVM regressions."""
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('OMNI_TEST_CACHE', str(Path(tempfile.gettempdir()) / 'omni-link-jvm')))
CACHE.mkdir(parents=True, exist_ok=True)
ARTIFACTS = {
    'ktlint.jar': 'com/pinterest/ktlint/ktlint-cli/1.8.0/ktlint-cli-1.8.0-all.jar',
    'junit.jar': 'junit/junit/4.13.2/junit-4.13.2.jar',
    'hamcrest.jar': 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar',
    'json.jar': 'org/json/json/20240303/json-20240303.jar',
}
for name, artifact in ARTIFACTS.items():
    target = CACHE / name
    if not target.exists():
        with urllib.request.urlopen('https://repo.maven.apache.org/maven2/' + artifact) as response:
            target.write_bytes(response.read())
classpath = os.pathsep.join(str(CACHE / name) for name in ARTIFACTS)
sources = [
    'app/src/main/java/com/omnidev/workspace/data/tools/OmniLinkActionSemantics.kt',
    'app/src/main/java/com/omnidev/workspace/domain/engine/LauncherExecutionLedger.kt',
    'app/src/test/java/com/omnidev/workspace/data/tools/LauncherExecutionLedgerTest.kt',
    'app/src/main/java/com/omnidev/workspace/data/tools/OmniLinkDiscovery.kt',
    'app/src/main/java/com/omnidev/workspace/data/ipc/OmniLinkTierCapabilityPolicy.kt',
    'app/src/test/java/com/omnidev/workspace/data/tools/OmniLinkDiscoveryTest.kt',
    'app/src/test/java/com/omnidev/workspace/data/ipc/OmniLinkTierCapabilityPolicyTest.kt',
]
with tempfile.TemporaryDirectory(prefix='omni-link-tests-') as output:
    subprocess.run(['java', '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', output] + sources, cwd=ROOT, check=True)
    subprocess.run(['java', '-cp', output + os.pathsep + classpath, 'org.junit.runner.JUnitCore',
        'com.omnidev.workspace.data.tools.LauncherExecutionLedgerTest',
        'com.omnidev.workspace.data.tools.OmniLinkDiscoveryTest',
        'com.omnidev.workspace.data.ipc.OmniLinkTierCapabilityPolicyTest'], cwd=ROOT, check=True)
