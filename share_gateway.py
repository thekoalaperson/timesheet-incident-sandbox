#!/usr/bin/env python3
"""Authenticated local reverse proxy for temporary sandbox sharing."""
import argparse
import base64
import hmac
import hashlib
import http.client
import json
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.cookies import SimpleCookie, CookieError
from pathlib import Path

HOP_HEADERS = {'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade'}
SESSION_COOKIE = 'sandbox_session'
SESSION_SECONDS = 8 * 60 * 60
OPEN_PAGE = b'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Opening sandbox</title><body style="font:16px system-ui;max-width:640px;margin:80px auto;padding:24px"><h1>Opening your demo</h1><p id="status">Connecting with your invitation...</p><script>
(async () => {
  try {
    const params = new URLSearchParams(location.hash.slice(1));
    const token = params.get('token');
    const target = new URL(params.get('path') || '/', location.origin);
    if (!token || target.origin !== location.origin || !target.pathname.startsWith('/')) throw new Error('Reopen the app from your handoff invitation.');
    const response = await fetch('/api/share-session', {method:'POST',headers:{Authorization:'Bearer '+token},cache:'no-store'});
    if (!response.ok) throw new Error('The invitation could not open this app. Reopen the handoff link.');
    location.replace(target.pathname + target.search + target.hash);
  } catch (error) { document.getElementById('status').textContent = error.message; }
})();
</script></body></html>'''

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
        if not auth:
            try:
                cookies = SimpleCookie(self.headers.get('Cookie', ''))
                value = cookies[SESSION_COOKIE].value
                expiry, signature = value.split('.', 1)
                remaining = int(expiry) - int(time.time())
                expected = hmac.new(self.server.token, ('browser-session:' + expiry).encode(), hashlib.sha256).hexdigest()
                return 0 < remaining <= SESSION_SECONDS and hmac.compare_digest(signature, expected)
            except (CookieError, KeyError, ValueError, TypeError):
                return False
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
        if self.command == 'GET' and self.path == '/open':
            return self.document(OPEN_PAGE, 'text/html; charset=utf-8')
        if self.command == 'GET' and self.path == '/handoff':
            try:
                return self.document(Path(__file__).with_name('handoff.html').read_bytes(), 'text/html; charset=utf-8')
            except OSError:
                return self.reply(503, 'Handoff page unavailable\n')
        if not self.authorized():
            return self.reply(401, 'Authentication required\n', True)
        if self.command == 'POST' and self.path == '/api/share-session':
            # Authorization is the entire input. Cloudflare can forward an empty
            # HTTP/2 POST as chunked HTTP/1.1. Close after replying so unread body
            # framing cannot be interpreted as another request.
            expiry = str(int(time.time()) + SESSION_SECONDS)
            signature = hmac.new(self.server.token, ('browser-session:' + expiry).encode(), hashlib.sha256).hexdigest()
            self.send_response(204)
            self.send_header('Set-Cookie', SESSION_COOKIE + '=' + expiry + '.' + signature + '; Path=/; Max-Age=' + str(SESSION_SECONDS) + '; Secure; HttpOnly; SameSite=Lax')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', '0')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            return
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
            blocked = HOP_HEADERS | {'authorization', 'host', 'content-length', 'cookie'}
            blocked.update(value.strip().lower() for value in self.headers.get('Connection', '').split(','))
            headers = {key: value for key, value in self.headers.items() if key.lower() not in blocked}
            try:
                cookies = SimpleCookie(self.headers.get('Cookie', ''))
                if SESSION_COOKIE in cookies:
                    del cookies[SESSION_COOKIE]
                if cookies:
                    headers['Cookie'] = '; '.join(value.OutputString() for value in cookies.values())
            except CookieError:
                pass
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
