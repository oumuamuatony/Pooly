# Pooly
현대자동차그룹 차량용 앱 개발 공모전 출품작 Pooly(풀리)

## 프로젝트 구성

| 구성 | 위치 |
| --- | --- |
| Pleos 차량 앱 | [android/PickupTogether](android/PickupTogether/) · Kotlin, Android Studio, Pleos Connect SDK |
| Flask 서버 | [together/server](together/server/) · 일정, 픽업, 위치 공유, 좌석 선호, SQLite |
| 차량 UI 및 모바일 웹 | [together/web](together/web/) · `car.html`은 Android 앱에 번들되며 `phone.html`은 Flask에서 제공 |
| 서버 배포 설정 | [together/deploy](together/deploy/) · PythonAnywhere 및 WSGI |
| 테스트 | [서버](together/tests/) · [Android](android/PickupTogether/app/src/test/) |

## 로컬에서 실행

프로젝트 루트에서 Flask 환경을 준비합니다.

```sh
python3 -m venv together/.venv
./together/.venv/bin/python -m pip install -r together/requirements.txt
./together/scripts/start-dev.sh
```

Android Studio에서 `android/PickupTogether`를 열고 Pleos Connect 에뮬레이터에 앱을 실행합니다. Gradle이 `together/web`을 읽으므로 저장소 폴더 구조를 유지합니다. 로컬 빌드와 에뮬레이터 연결은 [차량 앱 실행 가이드](android/PickupTogether/README.md), 호스팅용 패키지는 [PythonAnywhere 배포 가이드](together/deploy/PYTHONANYWHERE.md)를 참고하세요.

`together/web/car.html`과 `phone.html`은 현재 실행본입니다. 차량 앱 빌드는 저장소의 HTML을 직접 번들합니다. `build_ui.py`는 초기 디자인 템플릿을 재생성할 때 쓰는 개발 도구입니다.

픽업 위치 추천의 GraphSAGE 모델은 기획 단계입니다.
