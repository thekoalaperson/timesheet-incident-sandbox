#!/usr/bin/env python3
"""Local Datadog-compatible monitoring facade for the timesheet service."""
import argparse
import binascii
import base64
import collections
import datetime as dt
import fnmatch
import hashlib
import json
import math
import os
import re
import shlex
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SERVICE = 'timesheet-service'
LOCK = threading.RLock()
LOGS = collections.deque(maxlen=20000)
HISTORY = collections.deque(maxlen=3600)
ALERTS = collections.deque(maxlen=1000)
STATE = {'health': None, 'state': 'No Data', 'generation': 0, 'sequence': 0, 'offset': 0, 'identity': None, 'checked_at': None}
LOG_MONITORS = {2: ('monthly-total', 'Monthly total correctness'), 3: ('duplicate-submission', 'Duplicate submissions'), 4: ('month-boundary', 'Month boundary errors')}
LOG_STATES = {key: 'No Data' for key in LOG_MONITORS}
SOURCE = ''
LOG_PATH = None
WORKLOG_PATH = Path(__file__).resolve().parent.parent / 'runtime' / 'worklog.jsonl'

def activity():
    records = collections.deque(maxlen=1000)
    if WORKLOG_PATH.exists():
        for line in WORKLOG_PATH.read_text().splitlines():
            try:
                record = json.loads(line)
                if isinstance(record, dict):
                    records.append(record)
            except ValueError:
                continue
    return {'data': list(records)}

def append_activity(body):
    if not isinstance(body, dict):
        raise ValueError('Work log entry must be an object')
    action, details = body.get('action'), body.get('details', '')
    status = body.get('status', 'completed')
    if not isinstance(action, str) or not action.strip() or len(action) > 1000:
        raise ValueError('action must be a nonempty string of at most 1000 characters')
    if not isinstance(details, str) or len(details) > 10000:
        raise ValueError('details must be a string of at most 10000 characters')
    if status not in ('completed', 'working', 'blocked'):
        raise ValueError('status must be completed, working, or blocked')
    record = {'id': str(time.time_ns()), 'timestamp': now(), 'action': action.strip(), 'status': status, 'details': details}
    WORKLOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    with WORKLOG_PATH.open('a') as stream:
        stream.write(json.dumps(record) + '\n')
    return record

def now():
    return dt.datetime.now(dt.timezone.utc).isoformat().replace('+00:00', 'Z')

def timestamp(value, default=None):
    if value is None:
        return default
    if isinstance(value, (int, float)):
        return float(value) / 1000
    if value == 'now':
        return time.time()
    match = re.fullmatch(r'now-(\d+)([smhd])', str(value))
    if match:
        return time.time() - int(match[1]) * {'s': 1, 'm': 60, 'h': 3600, 'd': 86400}[match[2]]
    try:
        return dt.datetime.fromisoformat(str(value).replace('Z', '+00:00')).timestamp()
    except (ValueError, TypeError):
        raise ValueError('Time must be ISO-8601, epoch milliseconds, now, or now-N[s|m|h|d]')

def upstream(path, body=None):
    request = urllib.request.Request(SOURCE + path, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=4) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError('Service returned HTTP ' + str(error.code)) from error

def ingest():
    try:
        stat = LOG_PATH.stat()
    except FileNotFoundError:
        return
    identity = (stat.st_dev, stat.st_ino)
    if STATE['identity'] != identity or stat.st_size < STATE['offset']:
        STATE['offset'] = 0
        STATE['identity'] = identity
    with LOG_PATH.open('rb') as stream:
        stream.seek(STATE['offset'])
        while True:
            line = stream.readline()
            if not line or not line.endswith(b'\n'):
                break
            STATE['offset'] = stream.tell()
            try:
                record = json.loads(line)
                if not isinstance(record, dict) or not isinstance(record.get('attributes', {}), dict):
                    continue
                if not isinstance(record.get('status', record.get('level', 'info')), str):
                    continue
                if not isinstance(record.get('service', SERVICE), str):
                    continue
                record_time = timestamp(record['timestamp'])
                if not math.isfinite(record_time):
                    continue
            except (ValueError, KeyError, TypeError, OverflowError):
                continue
            STATE['sequence'] += 1
            LOGS.append({'id': str(STATE['sequence']), 'type': 'log', 'attributes': {'timestamp': record['timestamp'], 'service': record.get('service', SERVICE), 'status': record.get('status', record.get('level', 'info')).lower(), 'message': record.get('message', ''), 'attributes': record.get('attributes', {}), 'tags': ['service:' + record.get('service', SERVICE), 'env:local']}, '_time': record_time})

