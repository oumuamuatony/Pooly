# 공식 콘솔 연결 — 2026-11-10

공식 콘솔 http://127.0.0.1:8088/ 를 브라우저에서 열어 상태 입력을 시험했습니다. 브라우저의 1초 간격 `/browser/session` heartbeat가 있을 때만 콘솔의 DDS 신호 송신이 활성화되는 것을 확인했습니다. 웹을 열기 전 available=false, 열린 뒤 available=true 및 DDS ready=true가 되었습니다.

Door Position / Front right / Open 입력이 실제 Pleos SDK의 ROW_1_RIGHT 도어 상태와 lastOpenedAt에 도달했습니다. 앱에 모의 이벤트를 넣지 않았습니다.

좌석은 콘솔 Occupied=true일 때 SDK getCurrentSeatOccupancy가 false, Empty=false일 때 true를 반환하는 것을 재현했습니다. 고정 의존성 Vehicle 2.3.2의 getter와 listener bytecode 모두 원래 정수 1만 true로 변환합니다. Android의 좌석 값은 UNKNOWN=0, VACANT=1, OCCUPIED=2입니다. 공개 Boolean API로는 원래 정수 0/2를 구분할 수 없습니다.

현재 검증한 Pleos 에뮬레이터 fingerprint release_connect_v2.1.0 + SDK 2.3.2 조합에만 Boolean 변환을 반전했습니다. null/실패 값은 그대로 unknown으로 유지합니다. 실차·다른 이미지·다른 SDK에는 이 변환을 적용하지 않습니다. raw sdkValue와 mapping을 앱 상태에 남겨 추후 비교할 수 있습니다. 이 호환 처리는 해당 공식 콘솔의 Empty/Occupied 두 상태를 시험하기 위한 것으로, 실차의 알 수 없는 상태를 완전히 해석하는 해결책은 아닙니다.

일반·시연 APK 빌드/기존 설치 업데이트와 기존 승차·알림 정책 테스트가 통과했습니다. 수정 후 값을 다시 읽으려는 시점에 실행 중이던 에뮬레이터 프로세스가 종료돼 adb 연결이 사라졌습니다. **수정 후 공식 콘솔→SDK 정규화→실제 승차 패널까지의 재검증은 미완료**입니다. 이전 단계의 모의 승차 통합 검증과 구분해야 합니다. 이번 작업에서는 새 약속을 만들지 않았고 개인/시연 서버도 재시작하지 않았습니다.

## 사용

`together/scripts/open-vehicle-console.command`를 실행하면 포트 연결을 확인하고 공식 콘솔을 엽니다. 서버나 에뮬레이터를 시작·종료하지 않습니다. `emulator-5554 --no-open` 인수는 브라우저를 열지 않고 연결만 확인합니다. 일반 기기 연결 스크립트에도 에뮬레이터인 경우 8088 포트 연결을 추가했습니다.

앱의 에뮬레이터 테스트 안내를 공식 콘솔 사용 순서로 갱신했습니다. Emulation Controls → Convenience → Seat Occupancy에서 Passenger를 Empty로 놓고, 약속의 현재 픽업 도착/준비 완료 상태에서 해당 Door Position을 Open → 좌석 Occupied → Door Position Close로 바꾼 뒤 약 2초 기다립니다.

공식 콘솔 사용법: https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide
Android 좌석 enum: https://android.googlesource.com/platform/packages/services/Car/+/master/car-lib/src/android/car/VehicleSeatOccupancyState.java
