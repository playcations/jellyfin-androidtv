#!/usr/bin/env python3
"""Same native renderer against the disposable real bridge. Configure private auth first."""
from emulator_smoke import expect, key, checks, ARTIFACTS, strings
import json
expect('01-real-three-provider-catalog', 'Library sampler')
key('KEYCODE_DPAD_RIGHT')
key('KEYCODE_DPAD_CENTER')
expect('02-real-categories', 'Sports')
key('KEYCODE_DPAD_CENTER')
expect('03-real-sports-category', 'Guide: Sports')
key('KEYCODE_DPAD_CENTER')
expect('04-real-native-scoped-guide', 'Fixture One')
key('KEYCODE_BACK')
root = expect('05-real-one-back-to-category', 'Guide: Sports')
focused = [n for n in root.iter() if n.get('focused') == 'true']
assert any('guide: sports' in strings(n) for n in focused), 'Real bridge Back focus was not restored'
checks.append('06-real-back-restored-focus')
(ARTIFACTS / 'checks.json').write_text(json.dumps(checks, indent=2))
print('PASS 06-real-back-restored-focus', flush=True)