def observe():
    with LOCK:
        ingest()
        try:
            health = upstream('/api/health')
            if not isinstance(health, dict) or not isinstance(health.get('status'), str):
                raise ValueError('Service health response must be an object')
            state = {'healthy': 'OK', 'degraded': 'Warn', 'unhealthy': 'Alert'}.get(health.get('status'), 'No Data')
        except (OSError, ValueError, RuntimeError) as error:
            health = {'service': SERVICE, 'status': 'unreachable', 'error': str(error), 'timestamp': now()}
            state = 'No Data'
        previous = STATE['state']
        STATE.update(health=health, state=state, checked_at=now())
        HISTORY.append({'observed_at': STATE['checked_at'], 'monitor_state': state, 'health': health})
        if state != previous:
            ALERTS.append({'id': str(len(ALERTS)) + '-' + str(time.time_ns()), 'monitor_id': 1, 'service': SERVICE, 'timestamp': now(), 'previous_state': previous, 'state': state, 'transition': 'recovery' if state == 'OK' else 'alert' if state == 'Alert' else 'warning' if state == 'Warn' else 'no_data', 'health': health, 'log_search': {'filter': {'query': 'service:' + SERVICE, 'from': 'now-5m', 'to': 'now'}}})
        for key, (scenario, name) in LOG_MONITORS.items():
            records = [log for log in LOGS if log['_time'] >= time.time()-60 and log['attributes']['attributes'].get('scenario_id') == scenario]
            levels = {log['attributes']['status'] for log in records}
            state = 'Alert' if 'error' in levels else 'Warn' if 'warn' in levels or 'warning' in levels else 'OK' if health.get('status') != 'unreachable' else 'No Data'
            old = LOG_STATES[key]
            LOG_STATES[key] = state
            if old != state:
                ALERTS.append({'id': str(time.time_ns()), 'monitor_id': key, 'service': SERVICE, 'timestamp': now(), 'previous_state': old, 'state': state, 'transition': 'recovery' if state == 'OK' else 'alert' if state == 'Alert' else 'warning' if state == 'Warn' else 'no_data', 'log_ids': [log['id'] for log in records], 'log_search': {'filter': {'query': 'service:' + SERVICE + ' @scenario_id:' + scenario, 'from': dt.datetime.fromtimestamp(time.time()-60, dt.timezone.utc).isoformat(), 'to': now()}}})

def poll():
    while True:
        observe()
        time.sleep(1)

def monitor(key=1):
    if key in LOG_MONITORS:
        scenario, name = LOG_MONITORS[key]
        return {'id': key, 'name': name, 'type': 'log alert', 'overall_state': LOG_STATES[key], 'service': SERVICE, 'tags': ['service:' + SERVICE, 'env:local'], 'query': 'logs("service:' + SERVICE + ' @scenario_id:' + scenario + ' status:(warn OR error)").index("*").rollup("count").last("1m") >= 1', 'evaluation_window_seconds': 60, 'last_observed_at': STATE['checked_at'], 'log_search': {'filter': {'query': 'service:' + SERVICE + ' @scenario_id:' + scenario, 'from': 'now-5m', 'to': 'now'}}, 'options': {'notify_no_data': True, 'thresholds': {'warning': 1, 'critical': 1}}, 'severity_evaluation': {'Alert': 'At least one error log in the last 60 seconds', 'Warn': 'At least one warn/warning log and no error logs in the last 60 seconds', 'OK': 'No matching warn/error logs and service health is available', 'No Data': 'Service health unavailable and no matching warn/error logs'}, 'message': 'Investigate related scenario logs, including request_id, trace_id and error.stack.'}
    return {'id': 1, 'name': 'Timesheet service health', 'type': 'service check', 'query': '"timesheet.service.health".over("service:timesheet-service").last(1).count_by_status()', 'message': 'Investigate timesheet-service logs and health history using the monitor API.', 'tags': ['service:' + SERVICE, 'env:local'], 'overall_state': STATE['state'], 'options': {'notify_no_data': True, 'thresholds': {'warning': 1, 'critical': 1}}, 'state': {'groups': {'service:' + SERVICE: {'status': STATE['state']}}}, 'service': SERVICE, 'last_observed_at': STATE['checked_at'], 'health': STATE['health'], 'log_search': {'filter': {'query': 'service:' + SERVICE, 'from': 'now-15m', 'to': 'now'}}}

