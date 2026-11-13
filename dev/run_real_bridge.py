#!/usr/bin/env python3
# Native-owned runtime wrapper of the server integration fixture; parent server sources stay read-only.
"""Build artifacts first, then run a disposable Jellyfin with synthetic data only.
Never inspects or modifies any existing container. Credentials exist only in memory.
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2] / 'server'
ARTIFACTS = ROOT.parent / 'artifacts/native/real-bridge'
FIXTURES = ROOT.parent / 'native/.emulator/real-fixtures'
AUTH = 'MediaBrowser Client="Extension Fixture", Device="Synthetic", DeviceId="client-extensions-fixture", Version="1.0.0"'

def shell(*args):
    return subprocess.check_output(args, text=True).strip()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--image', default='jellyfin/jellyfin:12.2')
    parser.add_argument('--hold', action='store_true', help='Keep the synthetic server alive for client/emulator testing until interrupted')
    args = parser.parse_args()
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    runtime = Path(tempfile.mkdtemp(prefix='isolated-', dir=ARTIFACTS))
    name = 'client-extensions-fixture-' + secrets.token_hex(5)
    evidence = []
    completed = False
    version = None
    def passed(label):
        evidence.append(label)
        print('PASS ' + label, flush=True)
    try:
        configs = runtime / 'config/plugins/configurations'
        configs.mkdir(parents=True)
        for project, folder in [('Jellyfin.Plugin.ClientExtensions', 'ClientExtensions'), ('Jellyfin.Plugin.ForYou', 'ForYou')]:
            target = runtime / ('config/plugins/' + folder)
            target.mkdir()
            shutil.copy2(ROOT / project / 'bin/Release/net10.0' / (project + '.dll'), target)
        target = runtime / 'config/plugins/LiveTvGroups'
        target.mkdir()
        shutil.copy2(ROOT / 'integration/bin/Release/net10.0/Jellyfin.Plugin.LiveTvGroups.dll', target)
        (configs / 'Jellyfin.Plugin.ClientExtensions.xml').write_text('<PluginConfiguration><EnableLibrarySampler>true</EnableLibrarySampler><DisabledProviderIds /></PluginConfiguration>')
        (configs / 'Jellyfin.Plugin.LiveTvGroups.xml').write_text('<PluginConfiguration><EnableNestedGroups>true</EnableNestedGroups></PluginConfiguration>')
        media = runtime / 'media'
        media.mkdir()
        # Local NFOs avoid metadata-network requirements. A tiny synthetic MP4 is copied to three movie directories.
        sample = media / 'sample.mp4'
        shell('docker', 'run', '--rm', '--network', 'none', '--entrypoint', '/usr/lib/jellyfin-ffmpeg/ffmpeg', '-v', str(media) + ':/fixture', args.image,
              '-loglevel', 'error', '-f', 'lavfi', '-i', 'color=c=blue:s=320x180:d=1', '-c:v', 'libx264', '-y', '/fixture/sample.mp4')
        for index in range(1, 4):
            folder = media / ('Fixture Movie ' + str(index))
            folder.mkdir()
            shutil.copy2(sample, folder / ('Fixture Movie ' + str(index) + '.mp4'))
            (folder / 'movie.nfo').write_text('<movie><title>Fixture Movie ' + str(index) + '</title><year>2026</year><plot>Synthetic local test media</plot><genre>Fixture</genre><rating>8</rating></movie>')
        sample.unlink()
        # Non-routable synthetic playback URLs: guide tests never start playback.
        (media / 'channels.m3u').write_text('#EXTM3U\n#EXTINF:-1 tvg-id="fixture-one" tvg-chno="1",Fixture One\nhttp://127.0.0.1:1/one.ts\n#EXTINF:-1 tvg-id="fixture-two" tvg-chno="2",Fixture Two\nhttp://127.0.0.1:1/two.ts\n#EXTINF:-1 tvg-id="fixture-three" tvg-chno="3",Fixture Three\nhttp://127.0.0.1:1/three.ts\n')
        now = datetime.now(timezone.utc)
        stamp = lambda value: value.strftime('%Y%m%d%H%M%S +0000')
        guide = '<tv>'
        for index, label in enumerate(['one', 'two', 'three'], 1):
            guide += f'<channel id="fixture-{label}"><display-name>Fixture {label.title()}</display-name></channel>'
            guide += f'<programme channel="fixture-{label}" start="{stamp(now - timedelta(hours=1))}" stop="{stamp(now + timedelta(hours=2))}"><title>Fixture Program {index}</title><desc>Synthetic guide data</desc></programme>'
        (media / 'guide.xml').write_text(guide + '</tv>')
        shell('docker', 'run', '-d', '--name', name, '-p', '127.0.0.1::8096',
              '-e', 'JELLYFIN_PublishedServerUrl=http://127.0.0.1', '-v', str(runtime / 'config') + ':/config', '-v', str(media) + ':/fixture:ro', args.image)
        mapping = shell('docker', 'port', name, '8096/tcp')
        base = 'http://' + mapping
        def request(method, path, body=None, token=None, expected=200):
            headers = {'Authorization': AUTH, 'Content-Type': 'application/json'}
            if token:
                headers['Authorization'] = AUTH + ', Token="' + token + '"'
            data = None if body is None else json.dumps(body).encode()
            req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
            try:
                with urllib.request.urlopen(req, timeout=40) as response:
                    code, content = response.status, response.read()
            except urllib.error.HTTPError as error:
                code, content = error.code, error.read()
            if code != expected:
                # Do not echo response bodies from auth/config routes.
                raise AssertionError(f'{method} {path.split("?")[0]}: expected {expected}, got {code}')
            return json.loads(content) if content and content[:1] in (b'{', b'[') else None
        for attempt in range(120):
            try:
                request('GET', '/Startup/User')
                break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError('Synthetic container did not become ready')
        version = request('GET', '/System/Info/Public')['Version']
        password = secrets.token_urlsafe(24)
        request('GET', '/Startup/User')
        request('POST', '/Startup/User', {'Name': 'Fixture Admin', 'Password': password}, expected=204)
        request('POST', '/Startup/Complete', expected=204)
        def login(username, pw):
            return request('POST', '/Users/AuthenticateByName', {'Username': username, 'Pw': pw})
        admin_session = login('Fixture Admin', password)
        admin = admin_session['AccessToken']
        admin_id = admin_session['User']['Id']
        request('GET', '/ClientExtensions/v1', expected=401)
        passed('real Jellyfin session authentication: unauthenticated discovery is 401')
        request('GET', '/Users/Me', token=admin)
        passed('real Jellyfin user token is accepted by core /Users/Me')
        initial = request('GET', '/ClientExtensions/v1', token=admin)
        assert {e['id'] for e in initial['extensions']} == {'for-you', 'live-tv', 'library-sampler'}, initial
        passed('three independently registered providers loaded in Jellyfin 12.2')
        assert not next(e for e in initial['extensions'] if e['id'] == 'live-tv')['homeRows']
        request('POST', '/Library/VirtualFolders?name=Fixture%20Movies&collectionType=movies&paths=%2Ffixture&refreshLibrary=true',
                {'LibraryOptions': {'EnableInternetProviders': False, 'EnableRealtimeMonitor': False, 'TypeOptions': [{'Type': 'Movie', 'MetadataFetchers': [], 'ImageFetchers': []}]}}, admin, 204)
        for attempt in range(120):
            items = request('GET', '/Items?Recursive=true&IncludeItemTypes=Movie', token=admin)['Items']
            if len(items) >= 3:
                break
            time.sleep(1)
        assert len(items) >= 3
        discovery = request('GET', '/ClientExtensions/v1', token=admin)
        rows = next(e for e in discovery['extensions'] if e['id'] == 'for-you')['homeRows']
        # Discovery before media indexing may have populated the upstream cache; invalidate through configuration update.
        foryou_guid = '5f0c3f0e-4b8e-4e3a-9a5f-3c1d2b7a9e01'
        config = request('GET', '/Plugins/' + foryou_guid + '/Configuration', token=admin)
        config['MinItemsPerSection'] = 1
        request('POST', '/Plugins/' + foryou_guid + '/Configuration', config, admin, 204)
        discovery = request('GET', '/ClientExtensions/v1', token=admin)
        rows = next(e for e in discovery['extensions'] if e['id'] == 'for-you')['homeRows']
        pages = [request('GET', '/ClientExtensions/v1/extensions/for-you/screens/' + row['screenId'] + '?limit=1', token=admin) for row in rows]
        nonempty = next((p for p in pages if p['items']), None)
        assert nonempty and 'Name' in nonempty['items'][0]['media'] and 'name' not in nonempty['items'][0]['media']
        passed('real For You sections and BaseItemDto media page with canonical envelope casing')
        page = request('GET', '/ClientExtensions/v1/extensions/for-you/screens/' + nonempty['id'] + '?startIndex=1&limit=1', token=admin)
        assert page['startIndex'] == 1 and len(page['items']) <= 1
        passed('real For You pagination')
        request('POST', '/LiveTv/TunerHosts', {'Type': 'm3u', 'Url': '/fixture/channels.m3u', 'FriendlyName': 'Synthetic fixture'}, admin)
        request('POST', '/LiveTv/ListingProviders', {'Type': 'xmltv', 'Path': '/fixture/guide.xml', 'EnableAllTuners': True}, admin)
        tasks = request('GET', '/ScheduledTasks', token=admin)
        refresh = next(t for t in tasks if t['Key'] == 'RefreshGuide')
        request('POST', '/ScheduledTasks/Running/' + refresh['Id'], token=admin, expected=204)
        for attempt in range(120):
            channels = request('GET', '/LiveTv/Channels', token=admin)['Items']
            if len(channels) >= 3:
                break
            time.sleep(1)
        assert len(channels) >= 3
        def group(name):
            return request('POST', '/LiveTvGroups/Groups', {'Name': name}, admin)['Id']
        root = group('Sports')
        child = group('Sports / Local')
        other = group('News')
        request('PUT', '/LiveTvGroups/Groups/' + child + '/Channels', [channels[0]['Id']], admin, 204)
        request('PUT', '/LiveTvGroups/Groups/' + other + '/Channels', [channels[1]['Id']], admin, 204)
        request('PUT', '/LiveTvGroups/Administration', {'Mode': 'shared', 'ImportPersonalGroups': True}, admin, 204)
        gid = lambda id: 'group-' + id.replace('-', '')
        root_screen = request('GET', '/ClientExtensions/v1/extensions/live-tv/screens/root', token=admin)
        assert len(root_screen['items']) == 2, root_screen
        nested_screen = request('GET', '/ClientExtensions/v1/extensions/live-tv/screens/' + gid(root), token=admin)
        assert any(i['action'].get('screenId') == gid(child) for i in nested_screen['items'])
        child_screen = request('GET', '/ClientExtensions/v1/extensions/live-tv/screens/' + gid(child), token=admin)
        scope = request('GET', '/ClientExtensions/v1/extensions/live-tv/guide-scopes/' + gid(root), token=admin)
        normalize = lambda id: id.replace('-', '').lower()
        assert {normalize(id) for id in scope['channelIds']} == {normalize(channels[0]['Id'])}
        assert normalize(channels[1]['Id']) not in {normalize(id) for id in scope['channelIds']}
        passed('real Control Center nested category and exact native channel scope exclude unrelated category')
        for attempt in range(120):
            programs = request('GET', '/LiveTv/Programs?channelIds=' + ','.join(scope['channelIds']), token=admin)['Items']
            if programs:
                break
            time.sleep(1)
        assert programs and all(normalize(p['ChannelId']) in {normalize(id) for id in scope['channelIds']} for p in programs)
        passed('stock native guide programs query with resolved scope returns only the selected channel')
        request('GET', '/ClientExtensions/v1/extensions/live-tv/guide-scopes/group-' + '0' * 32, token=admin, expected=404)
        passed('unknown guide scope fails closed')
        viewer_pw = secrets.token_urlsafe(24)
        viewer = request('POST', '/Users/New', {'Name': 'Fixture Viewer', 'Password': viewer_pw}, admin)
        vid = viewer['Id']
        policy = request('GET', '/Users/' + vid, token=admin)['Policy']
        policy['EnableLiveTvAccess'] = True
        request('POST', '/Users/' + vid + '/Policy', policy, admin, 204)
        session = login('Fixture Viewer', viewer_pw)
        token = session['AccessToken']
        viewer_discovery = request('GET', '/ClientExtensions/v1', token=token)
        assert 'for-you' not in {e['id'] for e in viewer_discovery['extensions']}
        assert 'live-tv' in {e['id'] for e in viewer_discovery['extensions']}
        passed('ordinary viewer discovery requires no admin; For You FeatureAccess enforced')
        request('GET', '/ClientExtensions/v1?userId=' + admin_id, token=token, expected=400)
        request('GET', '/ClientExtensions/v1/extensions/live-tv/screens/root?userId=' + admin_id, token=token, expected=400)
        passed('caller-selected identity rejected on real Jellyfin')
        # Grant For You, then revoke library access while section cache still exists.
        config['VisibleToUserIds'] = [vid]
        request('POST', '/Plugins/' + foryou_guid + '/Configuration', config, admin, 204)
        allowed = request('GET', '/ClientExtensions/v1', token=token)
        viewer_rows = next(e for e in allowed['extensions'] if e['id'] == 'for-you')['homeRows']
        assert viewer_rows
        policy['EnableAllFolders'] = False
        policy['EnabledFolders'] = []
        request('POST', '/Users/' + vid + '/Policy', policy, admin, 204)
        for row in viewer_rows:
            response = request('GET', '/ClientExtensions/v1/extensions/for-you/screens/' + row['screenId'], token=token)
            assert response['items'] == []
        passed('revoked library access filters cached For You media')
        config['VisibleToUserIds'] = []
        request('POST', '/Plugins/' + foryou_guid + '/Configuration', config, admin, 204)
        request('GET', '/ClientExtensions/v1/extensions/for-you/screens/' + viewer_rows[0]['screenId'], token=token, expected=404)
        passed('revoked For You FeatureAccess denies previously discovered screen')
        request('PUT', '/LiveTvGroups/Administration/Groups/' + child + '/Access', {'VisibleToAllUsers': False, 'AllowedUserIds': [], 'DeniedUserIds': [vid]}, admin, 204)
        request('GET', '/ClientExtensions/v1/extensions/live-tv/guide-scopes/' + gid(root), token=token, expected=404)
        passed('revoked child group access cannot widen parent guide scope')
        policy['EnableLiveTvAccess'] = False
        request('POST', '/Users/' + vid + '/Policy', policy, admin, 204)
        assert 'live-tv' not in {e['id'] for e in request('GET', '/ClientExtensions/v1', token=token)['extensions']}
        request('GET', '/ClientExtensions/v1/extensions/live-tv/guide-scopes/' + gid(other), token=token, expected=404)
        passed('revoked Live TV permission hides discovery and denies scopes immediately')
        # Protocol snapshots contain synthetic UUIDs/media only; no sessions, users or credentials.
        FIXTURES.mkdir(exist_ok=True)
        for filename, data in [('discovery.json', discovery), ('foryou-screen.json', nonempty), ('livetv-root.json', root_screen), ('livetv-nested.json', nested_screen), ('livetv-child.json', child_screen), ('guide-scope.json', scope), ('native-guide-programs.json', {'Items': programs}),
                               ('third-provider.json', request('GET', '/ClientExtensions/v1/extensions/library-sampler/screens/root?limit=2', token=admin))]:
            (FIXTURES / filename).write_text(json.dumps(data, indent=2) + '\n')
        child_scope = request('GET', '/ClientExtensions/v1/extensions/live-tv/guide-scopes/' + gid(child), token=admin)
        (FIXTURES / 'guide-scope-child.json').write_text(json.dumps(child_scope, indent=2) + '\n')
        passed('canonical protocol fixtures exported from actual plugin responses')
        completed = True
        if args.hold:
            session_file = runtime / 'synthetic-session.json'
            session_file.write_text(json.dumps({'server': base, 'username': 'Fixture Admin', 'password': password, 'token': admin}))
            session_file.chmod(0o600)
            print('Synthetic server: ' + base, flush=True)
            print('Private synthetic credentials (removed on exit): ' + str(session_file), flush=True)
            def stop(signum, frame):
                raise KeyboardInterrupt
            signal.signal(signal.SIGTERM, stop)
            try:
                signal.pause()
            except KeyboardInterrupt:
                pass
    finally:
        # This random name is ours; never restart/remove another container.
        subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        removed = subprocess.run(['docker', 'inspect', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False).returncode != 0
        # The container writes root-owned databases. Cleanup via isolated one-off volume mount, without host Docker socket.
        subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--entrypoint', '/bin/sh', '-v', str(runtime) + ':/fixture-runtime', args.image,
                        '-c', 'rm -rf /fixture-runtime/config'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        shutil.rmtree(runtime, ignore_errors=True)
        (ARTIFACTS / 'integration-evidence.json').write_text(json.dumps({'completed': completed, 'serverVersion': version, 'image': args.image, 'passed': evidence, 'containerRemoved': removed, 'runtimeRemoved': not runtime.exists(), 'syntheticOnly': True}, indent=2) + '\n')

if __name__ == '__main__':
    main()
