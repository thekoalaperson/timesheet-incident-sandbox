#!/usr/bin/env python3
"""Authenticated local reverse proxy for temporary sandbox sharing."""
import argparse
import base64
import hmac
import http.client
import json
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOP_HEADERS = {'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade'}

class Gateway(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def document(self, payload, content_type):
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(payload)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.end_headers()
        self.wfile.write(payload)

    def authorized(self):
        auth = self.headers.get('Authorization', '')
        scheme, _, credentials = auth.partition(' ')
        candidate = ''
        if scheme.lower() == 'bearer':
            candidate = credentials
        elif scheme.lower() == 'basic':
            try:
                username, separator, password = base64.b64decode(credentials, validate=True).decode().partition(':')
                if username == 'demo' and separator:
                    candidate = password
            except (ValueError, UnicodeDecodeError):
                pass
        return hmac.compare_digest(candidate.encode(), self.server.token)

    def reply(self, status, message, authenticate=False):
        body = message.encode()
        self.send_response(status)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        if authenticate:
            self.send_header('WWW-Authenticate', 'Basic realm="Timesheet sandbox", charset="UTF-8"')
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def proxy(self):
        if self.command == 'GET' and self.path == '/handoff':
            try:
                return self.document(Path(__file__).with_name('handoff.html').read_bytes(), 'text/html; charset=utf-8')
            except OSError:
                return self.reply(503, 'Handoff page unavailable\n')
        if not self.authorized():
            return self.reply(401, 'Authentication required\n', True)
        if self.command == 'GET' and self.path == '/api/share-info':
            try:
                links = json.loads(Path(__file__).with_name('runtime').joinpath('shared-links.json').read_text())
                payload = json.dumps({'monitor_url': links['monitor'], 'timesheet_url': links['timesheet'], 'repo_url': 'https://github.com/thekoalaperson/timesheet-incident-sandbox', 'service_id': 'timesheet-service', 'service_path': 'timesheet-service/'}).encode()
                return self.document(payload, 'application/json')
            except (OSError, ValueError, KeyError, TypeError):
                return self.reply(503, 'Share links are not ready\n')
        try:
            parsed = urllib.parse.urlsplit(self.path)
        except ValueError:
            return self.reply(400, 'Invalid request target\n')
        if not self.path.startswith('/') or self.path.startswith('//') or parsed.scheme or parsed.netloc or parsed.fragment:
            return self.reply(400, 'Invalid request target\n')
        if self.headers.get('Transfer-Encoding'):
            return self.reply(400, 'Transfer-Encoding is unsupported\n')
        lengths = self.headers.get_all('Content-Length', [])
        try:
            if len(lengths) > 1:
                raise ValueError()
            size = int(lengths[0]) if lengths else 0
            if size < 0:
                raise ValueError()
        except ValueError:
            return self.reply(400, 'Invalid Content-Length\n')
        if size > 1048576:
            return self.reply(413, 'Request body exceeds 1 MiB\n')
        self.connection.settimeout(15)
        connection = None
        try:
            body = self.rfile.read(size)
            if len(body) != size:
                return self.reply(400, 'Incomplete request body\n')
            blocked = HOP_HEADERS | {'authorization', 'host', 'content-length'}
            blocked.update(value.strip().lower() for value in self.headers.get('Connection', '').split(','))
            headers = {key: value for key, value in self.headers.items() if key.lower() not in blocked}
            headers['Content-Length'] = str(size)
            connection = http.client.HTTPConnection(self.server.origin.hostname, self.server.origin.port or 80, timeout=15)
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            excluded = HOP_HEADERS | {'content-length'}
            excluded.update(value.strip().lower() for value in (response.getheader('Connection') or '').split(','))
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in excluded:
                    self.send_header(key, value)
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except (OSError, http.client.HTTPException):
            self.reply(502, 'Local service unavailable\n')
        finally:
            if connection:
                connection.close()

    do_GET = proxy
    do_POST = proxy
    do_PUT = proxy
    do_DELETE = proxy

    def log_message(self, format, *args):
        pass

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--origin', required=True)
    parser.add_argument('--token-file', type=Path, required=True)
    args = parser.parse_args()
    origin = urllib.parse.urlsplit(args.origin)
    if origin.scheme != 'http' or origin.hostname not in ('localhost', '127.0.0.1') or origin.path not in ('', '/') or origin.query or origin.fragment or origin.username or origin.password:
        parser.error('--origin must be a local http://localhost:PORT origin')
    token = args.token_file.read_bytes().strip()
    if not token:
        parser.error('Token file is empty')
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Gateway)
    server.origin, server.token = origin, token
    print('Share gateway listening on 127.0.0.1:' + str(args.port), flush=True)
    server.serve_forever()
