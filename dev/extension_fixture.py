#!/usr/bin/env python3
"""Loopback-only, three independent providers implementing the shared v1 wire contract.
No household services or credentials. Used by the native JVM tests and Android emulator.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from datetime import datetime, timedelta, timezone
import json
import os

IDS = [f'00000000-0000-0000-0000-{i:012d}' for i in (1, 2, 3)]
PROVIDERS = ('curation', 'television', 'community')
def action(kind, **kwargs):
    return dict(type=kind, **kwargs)
def entry(ident, title, act, media=None):
    return dict(id=ident, title=title, imageUrl=None, media=media, action=act)
def discovery():
    return dict(protocolVersion=1, extensions=[dict(id=p, title=t, version='1.0.0', entryPoints=[dict(id='entry', title='Browse', screenId='root')], homeRows=[dict(id='home', title=t, screenId='root')]) for p,t in zip(PROVIDERS, ('For You', 'Television', 'Community'))])
def screen(provider, ident, start, limit):
    if provider == 'television':
        items = [entry('sports', 'Sports', action('openScreen', extensionId=provider, screenId='sports')), entry('news', 'News', action('openGuide', extensionId=provider, scopeId='news'))] if ident == 'root' else [entry('football', 'Football guide', action('openGuide', extensionId=provider, scopeId='sports')), entry('news', 'News guide', action('openGuide', extensionId=provider, scopeId='news'))]
        view, title = 'categories', 'Television' if ident == 'root' else 'Sports'
    elif provider == 'curation':
        items = [entry(f'movie-{i}', f'Featured movie {i}', action('openItem' if i % 2 == 0 else 'playItem', itemId=IDS[0]), dict(Id=IDS[0], Name=f'Featured movie {i}', Type='Movie', MediaType='Video')) for i in range(85)]
        view, title = 'grid', 'For You · Featured'
    elif provider == 'community':
        items = [entry('events', 'Community events', action('openScreen', extensionId=provider, screenId='events'))] if ident == 'root' else [entry('local', 'Local collection', action('openItem', itemId=IDS[0]))]
        view, title = 'grid', 'Community'
    else:
        raise ValueError('unknown provider')
    return dict(protocolVersion=1, id=ident, title=title, view=view, startIndex=start, totalRecordCount=len(items), items=items[start:start+limit])

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        url = urlparse(self.path)
        path, query = url.path, parse_qs(url.query)
        if path == '/__fixture/health':
            return self.reply(200, {'ok': True})
        if path.startswith('/__fixture/state/'):
            self.server.mode = path.rsplit('/', 1)[-1]
            return self.reply(200, {'mode': self.server.mode})
        with open(self.server.log_path, 'a') as f:
            f.write(json.dumps(dict(path=path, query=query, authenticated=self.headers.get('X-Emby-Token') == 'fixture-viewer-token' or 'fixture-viewer-token' in self.headers.get('Authorization', ''))) + '\n')
        if not (self.headers.get('X-Emby-Token') == 'fixture-viewer-token' or 'fixture-viewer-token' in self.headers.get('Authorization', '')):
            return self.reply(401, {})
        if self.server.mode == 'absent' and path.startswith('/ClientExtensions'): return self.reply(404, {})
        if self.server.mode == 'denied' and ('guide-scopes' in path or path.startswith('/LiveTv')): return self.reply(403, {})
        if path == '/ClientExtensions/v1':
            data = discovery()
            if self.server.mode == 'removed': data['extensions'] = [p for p in data['extensions'] if p['id'] != 'television']
            if self.server.mode == 'version': data['protocolVersion'] = 99
            return self.reply(200, data)
        parts = path.strip('/').split('/')
        if len(parts) == 6 and parts[:3] == ['ClientExtensions', 'v1', 'extensions']:
            provider, route, ident = parts[3:]
            if route == 'screens':
                data = screen(provider, ident, int(query.get('startIndex', ['0'])[0]), int(query.get('limit', ['40'])[0]))
                if self.server.mode == 'malformed': data['items'][0]['action']['type'] = 'executeJavascript'
                return self.reply(200, data)
            if route == 'guide-scopes':
                ids = IDS[:2] if ident == 'sports' else IDS[2:]
                if self.server.mode == 'empty': ids = []
                return self.reply(200, dict(protocolVersion=1, id=ident, title=ident.title(), channelIds=ids))
        if path.startswith('/LiveTv/Channels/'):
            ident = path.rsplit('/', 1)[-1].replace('-', '')
            index = next((i for i, ch in enumerate(IDS) if ch.replace('-', '') == ident), None)
            if index is None: return self.reply(404, {})
            return self.reply(200, dict(Id=IDS[index], Name=['Sports One', 'Sports Two', 'News Only'][index], Number=str(index+1), Type='TvChannel', MediaType='Video'))
        if path == '/LiveTv/Programs':
            requested = [part for value in query.get('channelIds', query.get('ChannelIds', [''])) for part in value.split(',')]
            now = datetime.now(timezone.utc).replace(tzinfo=None)
            items = [dict(Id=f'20000000-0000-0000-0000-{i+1:012d}', ChannelId=ch, Name=f'Live fixture program {i+1}', Type='Program', MediaType='Video', StartDate=(now-timedelta(hours=1)).isoformat()+'Z', EndDate=(now+timedelta(hours=9)).isoformat()+'Z') for i,ch in enumerate(IDS) if ch.replace('-', '') in [r.replace('-', '') for r in requested]]
            return self.reply(200, dict(Items=items, TotalRecordCount=len(items), StartIndex=0))
        return self.reply(404, {})
    def reply(self, status, data):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=18096)
    parser.add_argument('--log', required=True)
    args = parser.parse_args()
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    server.mode, server.log_path = 'normal', args.log
    server.serve_forever()
