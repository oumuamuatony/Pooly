# Pleos 내장 내비 배경 지도 미표시 조사

확인일: 2026-11-08. Pleos Connect 2.1.0 ARM64, Android API 34, Mac Apple M4. 내장 앱 `ai.umos.maps.android.navigation.app` 버전 `3.3.2-rc5-hotfix.3`.

## 확인한 사실

1. 경로선·차량 마커·회전 안내·UI는 그려지지만 도로·건물 배경은 비어 있다.
2. 함께가기의 경로를 취소하고 내장 내비만 다시 실행해도 같은 현상이 발생한다. 재현 화면: `oem-map-without-pickup-route.png`.
3. 에뮬레이터 인터넷은 Android에서 VALIDATED 상태다. 내비 경로 서버도 응답하며, OpenGL 렌더러는 Apple M4로 동작하고 Mapbox가 프레임을 그리는 로그가 있다.
4. 내비 시작 로그의 `readySSLCertificationFlow`가 아래 스타일 URL을 표시한다.

   `https://a.tile.static.maps-coral.umos.ai/styles/42dot/connect_empty`

5. 해당 URL은 인증 정보를 추가하지 않은 읽기 요청에 HTTP 200 JSON을 응답한다. `sources`는 `coastline` 하나, `layers`는 다음 네 개이며 `imports`는 없다.

   | layer id | type |
   | --- | --- |
   | 바탕 | background |
   | coastline | fill |
   | driving_carpet | slot |
   | driving_carvatar | slot |

6. 이 기본 스타일 응답에는 도로·건물 레이어가 없다. 기본 스타일과 후속 지도 레이어가 구성되는 내장 내비 쪽을 확인해야 한다. 스타일 이름만 보고 단정한 것이 아니라 실제 JSON 응답을 확인했다.

## 판정과 한계

함께가기 앱의 경로 전달 실패나 일반적인 인터넷 단절만으로 설명되는 증상은 아니다. 내장 내비가 사용하는 기본 지도 구성과 추가 지도 리소스 경로로 원인을 좁혔다. 내장 앱이 별도로 동적 레이어를 추가해야 하는 설계인지, 개발 이미지가 이 스타일을 의도적으로 사용하는지까지는 공개 문서와 현재 로그로 확정하지 못했다.

현재 공개 NaviHelper의 경로·화면 요청 API에서 OEM 지도 스타일을 바꾸는 방법은 확인하지 못했다. 따라서 배경 지도를 복구했다고 보고하지 않는다. 시스템 앱을 수정하거나 인증서 검증을 끄거나 임의의 서버 주소로 지도 요청을 우회하지 않았다.

계기판 속도·기어, 내장 내비 위치가 실제로 이동하는 문제는 별도다. 실제 FusedLocation 좌표가 변한다는 사실을 내장 내비 마커 이동의 증거로 사용하지 않는다.

## 근거 파일

- `oem-map-diagnosis.json`: URL·HTTP 상태·레이어 요약
- `oem-map-style.json`: 확인한 지도 스타일 응답
- `oem-map-without-pickup-route.png`: 함께가기 경로 없이도 배경이 비는 내비 화면
- `in-emulator-tests.txt`, `in-emulator-result.json`: 차량 앱 내부 테스트 기능의 별도 검증

업체 문의가 필요하다면 위 환경·버전·재현 화면·레이어 요약을 제공하면 된다. 외부에 문의를 전송하지는 않았다.


## 추가 복구 시도 (2026-11-08 오후)

**현재 상태: 미해결. 배경 지도를 복구했다고 보고하지 않는다.**

- 내장 내비 앱만 강제 종료 후 저장 데이터를 유지한 채 다시 실행해 재현했다.
- 시작 로그에서 게이트웨이 응답 200, 시계 차이 검사 통과, AuthComplete 및 MapComplete를 확인했다. 이 상태 값은 화면의 모든 지도 요소가 정상적으로 그려졌음을 보증하지 않는다.
- 지도 SDK는 OpenGL ES 3 컨텍스트 생성에 성공했고, 별도로 `glDeleteProgram` 등의 GL 0x501 오류를 기록했다. 오류 로그만으로 배경 미표시의 원인이라고 단정하지 않는다.
- 같은 AVD를 `-gpu swiftshader`로 실행해 비교했다. 배경 지도가 복구되지 않았고 지도·차량 표면이 검게 나타나는 현상도 있어 원래 `-gpu host`로 복원했다.
- 공식 ARM64 이미지 저장소의 현재 revision과 설치된 revision은 모두 2이다. 다른 이미지나 다른 내비 APK를 임의 설치하지 않았다.
- 내장 내비의 지도 방향/시점 전환을 확인한 뒤 기존 Mode2D로 돌려놓았다. 접근 가능한 내비 설정 및 접근성 제어에서도 배경 지도 복구 항목을 확인하지 못했다.
- 현재 기본 스타일 응답은 앞서 확인한 4개 레이어와 같다. 단, Mapbox는 실행 중 레이어를 추가할 수 있고 내장 앱도 별도 지도 제공기 콜백을 등록한다. 따라서 기본 스타일 JSON에 도로 레이어가 없다는 사실만으로 서버 오류나 최종 원인을 확정할 수 없다.
- NaviHelper에서 지도 스타일이나 내장 지도 제공기를 직접 변경할 수 있는 공개 기능은 현재 확보한 SDK/문서에서 확인하지 못했다. 현재 환경에서 앱 코드로 복구할 방법을 찾지 못해 내장 내비/에뮬레이터 공급자 확인이 필요하다.

에뮬레이터는 원래 그래픽 모드로 실행 중이며 앱과 데이터는 유지했다. ADB 연결도 복구했다. 개인 약속 서버(8765, PID 44492)와 시연 서버(8766~8768, PID 53276)는 재시작하지 않았다. 진단용 임시 테스트 코드는 제거했다.

추가 근거: `map-recovery-session.json`, `oem-map-host-render-log.txt`, `oem-map-software-render-log.txt`, `oem-map-software.png`, `oem-map-controls-inspected.json`.

참고한 공식 문서:
- [Android Emulator 그래픽 문제 해결](https://developer.android.com/studio/run/emulator-troubleshooting)
- [Pleos 에뮬레이터 설치](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [Mapbox 실행 중 레이어 구성](https://docs.mapbox.com/android/maps/guides/styles/work-with-layers/)
