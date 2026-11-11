# Pleos 내장 내비 재조사 — 2026-11-09

## 현재 결론

배경 지도 미표시와 내비 위치 고정을 재현했다. 배경 지도는 아직 복구되지 않았다. GPS 수신 자체의 실패나 Pooly의 경로 전달 실패 하나로 설명되는 현상은 아니다. 같은 시스템 이미지의 재설치보다는 내장 내비의 지도 표시 및 위치 반영 경로에 대한 공급자 확인이 우선이다. 새 가상 기기와 Android Emulator 37.2.13에서도 지도 미표시를 재현했다. 정확한 내부 원인과 해결 방법은 아직 확인하지 못했다.

## 실행 환경

- Mac Apple M4, 메모리 16 GiB.
- Pleos Connect Emulator 2.1.0 ARM64, Android API 34, 공식 시스템 이미지 revision 2.
- 기존 Android Emulator 실행 프로그램 36.4.10.
- 내장 내비 `ai.umos.maps.android.navigation.app`, `3.3.2-rc5-hotfix.3`.
- 원래 가상 기기 `Pleos_Connect_2_1`, 기존 연결 `emulator-5554`.

## 1. 재설치·초기화가 필요한 손상인지

- 공식 ARM64 이미지 저장소의 최신 revision도 2였다.
- 전체 압축 파일을 다시 다운로드하지 않고 공식 ZIP의 중앙 디렉터리를 HTTPS Range 요청으로 읽었다. 각 배포 파일의 크기·CRC32를 현재 설치 파일 전체와 비교했으며 모두 일치했다. `image-integrity.json` 참고.
- 같은 시스템 이미지로 별도의 새 가상 기기를 만들었다. Pooly 패키지가 설치되지 않은 초기 상태에서도 배경 지도는 비었다. 인증과 내비 엔진·지도 초기화 완료 신호 이후에도 동일했다.
- 따라서 같은 Pleos 시스템 이미지를 재설치하거나 Pooly 데이터를 지우는 조치는 우선순위가 낮다. 공급자 내부 결함까지 확정한 것은 아니다.
- 비교용 가상 기기는 종료·삭제했으며 기존 가상 기기는 보존했다.
- 화면: `existing-before.png`, `clean-after-ready.png`.

## 2. GPS와 내비 위치를 분리한 시험

처음 GPS 주입 시험에는 시연 서버의 주기적인 좌표 재주입이 간섭했다. `gps-separation-test.json`은 이 간섭이 있는 시험이므로 위치 입력 실패의 근거로 사용하지 않는다.

서버를 재시작하지 않고 같은 가상 기기를 일시적으로 `emulator-5558`로 실행해 시연 서버의 `emulator-5554` 대상 주입과 분리했다. 시연 앱의 자동 이동·친구 응답은 일시적으로 비활성화하고 원래 설정 값을 별도로 보관했다.

- `geo fix`로 서울시청 좌표를 주입하자 Android GPS와 Pleos FusedLocation이 실제로 해당 좌표로 바뀌었다.
- 올바른 체크섬의 GGA/RMC를 정수 초 형식으로 생성해 연속 주입했다. Android NMEA 리스너의 유효 문장 수 증가와 실제 문장 수신을 확인했다.
- FusedLocation 좌표와 타임스탬프는 갱신됐으나, NaviHelper 조회 결과는 새 응답 시각에도 `37.4127212, 127.094681`에 고정됐다.
- `SAFE_GUIDING`과 실제 `ROUTE_GUIDING`에서 모두 관찰했다.
- 기본 `geo fix`가 발생시키는 NMEA에는 `*47` 고정 검사값 오류가 반복됐다. 그러나 정상 NMEA를 따로 전달한 뒤에도 내비 위치가 움직이지 않았으므로 이 오류만을 최종 원인으로 단정하지 않는다.
- 근거: `isolated-gps-samples.json`, `nmea-correct-input.json`.

## 3. 실제 D단·속도 입력까지 검증

- Virtual Console의 브라우저 세션이 비활성일 때 기어·속도 쓰기 요청은 HTTP 503 `emulator_inactive`로 거절됐다.
- Console 세션을 활성화하면 DDS bridge와 속도·기어 전달이 준비됐다.
- D단과 20km/h를 입력해 Console의 실제 기어 응답 및 계기판 표시를 확인했다.
- 활성 경로 안내 중 올바른 NMEA를 주입하고 시작 좌표 근처에서 위치를 이동했다. FusedLocation은 이동했으나 내비 좌표는 그대로였고 배경 지도도 비었다.
- 따라서 P단·0km/h만 시험해서 생긴 결과는 아니다.
- 근거: `driving-gps-samples.json`, `driving-with-valid-gps.png`.

## 4. 지도 리소스와 렌더링

