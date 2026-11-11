#!/bin/bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/adb"
SERIAL="${1:-emulator-5554}"
if [[ "$SERIAL" != emulator-* ]]; then
  echo "시연은 실제 휴대폰이나 차량 대신 에뮬레이터에서 실행해 주세요."
  exit 1
fi
for PORT in 8766 8767 8768; do
  if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "시연용 $PORT 포트가 이미 사용 중이에요. 기존 서버를 종료하지 않았어요."
    echo "기존 시연 화면: http://127.0.0.1:8766/"
    exit 1
  fi
done
cd "$TASK_ROOT"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
./android/PickupTogether/gradlew -p android/PickupTogether :app:assembleDemo
"$ADB" -s "$SERIAL" install -r together/artifacts/together-demo.apk
CURRENT_USER="$("$ADB" -s "$SERIAL" shell am get-current-user | tr -d '\r')"
# Each server run creates fresh temporary identities. Clear only the disposable
# demo client's old login so it cannot keep a token from the previous run.
"$ADB" -s "$SERIAL" shell pm clear --user "$CURRENT_USER" io.pickuptogether.app.demo
# Only the disposable emulator demo identity receives these location permissions.
for PERMISSION in android.permission.ACCESS_COARSE_LOCATION android.permission.ACCESS_FINE_LOCATION pleos.car.permission.FUSED_LOCATION android.permission.POST_NOTIFICATIONS; do
  "$ADB" -s "$SERIAL" shell pm grant --user "$CURRENT_USER" io.pickuptogether.app.demo "$PERMISSION"
done
"$ADB" -s "$SERIAL" reverse tcp:8766 tcp:8766
"$ADB" -s "$SERIAL" forward tcp:8088 tcp:8088
# Open the native car app after the local API is ready. No browser is required.
python3 - "$ADB" "$SERIAL" <<'PY' &
import json,subprocess,sys,time,urllib.request
adb,serial=sys.argv[1:]
for _ in range(40):
    try:
        with urllib.request.urlopen('http://127.0.0.1:8766/api/demo/state',timeout=1) as response:
            state=json.load(response)
        if len(state.get('actors',{}))==2:
            subprocess.run([adb,'-s',serial,'shell','am','start','--user','current','-n','io.pickuptogether.app.demo/io.pickuptogether.app.MainActivity','--es','demoCommand','newTest'],check=True,timeout=15)
            break
    except (OSError,ValueError):
        time.sleep(.3)
PY
exec python3 -u -m together.server.demo --serial "$SERIAL"
