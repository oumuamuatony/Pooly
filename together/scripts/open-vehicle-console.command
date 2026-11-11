#!/bin/bash
set -euo pipefail
ADB="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/adb"
SERIAL="${1:-emulator-5554}"
if [[ "$SERIAL" != emulator-* ]]; then
  echo "공식 차량 콘솔은 Pleos 에뮬레이터에서 사용해 주세요."
  exit 1
fi
"$ADB" -s "$SERIAL" get-state >/dev/null
"$ADB" -s "$SERIAL" forward tcp:8088 tcp:8088 >/dev/null
if ! curl -fsS --max-time 5 http://127.0.0.1:8088/vehicle/catalog >/dev/null; then
  echo "Pleos 에뮬레이터 2.1 이상이 실행 중인지 확인해 주세요. 서버나 에뮬레이터를 재시작하지 않았어요."
  exit 1
fi
printf '%s\n' '공식 콘솔: http://127.0.0.1:8088/' '브라우저에서 콘솔을 열어 두어야 신호가 활성화돼요.' 'Emulation Controls → Convenience → Door Position / Seat Occupancy'
if [[ "${2:-}" != "--no-open" ]]; then
  open 'http://127.0.0.1:8088/'
fi
