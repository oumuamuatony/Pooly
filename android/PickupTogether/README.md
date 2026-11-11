# 함께가기 · Pleos / Android

완성된 차량·휴대폰 디자인에 약속 저장, 초대, 픽업 상태, 위치 공유, 메시지와 주행 기록을 연결한 개발 버전입니다. 화면은 제공된 HTML을 그대로 활용하고 기기 기능은 Kotlin으로 연결합니다.

실행 순서와 구현 범위는 [함께가기 개발 문서](../../together/README.md)를 참고하세요.

1. 프로젝트 루트에서 `./together/scripts/start-dev.sh`로 개발용 서버를 시작합니다.
2. Android Studio에서 이 `PickupTogether` 폴더를 열고 JDK 21로 동기화합니다.
3. `Pleos_Connect_2_1`을 선택해 실행하거나 `./scripts/run-pleos.sh`를 실행합니다.

- Kotlin 2.1.0 / AGP 8.7.3 / Gradle 8.12
- 최소 API 28 / 컴파일 API 35 / 대상 API 34
- Vehicle SDK 2.3.2 / NaviHelper SDK 3.0.5
- FusedLocation 1.1.3 / TextToSpeech·SpeechToText 3.3.1 / Gleo Interface
- Androidx WebKit 1.12.1, origin을 한정한 웹 메시지 연결
- Pleos Connect 2.1.0 ARM64 AVD, 4GB RAM, 스냅샷 사용 안 함
- `MainActivity`: 공유 화면과 기기 기능
- `JourneyService`: 동의한 이동의 위치 공유와 활성 이동 알림
- `PleosNavigation`: 차량 내비 요청, ETA와 안내 복원
- `PleosLocation`, `PleosVoice`, `PleosVehicle`: 차량 위치·음성·차량 정보
- `GleoActivity`, `GleoContract`, `assets/schema.json`: Gleo 명령·입력 검증·콜백
- `SdkCheckActivity`: 이전 Vehicle SDK 읽기 진단 화면 보관

결과 APK는 `../../together/artifacts/together-dev.apk`입니다. `local.properties`는 이 맥북의 SDK 경로만 담고 있으며 계정 비밀번호나 Playground 토큰은 소스에 저장하지 않습니다.

Virtual Console은 실행 스크립트가 연결한 <http://127.0.0.1:8088/>에서 열 수 있습니다. 이는 가상 차량의 상태를 바꾸는 개발 도구이며 실제 차량을 제어하지 않습니다. 약속 생성·수정은 가상 차량을 P에 놓고 진행합니다.

빌드 중간 파일은 동기화 폴더의 중복 생성 문제를 피하도록 `~/.gradle/together-build/`에 저장하고, 설치할 APK만 `together/artifacts/together-dev.apk`로 내보냅니다.
