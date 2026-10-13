#!/usr/bin/env python3
"""Check physical flavor boundaries, plus an optional merged Lite manifest."""
from pathlib import Path
import argparse
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
TOOLS = '{http://schemas.android.com/tools}'

def check(merged=None):
    allowed = set((ROOT / 'scripts/lite-permissions.txt').read_text().splitlines())
    main = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
    actual = {e.get(ANDROID+'name') for e in main.findall('uses-permission')}
    assert actual <= allowed, f'Unexpected shared permissions: {actual - allowed}'
    lite = ROOT / 'app/src/lite/java/com/omnidev/workspace'
    assert not (ROOT / 'app/src/main/java/com/omnidev/workspace/domain/engine/AgentPipeline.kt').exists()
    assert not (ROOT / 'app/src/main/java/com/omnidev/workspace/domain/engine/SwarmOrchestrator.kt').exists()
    for path in lite.rglob('*.kt'):
        text = path.read_text()
        assert 'import rikka.' not in text and 'org.eclipse.jgit' not in text, path
        assert not re.search(r':\s*AccessibilityService\s*\(', text), path
        assert 'ProcessBuilder(' not in text and 'Runtime.getRuntime().exec(' not in text, path
    for flavor in ('lite', 'oem'):
        manifest = ET.parse(ROOT / f'app/src/{flavor}/AndroidManifest.xml').getroot()
        assert ANDROID+'sharedUserId' not in manifest.attrib
        removed = {e.get(ANDROID+'name') for e in manifest.findall('./application/service') if e.get(TOOLS+'node') == 'remove'}
        assert '.data.accessibility.OmniAccessibilityService' in removed
    oem_facade = (ROOT / 'app/src/oem/java/com/omnidev/workspace/core/privileged/PrivilegedExecutionFacadeBootstrap.kt').read_text()
    assert 'Runtime.getRuntime().exec(' not in oem_facade
    broker = (ROOT / 'oem-system/src/main/java/com/omnidev/system/OmniSystemService.kt').read_text()
    assert 'enforceCallingPermission' in broker and 'checkSignatures' in broker
    if merged:
        final = ET.parse(merged).getroot()
        permissions = {e.get(ANDROID+'name') for e in final.findall('uses-permission')}
        assert permissions <= allowed, f'Unexpected merged Lite permissions: {permissions - allowed}'
        components = {e.get(ANDROID+'name', '') for e in final.findall('./application/*')}
        assert not any('OmniAccessibilityService' in name or 'ShizukuProvider' in name for name in components)
    print('Flavor source boundaries verified' + ('; merged Lite manifest verified' if merged else ''))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--lite-manifest', type=Path)
    args = parser.parse_args()
    check(args.lite_manifest)
