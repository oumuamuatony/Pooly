#!/bin/bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
ADB="$SDK_DIR/platform-tools/adb"
SERIAL="${1:-emulator-5554}"
"$ADB" -s "$SERIAL" reverse tcp:8765 tcp:8765
if [[ "$SERIAL" == emulator-* ]]; then
  "$ADB" -s "$SERIAL" forward tcp:8088 tcp:8088
fi
"$ADB" -s "$SERIAL" install -r "$TASK_ROOT/together/artifacts/together-dev.apk"
"$ADB" -s "$SERIAL" shell am start -n io.pickuptogether.app/.MainActivity --es apiUrl http://127.0.0.1:8765