- 기존·새 가상 기기 모두 네트워크 및 내비 초기화가 동작했다.
- 로그에서 Mapbox 프레임 렌더링과 일부 OpenGL 오류가 관찰됐다. 오류 로그만으로 원인을 확정하지 않는다.
- 현재 기본 스타일 `connect_empty`는 해안선·배경·슬롯 레이어를 제공했다. 실행 중 동적 레이어를 추가하는 설계일 수 있으므로 이 JSON만으로 지도 서버 오류를 확정하지 않는다.
- 충전 사업자 API의 인증 오류도 관찰됐으나 배경 지도 미표시와의 인과관계는 확인하지 못했다.
- 근거: `style-current.json`, 마스킹된 기존·초기 상태 내비 로그.

## 5. 최신 Android Emulator와 그래픽 방식 비교

기존 SDK 실행 프로그램을 덮어쓰지 않고 Google 공식 ARM64 배포본 37.2.13을 별도 위치에 받아 시험했다. 원본 시스템 이미지로 별도 가상 기기를 만들었으며 Pooly 앱이 설치되지 않은 상태였다.

- 37.2.13 + `host`: 내비 초기화 완료 후에도 배경 지도는 비었다. 일부 OpenGL 오류가 기록됐다.
- 37.2.13 + `software`: 내비 초기화 완료 신호는 발생했으나 지도 영역이 검게 표시됐다. 차량 모델도 정상 질감이 표시되지 않았다. 이 방식은 대안이 되지 못했다.
- 초기화 완료 신호, 지도 배경의 실제 표시, GPS 위치 갱신은 별개로 평가했다. 그래픽 오류와 내비 위치 고정 사이의 인과관계는 확인하지 못했다.
- 37.2.13에서 GPS 분리 시험 전체를 반복한 것은 아니다. 이 비교는 지도 표시 시험이다.
- 근거: `new-runtime-source.json`, `new-runtime-host.json`, `new-runtime-software.json`, `emulator-37-host-map.png`, `emulator-37-software-map.png`.

## 6. 원래 환경 복원

- 시험용 가상 기기와 별도 다운로드한 37.2.13 실행 프로그램을 종료·제거했다. 원래 Pleos 가상 기기와 SDK는 보존했다.
- 원래 가상 기기를 기존 Android Emulator 36.4.10, `host` 그래픽, `emulator-5554`로 다시 실행했다.
- 시험 중 설정한 D단·속도는 P단·0km/h로 돌렸고 Console 진단 세션을 종료했다.
- 시연 앱의 자동 이동·친구 응답·일시정지 설정은 시험 전 값으로 복원했다. 시연 약속은 초기화하지 않았다.
- 개인 서버(8765)와 시연 서버(8766–8768)는 재시작하지 않았다.
- 기존 차량 앱과 서버 연결용 포트 전달을 복원했다. 최종 상태는 `restored-environment.json` 참고.

## 공급자에게 확인할 항목

1. Pleos Connect 2.1.0 ARM64 이미지 revision 2의 내장 내비 `3.3.2-rc5-hotfix.3`에서 도로 배경 지도가 표시되는 것이 지원되는지, 추가 지도 데이터·권한·환경 설정이 필요한지.
2. Android/Pleos FusedLocation은 갱신되지만 NaviHelper가 `37.4127212, 127.094681`에 고정되는 상황에서 내장 내비에 위치를 반영하는 공식 시험 절차가 무엇인지.
3. 올바른 NMEA, 실제 D단·20km/h, 활성 경로 안내를 모두 적용해도 내비 위치가 고정되는 현상이 알려진 문제인지.

다른 개발자의 공개 문의는 유사 현상의 참고 근거이며 Pleos 측이 결함을 확인했다는 뜻은 아니다. 이 조사 과정에서 외부 문의나 메시지를 새로 게시하지 않았다.

## 재현을 위한 유의점

1. 시연 서버의 주기적 위치 주입과 별도의 GPS 시험을 동시에 실행하지 않는다.
2. NMEA는 체크섬을 계산하고, 이 이미지에서 확인된 HAL 파서 호환성을 위해 시각을 정수 초로 보낸다.
3. Console 연결이 활성인지, 기어·속도 쓰기 요청이 성공했는지 확인한다. HTTP 요청을 보냈다는 사실과 실제 적용은 구분한다.
4. FusedLocation의 이동, NaviHelper의 위치 갱신, 지도 배경 렌더링을 각각 확인한다.

## 관련 공식 자료와 공개 문의

- [Pleos Emulator 설치](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [Pleos Emulator 2.1.0 릴리스](https://document.pleos.ai/en/docs/pleos-only/release-note/emulator-release-note)
- [Virtual Console 설정](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)
- [Android Emulator 릴리스](https://developer.android.com/studio/releases/emulator)
- [기본 내비 위치 고정 관련 다른 개발자의 문의 #32](https://github.com/Pleos-Playground/pleos-playground-api/discussions/32): 현재 공식 답변 없음.
- [NaviHelper 모의주행 관련 문의 #29](https://github.com/Pleos-Playground/pleos-playground-api/discussions/29): 다른 개발자가 Windows에서도 유사한 위치 고정을 보고. 공식 지원 범위의 확답은 아님.

외부 문의용으로는 이 문서와 테스트 JSON·화면을 사용한다. 계정 토큰이나 전체 SharedPreferences를 첨부하지 않는다.
