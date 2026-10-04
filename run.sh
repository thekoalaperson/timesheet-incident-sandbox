#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
root_dir="$PWD"
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v java >/dev/null || { echo 'Install Java 21 and set JAVA_HOME.' >&2; exit 1; }
command -v mvn >/dev/null || { echo 'Install Maven 3.9 or newer.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'Install Python 3.10 or newer.' >&2; exit 1; }
python3 - <<'PY'
import socket
for port in (8080, 8081):
    with socket.socket() as s:
        try:
            s.bind(('127.0.0.1', port))
        except OSError:
            raise SystemExit(f'Port {port} is already in use. Stop the existing service first.')
PY
mkdir -p runtime
mvn -q -f timesheet-service/pom.xml -DskipTests package
cp timesheet-service/target/timesheet-service-1.0.0.jar runtime/timesheet-service.jar
service_pid=''
monitor_pid=''
cleanup() {
  [[ -z "$monitor_pid" ]] || kill "$monitor_pid" 2>/dev/null || true
  [[ -z "$service_pid" ]] || kill "$service_pid" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
(
  cd timesheet-service
  export LOG_FILE="$root_dir/runtime/service.jsonl"
  export PORT=8080
  exec java -jar "$root_dir/runtime/timesheet-service.jar"
) >runtime/service-console.log 2>&1 &
service_pid=$!
python3 - <<'PY'
import time, urllib.request
for _ in range(120):
    try:
        urllib.request.urlopen('http://127.0.0.1:8080/actuator/health', timeout=1).close()
        break
    except OSError:
        time.sleep(1)
else:
    raise SystemExit('Service did not start. Check runtime/service-console.log.')
PY
python3 datadog-mock/server.py >runtime/monitor-console.log 2>&1 &
monitor_pid=$!
echo 'Timesheet UI: http://localhost:8080'
echo 'Monitor API: http://localhost:8081/api/v1/monitor'
echo 'Scenarios:   http://localhost:8081/api/scenarios'
echo 'Live work log: http://localhost:8081/worklog'
echo 'Logs: POST http://localhost:8081/api/v2/logs/events/search'
echo 'Run incident: curl -X POST http://localhost:8081/api/scenarios/monthly-total/run'
echo 'Reset:        curl -X POST http://localhost:8081/api/scenarios/reset'
echo 'Ctrl+C stops both applications. Runtime output stays in runtime/.'
while kill -0 "$service_pid" 2>/dev/null && kill -0 "$monitor_pid" 2>/dev/null; do
  sleep 1
done
echo 'An application stopped. Check runtime/ console logs.' >&2
exit 1
