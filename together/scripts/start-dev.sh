#!/bin/bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export POOLY_FLASK_PORT=8765
export POOLY_DATABASE_PATH="${POOLY_DATABASE_PATH:-$TASK_ROOT/together/data/together.sqlite3}"
export POOLY_SQLITE_JOURNAL_MODE="${POOLY_SQLITE_JOURNAL_MODE:-WAL}"
if [ -z "${POOLY_DEMO_OWNER:-}" ] && [ -f "$TASK_ROOT/together/data/private/demo-owner" ]; then
  POOLY_DEMO_OWNER="$(cat "$TASK_ROOT/together/data/private/demo-owner")"
  export POOLY_DEMO_OWNER
fi
exec "$TASK_ROOT/together/scripts/start-flask.sh"
