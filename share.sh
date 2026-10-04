#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v cloudflared >/dev/null || { echo 'Install cloudflared first (on macOS: brew install cloudflared).' >&2; exit 1; }
mkdir -p runtime
python3 - <<'PY'
import socket, urllib.request
for port in (8080, 8081):
    urllib.request.urlopen(f'http://127.0.0.1:{port}/api/health', timeout=5).close()
for port in (8090, 8091):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(('127.0.0.1', port))
        except OSError:
            raise SystemExit(f'Port {port} is in use. Stop the existing share gateway first.')

PY
share_pids=()
cleanup() {
  for pid in "${share_pids[@]}"; do kill "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for spec in timesheet:8090:8080 monitor:8091:8081; do
  IFS=: read -r name gateway_port origin_port <<< "$spec"
  python3 share_gateway.py --port "$gateway_port" --origin "http://127.0.0.1:$origin_port" >"runtime/gateway-$name.log" 2>&1 &
  share_pids+=("$!")
  cloudflared tunnel --no-autoupdate --protocol http2 --url "http://127.0.0.1:$gateway_port" >"runtime/tunnel-$name.log" 2>&1 &
  share_pids+=("$!")
done
echo 'Creating temporary share links.'
python3 - <<'PY'
import json, os, pathlib, re, shlex, time
urls = {}
for _ in range(60):
    for name in ('timesheet', 'monitor'):
        text = pathlib.Path(f'runtime/tunnel-{name}.log').read_text()
        match = re.search(r'https://[a-z0-9-]+\.trycloudflare\.com', text)
        if match:
            urls[name] = match.group()
    if len(urls) == 2:
        break
    time.sleep(1)
else:
    raise SystemExit('Tunnel URLs not ready. Check runtime/tunnel-*.log.')
values = {'SANDBOX_MONITOR_URL': urls['monitor'], 'SANDBOX_UI_URL': urls['timesheet']}
pathlib.Path('runtime/shared-links.json').write_text(json.dumps(urls, indent=2))
fd = os.open('runtime/share.env', os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
os.chmod('runtime/share.env', 0o600)
with os.fdopen(fd, 'w') as f:
    for key, value in values.items():
        f.write(f'export {key}={shlex.quote(value)}\n')
for name, url in urls.items():
    print(f'{name}: {url}')
print('Connection settings saved to runtime/share.env. Ctrl+C stops sharing.')
invite = urls['monitor'] + '/handoff'
fd = os.open('runtime/handoff-link', os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
os.chmod('runtime/handoff-link', 0o600)
with os.fdopen(fd, 'w') as f:
    f.write(invite + '\n')
print('Collaborator handoff link saved to runtime/handoff-link.')
PY
while true; do
  for pid in "${share_pids[@]}"; do
    kill -0 "$pid" 2>/dev/null || { echo 'A share process stopped. Check runtime logs.' >&2; exit 1; }
  done
  sleep 1
done
