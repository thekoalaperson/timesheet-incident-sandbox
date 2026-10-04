#!/usr/bin/env python3
"""Local reverse proxy and handoff page for temporary sandbox sharing."""
import argparse
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

    def reply(self, status, message):
        body = message.encode()
        self.send_response(status)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def read_body(self):
        lengths = self.headers.get_all('Content-Length', [])
        encodings = self.headers.get_all('Transfer-Encoding', [])
        if encodings:
            if lengths or len(encodings) != 1 or encodings[0].lower().strip() != 'chunked':
                raise ValueError('Invalid request body framing')
            body = bytearray()
            while True:
                line = self.rfile.readline(1025)
                size_text = line.split(b';', 1)[0].strip()
                if not line.endswith(b'\r\n') or len(line) > 1024 or not size_text or any(c not in b'0123456789abcdefABCDEF' for c in size_text):
                    raise ValueError('Invalid chunk size')
                size = int(size_text, 16)
                if len(body) + size > 1048576:
                    raise OverflowError('Request body exceeds 1 MiB')
                if size == 0:
                    trailer_size = 0
                    while True:
                        trailer = self.rfile.readline(1025)
                        trailer_size += len(trailer)
                        if not trailer.endswith(b'\r\n') or len(trailer) > 1024 or trailer_size > 8192:
                            raise ValueError('Invalid chunk trailers')
                        if trailer == b'\r\n':
                            return bytes(body)
                chunk = self.rfile.read(size)
                if len(chunk) != size or self.rfile.read(2) != b'\r\n':
                    raise ValueError('Incomplete chunk')
                body.extend(chunk)
        if len(lengths) > 1:
            raise ValueError('Invalid Content-Length')
        size = int(lengths[0]) if lengths else 0
        if size < 0:
            raise ValueError('Invalid Content-Length')
        if size > 1048576:
            raise OverflowError('Request body exceeds 1 MiB')
        body = self.rfile.read(size)
        if len(body) != size:
            raise ValueError('Incomplete request body')
        return body

    def proxy(self):
        if self.command == 'GET' and self.path == '/handoff':
            try:
                return self.document(Path(__file__).with_name('handoff.html').read_bytes(), 'text/html; charset=utf-8')
            except OSError:
                return self.reply(503, 'Handoff page unavailable\n')
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
        self.connection.settimeout(15)
        connection = None
        try:
            body = self.read_body()
            blocked = HOP_HEADERS | {'authorization', 'host', 'content-length'}
            blocked.update(value.strip().lower() for value in self.headers.get('Connection', '').split(','))
            headers = {key: value for key, value in self.headers.items() if key.lower() not in blocked}
            headers['Content-Length'] = str(len(body))
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
        except OverflowError as error:
            self.reply(413, str(error) + '\n')
        except ValueError as error:
            self.reply(400, str(error) + '\n')
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
    args = parser.parse_args()
    origin = urllib.parse.urlsplit(args.origin)
    if origin.scheme != 'http' or origin.hostname not in ('localhost', '127.0.0.1') or origin.path not in ('', '/') or origin.query or origin.fragment or origin.username or origin.password:
        parser.error('--origin must be a local http://localhost:PORT origin')
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Gateway)
    server.origin = origin
    print('Share gateway listening on 127.0.0.1:' + str(args.port), flush=True)
    server.serve_forever()
