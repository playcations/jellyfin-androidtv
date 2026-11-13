#!/usr/bin/env python3
"""D-pad-only smoke on the explicit local native emulator; stores XML/screenshots and assertions."""
import argparse
import json
import subprocess
import time
from pathlib import Path
import xml.etree.ElementTree as ET
ADB = '/home/mrpeyvan/Android/Sdk/platform-tools/adb'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5582')
parser.add_argument('--adb-port', default='5043')
parser.add_argument('--artifacts', default='/opt/jellyfin/prototypes/artifacts/native/emulator-ui')
args = parser.parse_args()
SERIAL, PORT = args.serial, args.adb_port
assert SERIAL.startswith('emulator-')
ARTIFACTS = Path(args.artifacts)
ARTIFACTS.mkdir(parents=True, exist_ok=True)
checks = []
(ARTIFACTS / 'checks.json').write_text('[]\n')
def adb(*args, timeout=90, binary=False):
    return subprocess.check_output([ADB, '-P', PORT, '-s', SERIAL, *args], timeout=timeout, text=not binary)
def key(name):
    adb('shell', 'input', 'keyevent', name)
def snapshot(name):
    # uiautomator can exit successfully with "null root" and leave an older dump behind.
    # Never let a previous screen/run satisfy the current assertion.
    adb('shell', 'rm', '-f', '/sdcard/familyfin-native-ui.xml')
    adb('shell', 'uiautomator', 'dump', '/sdcard/familyfin-native-ui.xml')
    data = adb('shell', 'cat', '/sdcard/familyfin-native-ui.xml')
    (ARTIFACTS / (name + '.xml')).write_text(data)
    (ARTIFACTS / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    return ET.fromstring(data)
def strings(root):
    return ' | '.join(n.get('text', '') for n in root.iter()).lower()
def expect(name, text):
    last = None
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            last = snapshot(name)
            if text.lower() in strings(last):
                checks.append(name)
                print('PASS', name, flush=True)
                (ARTIFACTS / 'checks.json').write_text(json.dumps(checks, indent=2))
                return last
        except (subprocess.SubprocessError, ET.ParseError):
            pass
        time.sleep(3)
    raise AssertionError(name + ': expected fixture text ' + text)
if __name__ == '__main__':
    expect('01-three-providers', 'Community')
    key('KEYCODE_DPAD_RIGHT')
    key('KEYCODE_DPAD_CENTER')
    expect('02-television-categories', 'Sports')
    key('KEYCODE_DPAD_CENTER')
    expect('03-selected-sports-category', 'Football guide')
    key('KEYCODE_DPAD_CENTER')
    expect('04-scoped-guide', 'Sports One')
    key('KEYCODE_BACK')
    root = expect('05-one-back-to-sports', 'Football guide')
    focused = [n for n in root.iter() if n.get('focused') == 'true']
    assert any('football guide' in strings(n) for n in focused), 'Back did not restore selected category'
    checks.append('06-back-restored-category-focus')
    key('KEYCODE_BACK')
    expect('07-parent-category', 'Sports')
    key('KEYCODE_BACK')
    expect('08-catalog-restored', 'Community')
    key('KEYCODE_DPAD_RIGHT')
    key('KEYCODE_DPAD_CENTER')
    expect('09-independent-third-provider', 'Community events')
    (ARTIFACTS / 'checks.json').write_text(json.dumps(checks, indent=2))
