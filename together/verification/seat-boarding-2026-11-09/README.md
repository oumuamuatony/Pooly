# 좌석 기반 승차 패널 검증

## 구현

Pleos Vehicle 2.3.2의 `checkSeatOccupancyCapabilities`, `getCurrentSeatOccupancy`, `registerSeatOccupancy`와 도어 구독/읽기를 연결했습니다. 운전석 위치는 Android CarInfoManager에서 읽습니다. SDK는 읽기만 사용하며 좌석·문을 제어하지 않습니다. 안내 중에는 GPS 공유와 독립적인 기존 안내 서비스에서 센서 상태를 주기적으로 갱신합니다.

현재 픽업 도착 상태 + 수락한 일행의 준비 완료/대기 + 현재 내비 연결 + 정차 상태에서만 자동 탑승합니다. 도착 후 한 동승자 좌석의 빈 상태→점유 변화가 1.5초 안정적으로 유지되고, 해당 문 열림→닫힘 변화가 함께 있어야 합니다. 처음부터 점유된 좌석, 오래된 신호, 이동 중, 운전석/운전석 정보 없음, 여러 좌석의 동시 변화는 제외합니다. 센서만으로 신원을 아는 기능은 아니고 도착 상태와 픽업 순서로 연결합니다.

기존 서버 탑승 API 성공 후 다음 남은 경유지로 내비를 다시 요청하고 7초 유리 패널을 표시합니다. 마지막 동승자는 목적지 이름을 표시하며 전화 버튼을 숨깁니다. 수동 탑승 확인도 유지합니다.

## 검증 결과 및 범위

- 실제 Pleos 에뮬레이터에서 점유 지원 상태 connected, 운전석 ROW_1_LEFT, 다섯 좌석 점유 상태와 문 상태 수신 확인: sdk-state.json.
- 정책 테스트 통과: startup/driver/motion/unknown capability/stale/ambiguous guards, 안정화 시간, 중복 방지, 다음 픽업에 동일 좌석 변화 재사용 방지, 다음 일행/목적지 문구.
- 기존 알림 정책 테스트도 통과.
- 실행 중인 시연 앱의 이벤트 경계에 **모의 좌석 및 도어 변화** 입력. 실제 센서 변화가 들어온 것으로 주장하지 않습니다.
- 시연 서버에 임시 일정 생성 후 실제 API 탑승 처리 확인. Alex는 index 0→1, Sam은 1→2, 각각 한 번만 탑승 기록.
- Alex 승차 후 Sam 한 명만 남은 실제 내비 경유지 전달 확인: native-next-route.json. Sam 승차 후 destination 상태 확인.
- 내비 위 실제 유리 패널: Alex 님 승차 완료 / Sam 님에게 이동 중; Sam 님 승차 완료 / 서울숲으로 이동 중. 캡처는 boarding-first.png, boarding-final.png.
- 최종 패널이 자동으로 닫히는 것을 확인. 정책/내비/UI 통합 검증 뒤 마지막 UI 보완으로 목적지 구간의 불필요한 전화 버튼을 숨김. 최종 APK 파일과 소스의 바이트 일치 확인.
- 일반 및 시연 APK 빌드/설치 완료. 서버 재시작과 개인 데이터 초기화 없음. 임시 일정 세 개는 취소했고 기존 [시연] 친구들과 서울숲 여정으로 복귀. Alex/Sam은 시연 서버의 테스트 친구로 남겨 둠.

## 제한

에뮬레이터 CarService 입력을 시도했으나 SDK의 좌석 값에 반영되지 않았습니다. 입력값은 원래 값으로 되돌리는 요청을 보냈고 해당 임시 포워딩을 제거했습니다. 따라서 실제 좌석 센서 변화의 끝까지 검증과 실차 정확도는 미검증입니다. 앱 내부 판정부터 서버 저장·내비 변경·패널까지는 모의 이벤트로 검증했습니다. 모의 이벤트를 발생시키는 자동 타이머나 운영용 호출은 앱에 추가하지 않았습니다.

공식 문서: https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-occupancy

검증 명령: `node together/verification/seat-boarding-2026-11-09/test-boarding.cjs`
