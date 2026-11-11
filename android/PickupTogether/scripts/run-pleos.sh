#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
AVD_NAME="Pleos_Connect_2_1"
ADB="$SDK_DIR/platform-tools/adb"
EMULATOR="$SDK_DIR/emulator/emulator"
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
if ! "$EMULATOR" -list-avds | /usr/bin/grep -Fxq "$AVD_NAME"; then
  echo "Pleos 가상 기기가 없습니다. Pleos SDK와 가상 기기 설치를 먼저 완료해 주세요."
  exit 1
fi

SERIAL=""
EMULATOR_PID=""
while IFS=$'\t' read -r device state; do
  if [[ "$device" == emulator-* ]] && [[ "$state" == device ]]; then
    if "$ADB" -s "$device" emu avd name 2>/dev/null | /usr/bin/grep -Fxq "$AVD_NAME"; then
      SERIAL="$device"
      break
    fi
  fi
done < <("$ADB" devices)

if [ -z "$SERIAL" ]; then
  mkdir -p "$PROJECT_DIR/.run"
  PORT=5554
  while /usr/sbin/lsof -iTCP:"$PORT" -sTCP:LISTEN -t >/dev/null 2>&1 || \
        /usr/sbin/lsof -iTCP:"$((PORT + 1))" -sTCP:LISTEN -t >/dev/null 2>&1; do
    PORT=$((PORT + 2))
  done
  SERIAL="emulator-$PORT"
  nohup "$EMULATOR" -avd "$AVD_NAME" -port "$PORT" -no-snapshot \
    -memory 4096 -cores 4 -gpu host \
    > "$PROJECT_DIR/.run/emulator.log" 2>&1 </dev/null &
  EMULATOR_PID=$!
  echo "Pleos 에뮬레이터를 시작했습니다. 부팅을 기다리고 있습니다."
fi

BOOTED=0
for _ in {1..150}; do
  if [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    BOOTED=1
    break
  fi
  sleep 2
done
if [ "$BOOTED" != 1 ]; then
  echo "부팅 확인 시간이 초과됐습니다. .run/emulator.log를 확인해 주세요."
  exit 1
fi

cd "$PROJECT_DIR"
./gradlew --no-daemon :app:assembleDebug
"$ADB" -s "$SERIAL" install -r "$PROJECT_DIR/../../together/artifacts/together-dev.apk"
"$ADB" -s "$SERIAL" reverse tcp:8765 tcp:8765
"$ADB" -s "$SERIAL" shell am start -n io.pickuptogether.app/.MainActivity --es apiUrl http://127.0.0.1:8765
"$ADB" -s "$SERIAL" forward tcp:8088 tcp:8088
echo "앱 실행 완료 ($SERIAL)"
echo "Virtual Console: http://127.0.0.1:8088/"
if [ -n "$EMULATOR_PID" ]; then
  echo "에뮬레이터 창을 닫으면 이 실행 작업도 끝납니다."
  wait "$EMULATOR_PID"
fi
