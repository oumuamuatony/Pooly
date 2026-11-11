#!/bin/bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/adb"
SERIAL="${1:-emulator-5554}"
if [[ "$SERIAL" != emulator-* ]]; then
  echo "이 검증은 개인 데이터가 없는 가상 기기에서만 실행합니다."
  exit 1
fi
# UTP may remove the target app. Tests use a separate application identity.
CURRENT_USER="$("$ADB" -s "$SERIAL" shell am get-current-user | tr -d '\r')"
if "$ADB" -s "$SERIAL" shell pm list packages --user "$CURRENT_USER" io.pickuptogether.app.integration | tr -d '\r' | /usr/bin/grep -Fxq 'package:io.pickuptogether.app.integration'; then
  echo "검증용 앱이 이미 설치돼 있어 중지했어요. 기존 검증 작업이 끝났는지 확인해 주세요."
  exit 1
fi
if lsof -nP -iTCP:59583 -sTCP:LISTEN -t >/dev/null 2>&1; then
  echo "검증용 59583 포트가 사용 중입니다. 기존 프로세스를 자동으로 종료하지 않습니다."
  exit 1
fi
TEST_DIR="$(mktemp -d /tmp/together-device-test.XXXXXX)"
cd "$TASK_ROOT"
python3 -u -m together.server.app --port 59583 --database "$TEST_DIR/test.sqlite3" > "$TEST_DIR/server.log" 2>&1 &
TEST_SERVER_PID=$!
GPS_PID=""
cleanup(){
  if [ -n "$GPS_PID" ]; then kill "$GPS_PID" 2>/dev/null || true; fi
  kill "$TEST_SERVER_PID" 2>/dev/null || true
  echo "검증용 DB와 로그: $TEST_DIR"
}
trap cleanup EXIT INT TERM
"$ADB" -s "$SERIAL" reverse tcp:59583 tcp:59583
# Public Seoul coordinates, generated only for the emulator test. Never a user's
# real location. Move a few metres per sample to exercise GPS recording.
python3 - "$ADB" "$SERIAL" <<'PY' &
import subprocess,sys,time
adb,serial=sys.argv[1:]
for n in range(150):
    subprocess.run([adb,'-s',serial,'emu','geo','fix','127.0556',str(37.5444+n*0.00001)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=12)
    time.sleep(2)
PY
GPS_PID=$!
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_SERIAL="$SERIAL"
./android/PickupTogether/gradlew -p android/PickupTogether :app:assembleDebug :app:connectedIntegrationAndroidTest :app:lintDebug "${@:2}"
