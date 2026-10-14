#!/usr/bin/env python3
"""Check physical flavor boundaries, plus an optional merged Lite manifest."""
from pathlib import Path
import argparse
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'

def check(merged=None):
    allowed = set((ROOT / 'scripts/lite-permissions.txt').read_text().splitlines())
    dependencies = (ROOT / 'app/build.gradle.kts').read_text()
    for unused in ('libs.androidx.camera', 'libs.mlkit', 'libs.retrofit', 'libs.androidx.window'):
        assert unused not in dependencies, f'Unused SDK restored: {unused}'
    main = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
    app = main.find('application')
    assert app.get(ANDROID+'allowBackup') == 'false'
    receivers = {e.get(ANDROID+'name'): e for e in app.findall('receiver')}
    assert receivers['.data.system.BootReceiver'].get(ANDROID+'exported') == 'false'
    assert receivers['.data.communication.OmniSmsReceiver'].get(ANDROID+'permission') == 'android.permission.BROADCAST_SMS'
    sensitive = {('file', 'datastore/omnidev_api_keys.preferences_pb'), ('sharedpref', 'copilot_session_secure_v1.xml')}
    backup = ET.parse(ROOT / 'app/src/main/res/xml/backup_rules.xml').getroot()
    extraction = ET.parse(ROOT / 'app/src/main/res/xml/data_extraction_rules.xml').getroot()
    for rules in (backup, extraction.find('cloud-backup'), extraction.find('device-transfer')):
        assert sensitive <= {(e.get('domain'), e.get('path')) for e in rules.findall('exclude')}
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
    accessibility = '.data.accessibility.OmniAccessibilityService'
    assert not any(e.get(ANDROID+'name') == accessibility for e in main.findall('./application/service'))
    for flavor in ('lite', 'oem'):
        manifest = ET.parse(ROOT / f'app/src/{flavor}/AndroidManifest.xml').getroot()
        assert ANDROID+'sharedUserId' not in manifest.attrib
        assert not any(e.get(ANDROID+'name') == accessibility for e in manifest.findall('./application/service'))
    for flavor in ('norm', 'pro', 'admin'):
        manifest = ET.parse(ROOT / f'app/src/{flavor}/AndroidManifest.xml').getroot()
        services = [e for e in manifest.findall('./application/service') if e.get(ANDROID+'name') == accessibility]
        assert len(services) == 1, f'Missing or duplicated Accessibility declaration in {flavor}'
        assert services[0].get(ANDROID+'permission') == 'android.permission.BIND_ACCESSIBILITY_SERVICE'
    provider = 'rikka.shizuku.ShizukuProvider'
    assert not any(e.get(ANDROID+'name') == provider for e in main.findall('./application/provider'))
    for flavor in ('lite', 'norm', 'oem'):
        manifest = ET.parse(ROOT / f'app/src/{flavor}/AndroidManifest.xml').getroot()
        assert not any(e.get(ANDROID+'name') == provider for e in manifest.findall('./application/provider'))
    for flavor in ('pro', 'admin'):
        manifest = ET.parse(ROOT / f'app/src/{flavor}/AndroidManifest.xml').getroot()
        providers = [e for e in manifest.findall('./application/provider') if e.get(ANDROID+'name') == provider]
        assert len(providers) == 1, f'Missing or duplicated Shizuku provider in {flavor}'
        assert providers[0].get(ANDROID+'permission') == 'android.permission.INTERACT_ACROSS_USERS_FULL'
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
