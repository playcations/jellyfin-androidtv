#!/usr/bin/env python3
"""Copy a disposable fixture session into the debuggable emulator app without printing secrets."""
import argparse
import json
import uuid
import subprocess
import urllib.request
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('session_file', type=Path)
parser.add_argument('--serial', default='emulator-5582')
parser.add_argument('--adb-port', default='5043')
args = parser.parse_args()
c = json.loads(args.session_file.read_text())
auth = 'MediaBrowser Client="Familyfin Native", Device="Android TV", DeviceId="familyfin-native-extensions", Version="0.1.0", Token="' + c['token'] + '"'
req = urllib.request.Request(c['server'] + '/Users/Me', headers={'Authorization': auth})
with urllib.request.urlopen(req) as response:
    user = json.load(response)['Id']
config = dict(server=c['server'].replace('127.0.0.1', '10.0.2.2'), userId=str(uuid.UUID(user)), token=c['token'])
command = ['/home/mrpeyvan/Android/Sdk/platform-tools/adb', '-P', args.adb_port, '-s', args.serial, 'shell', 'run-as', 'com.playcations.familyfin.nativeclient.debug', 'sh', '-c', "'mkdir -p files; cat > files/fixture-session.json; chmod 600 files/fixture-session.json'"]
subprocess.run(command, input=json.dumps(config).encode(), check=True, stdout=subprocess.DEVNULL)
print('Private synthetic session installed into emulator app storage.')