def predicates(query):
    """Explicit subset: whitespace/AND conjunction, exact or glob field values."""
    if not isinstance(query, str):
        raise ValueError('filter.query must be a string')
    try:
        tokens = shlex.split(query)
    except ValueError as error:
        raise ValueError('Malformed quoted query') from error
    result = []
    for token in tokens:
        if token in ('*', 'AND'):
            continue
        if token in ('OR', 'NOT') or any(c in token for c in '()[]{}') or token.startswith('-'):
            raise ValueError('Unsupported query syntax: use AND-conjoined field:value terms; OR, NOT, ranges and groups are unsupported')
        if ':' not in token:
            raise ValueError('Unsupported free text; use message:"text" or field:value')
        key, value = token.split(':', 1)
        if key not in ('service', 'status', 'message', 'host', 'source') and not key.startswith('@'):
            raise ValueError('Unsupported field: ' + key + '; custom attributes must start with @')
        if not value:
            raise ValueError('Empty query value')
        result.append((key, value))
    return result

def matches(log, conditions):
    attrs = log['attributes']
    for key, value in conditions:
        found = attrs.get(key) if not key.startswith('@') else attrs['attributes'].get(key[1:])
        if found is None and key.startswith('@'):
            found = attrs['attributes']
            for part in key[1:].split('.'):
                found = found.get(part) if isinstance(found, dict) else None
        if found is None or not fnmatch.fnmatchcase(str(found), value):
            return False
    return True

def search(body):
    if not isinstance(body, dict):
        raise ValueError('Request body must be an object')
    if set(body) - {'filter', 'sort', 'page'}:
        raise ValueError('Supported body fields: filter, sort, page')
    filters = body.get('filter', {})
    page = body.get('page', {})
    if not isinstance(filters, dict) or not isinstance(page, dict):
        raise ValueError('filter and page must be objects')
    if set(filters) - {'query', 'from', 'to', 'indexes'} or set(page) - {'limit', 'cursor'}:
        raise ValueError('Unsupported filter/page field')
    if filters.get('indexes', ['*']) != ['*']:
        raise ValueError('Only indexes:["*"] is supported')
    conditions = predicates(filters.get('query', '*'))
    sort = body.get('sort', '-timestamp')
    if sort not in ('timestamp', '-timestamp'):
        raise ValueError('sort must be timestamp or -timestamp')
    limit = page.get('limit', 50)
    if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 1000:
        raise ValueError('page.limit must be an integer from 1 to 1000')
    signature = hashlib.sha256(json.dumps({'filter': filters, 'sort': sort}, sort_keys=True).encode()).hexdigest()
    start, end = timestamp(filters.get('from'), time.time()-900), timestamp(filters.get('to'), time.time())
    ceiling, offset = STATE['sequence'], 0
    if 'cursor' in page:
        try:
            if not isinstance(page['cursor'], str):
                raise ValueError()
            cursor = json.loads(base64.b64decode(page['cursor'], altchars=b'-_', validate=True))
            if not isinstance(cursor, dict):
                raise ValueError()
            for field in ('start', 'end'):
                if isinstance(cursor[field], bool) or not isinstance(cursor[field], (int, float)) or not math.isfinite(cursor[field]):
                    raise ValueError()
            for field in ('ceiling', 'offset', 'generation'):
                if isinstance(cursor[field], bool) or not isinstance(cursor[field], int) or cursor[field] < 0:
                    raise ValueError()
            if cursor['signature'] != signature or cursor['generation'] != STATE['generation']:
                raise ValueError()
            start, end, ceiling, offset = cursor['start'], cursor['end'], cursor['ceiling'], cursor['offset']
            if LOGS and cursor['oldest'] != LOGS[0]['id']:
                raise ValueError()
        except (ValueError, TypeError, KeyError, binascii.Error, UnicodeDecodeError, OverflowError):
            raise ValueError('Invalid, expired, or mismatched cursor')
    if not math.isfinite(start) or not math.isfinite(end):
        raise ValueError('Time must be finite')
    if start > end:
        raise ValueError('filter.from must precede filter.to')
    rows = [log for log in LOGS if int(log['id']) <= ceiling and start <= log['_time'] <= end and matches(log, conditions)]
    rows.sort(key=lambda x: (x['_time'], int(x['id'])), reverse=sort == '-timestamp')
    selected = rows[offset:offset+limit]
    meta = {'status': 'done', 'page': {}}
    if offset + limit < len(rows):
        cursor = {'signature': signature, 'generation': STATE['generation'], 'start': start, 'end': end, 'ceiling': ceiling, 'offset': offset+limit, 'oldest': LOGS[0]['id']}
        meta['page']['after'] = base64.urlsafe_b64encode(json.dumps(cursor).encode()).decode()
    return {'data': [{k: v for k, v in log.items() if k != '_time'} for log in selected], 'meta': meta}

