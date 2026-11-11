#!/bin/bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$TASK_ROOT"
FLASK_PYTHON="$TASK_ROOT/together/.venv/bin/python"
FLASK_PORT="${POOLY_FLASK_PORT:-8769}"
if [ ! -x "$FLASK_PYTHON" ]; then
  echo "먼저 python3 -m venv together/.venv 를 실행하고 together/requirements.txt를 설치해 주세요."
  exit 1
fi
if "$FLASK_PYTHON" - "$FLASK_PORT" <<'PY'
import json, sys, urllib.request
try:
    with urllib.request.urlopen('http://127.0.0.1:' + sys.argv[1] + '/health', timeout=2) as response:
        status = json.load(response)
    sys.exit(0 if status.get('service') == 'together' and status.get('framework') == 'flask' else 1)
except Exception:
    sys.exit(1)
PY
then
  echo "Flask 서버를 그대로 사용합니다: http://127.0.0.1:$FLASK_PORT/phone.html"
  exit 0
fi
exec "$FLASK_PYTHON" -m together.server.local_flask --port "$FLASK_PORT"
