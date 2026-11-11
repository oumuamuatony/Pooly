# 사용자 디자인 적용 및 실제 Pleos 패널 검증 — 2026-11-09

보내준 4개 화면의 배치와 크기를 기준으로 기존 차량 앱을 수정했다. 기존 상세·초대·기록 흐름은 유지하고, 홈·일정 생성·이동 화면을 교체했다. 일반 앱과 시연 앱 모두 데이터 삭제 없이 업데이트 설치했다.

## 디자인 적용

- 1194 × 834 가로 화면을 기준으로 일정 영역과 친구 목록을 813:381 비율로 배치.
- 일정 없는 홈에 겹친 프로필 사진, 안내 문구, 현대 네이비 버튼 적용.
- 일정 카드의 사각 테두리, 검정 출발 버튼, 여정 확인 버튼 적용.
- Freesentation 400·500·600·700과 제공된 예시 프로필 원본 사용. 폰트 OFL 파일을 APK에 포함.
- 생성 패널은 405 × 612, 이동 패널은 497 × 175 기준. 실제 화면 크기에 맞춰 확대.
- 도착지 확인·변경 행과 닫기 버튼을 추가. 초록 테두리는 픽업 대상으로 선택한 일행을 뜻하며, 이름을 누르면 미리보기 대상을 바꾼다.

## 실제 내비 위 표시 방식

`NavigationPanelWindow`가 기존 WebView를 Android `TYPE_APPLICATION_OVERLAY` 창으로 옮긴다. 동일한 로그인·작성 중인 폼을 유지하며 별도 테스트 페이지를 띄우지 않는다. 내비 화면은 실제 OEM 앱이다. 패널 창 밖의 지도 입력은 그대로 내비에 전달된다. 패널을 닫거나 저장하면 WebView를 원래 앱으로 돌려놓는다.

- 생성 패널은 주차 상태에서만 열 수 있다. 차량 제한이 활성화되면 생성 패널을 닫는다.
- 실제 주행에서는 시안의 좌상단 위치가 회전 안내를 가렸다. 이동 패널만 회전 안내 아래로 내렸다.
- 기본 WebView 날짜 팝업이 숨겨진 Activity에 붙어 보이지 않는 현상을 확인했다. 패널 안에서 그리는 달력·시각 조절로 교체했고, 실제 화면 터치로 날짜와 분을 변경한 뒤 서버 저장을 확인했다.
- 내비 경유지 응답의 좌표가 0인 경우를 확인했다. 앱이 요청한 경로의 목적지 확인이 끝난 상태에서 경유지 개수·순서와 이름 또는 좌표가 일치할 때만 참가자 ID를 연결한다. 남은 시간은 해당 경유지의 SDK 응답을 사용한다. 신선한 응답이 없으면 ‘확인 중’을 표시한다.
- 전화 버튼은 `ACTION_DIAL`로 차량 전화 앱을 연다. 예시 멤버에게 전화번호가 없어 전화 앱 실행까지만 검증했으며 실제 통화는 하지 않았다.
- 픽업 미리보기에는 참가자가 공유한 장소를 OpenFreeMap으로 표시한다. 공유 전에는 안내 문구를 표시한다. OEM 내비 배경 지도 복구와는 별개다.

## 검증 범위

현재 실행 중인 `Pleos_Connect_2_1` / `emulator-5554`, Pleos 2.1.0 ARM64에서 검증했다. 임시 검증 앱·별도 로컬 DB에 Max/Kelly/Jeff를 만들고 선택·생성·초대 수락·출발을 시험했다. 이후 임시 앱·서버·DB·포트 연결을 제거했다. 별도 브라우저 데모로 검증한 것이 아니다.

최종 시연 앱에서도 생성 패널·닫기·이동 패널·SDK 남은 시간·기존 에뮬레이터 테스트 메뉴를 확인했다. 마지막에는 시연 앱의 이동 패널을 OEM 내비 위에 남겼다. 기존 시연 약속은 `near`, 재생 중지는 그대로 유지했다. 개인 서버 8765와 시연 서버 8766–8768은 재시작하지 않았다.

세부 결과는 `workflow-checks.json`, 패키지/원본 파일 일치와 서버 상태는 `verification.json`, 빌드는 `build.log`에 기록했다. 실제 화면은 이 폴더의 PNG 파일을 참고한다. `06-moving-native-panel.png`는 회전 안내와의 겹침을 찾은 수정 전 시험 화면이고 `07-moving-native-final.png`가 수정 후 화면이다. `03-date-picker.png`는 기본 날짜 창이 열리지 않던 수정 전 기록이다.

## 남아 있는 제한

- 이 Pleos 이미지에는 다른 앱 위에 표시 권한을 켜는 설정 Activity가 없다. 에뮬레이터의 일반·시연 앱에 ADB로 `SYSTEM_ALERT_WINDOW` 권한을 설정해 검증했다. 권한이 없는 환경에서는 설정 화면이 있으면 안내하고, 없으면 설치 단계의 표시 권한이 필요하다는 메시지를 표시한다.
- 이는 Android 오버레이 구현이다. Pleos 공식 내비 위젯 기능이 지원된다는 뜻이나 실차·App Market 승인 결과가 아니다.
- 기존 OEM 내비 배경 지도 미표시와 위치 고정은 해결하지 않았다. 따라서 실제 이동에 따른 패널 갱신과 도로 지도 위의 반투명 패널 대비까지 검증한 것은 아니다.
- 개발용 서버·기존 시연 데이터는 유지했다. 새 디자인이 적용된 APK는 `together/artifacts/together-dev.apk`, `together/artifacts/together-demo.apk`이다.

## 근거 문서

- Android overlay window: https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
- Android overlay permission: https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION
- Freesentation official distribution: https://github.com/Freesentation/freesentation

원본 디자인 이미지는 `together/design/user-layout-2026-11-09/`, 변경 전 주요 화면 백업은 `backups/before-user-layout-20261109-173924.zip`에 보관했다.