class Handler(BaseHTTPRequestHandler):
    def respond(self, status, data):
        payload = json.dumps(data).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path in ('/', '/worklog'):
            payload = Path(__file__).with_name('worklog.html').read_bytes()
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(payload)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(payload)
            return
        with LOCK:
            if path == '/api/worklog':
                data = activity()
            elif path == '/api/health':
                data = {'status': 'ok', 'service': 'datadog-mock', 'observed_service': SERVICE, 'monitor_state': STATE['state'], 'last_observed_at': STATE['checked_at'], 'retained_logs': len(LOGS)}
            elif path == '/api/v1/monitor':
                data = [monitor(key) for key in [1, *LOG_MONITORS]]
            elif re.fullmatch(r'/api/v1/monitor/[1-4]', path):
                data = monitor(int(path.rsplit('/', 1)[1]))
            elif path == '/api/services/' + SERVICE + '/health':
                data = {'service': SERVICE, 'current': STATE['health'], 'monitor_state': STATE['state'], 'history': list(HISTORY)}
            elif path == '/api/alerts':
                data = {'data': list(ALERTS)}
            elif path == '/api/scenarios':
                try:
                    data = upstream(path)
                except (OSError, ValueError, RuntimeError) as error:
                    return self.respond(502, {'errors': [str(error)]})
            else:
                return self.respond(404, {'errors': ['Unknown endpoint']})
            self.respond(200, data)

    def do_POST(self):
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if size < 0 or size > 1048576:
                return self.respond(413, {'errors': ['Body exceeds 1 MiB']})
            body = json.loads(self.rfile.read(size)) if size else {}
            path = urllib.parse.urlsplit(self.path).path
            with LOCK:
                if path == '/api/worklog':
                    data = append_activity(body)
                elif path == '/api/v2/logs/events/search':
                    ingest()
                    data = search(body)
                elif path == '/api/scenarios/reset':
                    data = upstream(path, body)
                    # Drain the real reset event, then start a fresh observation epoch.
                    ingest()
                    LOGS.clear()
                    HISTORY.clear()
                    ALERTS.clear()
                    STATE['generation'] += 1
                    # Preserve prior states so the new epoch records actual recoveries.
                    STATE['health'] = None
                    observe()
                elif re.fullmatch(r'/api/scenarios/[a-z0-9-]+/run', path):
                    data = upstream(path, body)
                    observe()
                else:
                    return self.respond(404, {'errors': ['Unknown endpoint']})
                self.respond(200, data)
        except (ValueError, TypeError, OverflowError) as error:
            self.respond(400, {'errors': [str(error)]})
        except (OSError, RuntimeError) as error:
            self.respond(502, {'errors': [str(error)]})

    def log_message(self, format, *args):
        pass

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=int(os.environ.get('PORT', '8081')))
    parser.add_argument('--host', default=os.environ.get('HOST', '127.0.0.1'))
    parser.add_argument('--service-url', default=os.environ.get('SERVICE_URL', 'http://127.0.0.1:8080'))
    parser.add_argument('--log-file', type=Path, default=Path(os.environ.get('LOG_FILE', str(Path(__file__).resolve().parent.parent / 'runtime' / 'service.jsonl'))))
    parser.add_argument('--worklog-file', type=Path, default=Path(os.environ.get('WORKLOG_FILE', str(WORKLOG_PATH))))
    args = parser.parse_args()
    SOURCE, LOG_PATH = args.service_url.rstrip('/'), args.log_file
    WORKLOG_PATH = args.worklog_file
    threading.Thread(target=poll, daemon=True).start()
    print('Datadog mock listening on http://' + args.host + ':' + str(args.port), flush=True)
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
