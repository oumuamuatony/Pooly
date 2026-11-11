# Pooly Flask 서버 배포

현재 단계는 웹·차량 프로토타입을 연결하는 Flask API입니다. PythonAnywhere 계정에는 아직 배포하지 않았습니다.
로컬 차량·웹 연동은 `./together/scripts/start-dev.sh`로 실행하며, 8765에서 기존 `together.sqlite3`를 사용합니다. 아래의 8769 명령은 별도 DB를 쓰는 테스트용입니다.

## 로컬 실행

프로젝트 최상위 폴더에서 Python 3.11 이상을 사용합니다.

```sh
python3 -m venv together/.venv
./together/.venv/bin/python -m pip install -r together/requirements.txt
POOLY_DATABASE_PATH=/tmp/pooly-flask-demo.sqlite3 ./together/.venv/bin/python -m flask --app together.server.flask_app:create_app run --host 127.0.0.1 --port 8769 --no-reload
```

다른 프로세스가 포트를 사용 중이면 해당 프로세스를 재시작하지 말고 빈 포트를 사용합니다.

- 예시 데이터 웹: `http://127.0.0.1:8769/phone.html`
- 차량 UI: `http://127.0.0.1:8769/car.html`
- 실제 초대: 같은 서버의 `phone.html?invite=<토큰>`
- 상태 확인: `/health` (`framework=flask`, `max_pickups=2`)

기본 웹 미리보기는 localStorage 예시 데이터입니다. 실제 서버 연동은 차량에서 만든 초대 링크로 확인합니다.

## PythonAnywhere 업로드

1. `artifacts/pooly-pythonanywhere.zip`을 업로드합니다. Bash 콘솔에서 `/home/USERNAME/pooly`에 압축을 풉니다. 이 폴더 바로 아래 `together/server/flask_app.py`가 있어야 합니다.
2. 가상환경을 만듭니다. Web 탭에서 선택하는 Python 버전과 동일하게 맞춥니다. 아래 3.13은 예시이며 계정에서 제공하는 버전을 사용합니다.

```sh
mkvirtualenv --python=/usr/bin/python3.13 pooly
cd /home/USERNAME/pooly
pip install -r together/requirements.txt
```

3. Web 탭 → Add a new web app → Manual configuration → 가상환경과 동일한 Python 버전을 선택합니다.
4. Virtualenv에 `/home/USERNAME/.virtualenvs/pooly`를 입력합니다.
5. Web 탭의 WSGI configuration file을 열고 `together/deploy/pythonanywhere_wsgi.py`의 내용을 붙여넣습니다. `USERNAME` 두 곳을 실제 계정명으로 수정하고 프로젝트 경로도 확인합니다. EU 계정이나 커스텀 도메인을 사용하면 `POOLY_PUBLIC_ORIGIN`도 실제 HTTPS 주소로 수정합니다.
6. Web 탭의 Reload를 누릅니다. 이 동작은 PythonAnywhere 웹 앱에만 해당합니다.
7. `https://USERNAME.pythonanywhere.com/health`와 `/phone.html`을 확인합니다. 웹과 API를 같은 도메인에 두므로 별도 CORS 설정은 필요하지 않습니다.

PythonAnywhere에서는 콘솔에서 Flask 개발 서버를 띄우는 방식이 아니라 WSGI의 `application`으로 실행합니다. 모듈 import 시 개발 서버나 백그라운드 스레드는 실행되지 않습니다.

공식 가이드: https://help.pythonanywhere.com/pages/Flask/

## 설정

| 환경변수 | 값 |
|---|---|
| `POOLY_DATABASE_PATH` | DB 절대 경로. 소스 배포 시 덮어쓰거나 삭제하지 않습니다. |
| `POOLY_PUBLIC_ORIGIN` | 실제 HTTPS 원점. 예: `https://name.pythonanywhere.com` |
| `POOLY_SQLITE_JOURNAL_MODE` | WSGI 기본값 `DELETE`. 로컬 기본 HTTP 서버는 기존 `WAL` 유지. |
| `VWORLD_API_KEY` | 브이월드 검색 API 키. 장소명과 도로명/지번주소 검색. |
| `VWORLD_DOMAIN` | 키에 등록한 실제 서비스 URL. 로컬 개발과 배포 도메인을 구분합니다. |
| `POOLY_SEARCH_CONFIG` | 선택. 서버 전용 JSON 설정 절대 경로. 기본 together/data/private/search-provider.json. |
| `KAKAO_REST_API_KEY` | 브이월드 키가 없을 때 사용할 선택 검색 제공자. |
| `TOGETHER_ROUTE_PROVIDER` | 기본 `estimate`. 차량 안내는 Pleos 내비에서 처리합니다. |

키는 WSGI 서버 환경에만 넣습니다. 프런트엔드, Git, 배포 ZIP에 넣지 않습니다.
장소 검색 제공자에 대한 외부 통신 허용 여부는 PythonAnywhere 계정에서 확인해야 하며, 이 작업에서는 호스팅 환경의 브이월드 외부 통신까지 검증하지 않았습니다.

## API와 데이터

기존 요청 주소를 유지합니다. Android/Pleos 앱은 서버 기본 주소를 새 HTTPS 도메인으로 바꿔야 합니다. 아직 설치된 APK의 서버 주소는 변경하지 않았습니다.

| 요청 | 기능 |
|---|---|
| `POST /api/sessions` | 개발용 기기 프로필·Bearer 토큰 생성 |
| `GET /api/state` | 내 약속·친구·알림 |
| `POST /api/contacts` | 내 친구 추가 |
| `POST /api/appointments` | 일정 생성, 픽업 대상 최대 2명 |
| `PATCH /api/appointments/<id>` | 일정 및 운전자가 지정한 픽업 대상 변경 |
| `GET /api/invites/<token>` | 초대 확인 |
| `POST /api/invites/<token>/join` | 참가 및 운전 여부 저장 |
| `POST /api/appointments/<id>/member` | 내 픽업 위치·준비 상태·공유 동의·탑승 확인 |
| `POST /api/appointments/<id>/actions` | 운전자의 출발·도착·승차 확인·종료 |
| `POST /api/appointments/<id>/location` | 동의한 이동의 위치 갱신 |
| `POST /api/appointments/<id>/messages` | 약속 일행 메시지 |
| `GET /api/places?q=...` | 서버를 통한 장소 검색 |

웹은 `drives: true/false`를 보냅니다. 운전하지 않는 사람이 운전자에게 픽업 대상으로 지정됐으면 내부 `mode=pickup`, 그렇지 않으면 `mode=walk`로 저장합니다. `walk`는 기존 API 호환용 내부 값이며 사용자의 실제 도보 이동을 추적하거나 확인한 결과가 아닙니다. 앱 화면에는 도보 선택을 표시하지 않습니다.

픽업 위치는 해당 동승자만 변경할 수 있고, 스스로 픽업 대상으로 추가할 수 없습니다. 초대 중인 픽업 대상도 2명 제한에 포함합니다. 기존 DB에 3명 이상 지정된 약속이 있으면 자동으로 사람을 삭제하지 않고, 출발 전에 2명 이하로 수정하도록 오류를 반환합니다.

현재 SQLite에 기기 프로필·약속·픽업 위치·운전 여부·준비/탑승 상태·공유 동의·메시지·주행 기록을 저장합니다. 사용자화 선호는 /api/seat-profile에 저장하며, 차량의 새 착석을 인식하면 지원되는 좌석 설정을 적용합니다. 상세 조건은 verification/auto-pickup-2026-11-11 및 verification/pickup-simple-2026-11-11 문서를 참고합니다. 로그인·계정 복구도 개발용 기기 토큰 방식이므로 정식 서비스 단계에서 추가해야 합니다.

## 출발 알림 예약 작업

WSGI 내부에 반복 스레드를 만들지 않습니다. 지원하는 예약 작업에서 다음 명령을 실행하면 출발 알림을 한 번 계산합니다.

```sh
cd /home/USERNAME/pooly
POOLY_DATABASE_PATH=/home/USERNAME/pooly/together/data/pooly-flask.sqlite3 /home/USERNAME/.virtualenvs/pooly/bin/python -m flask --app together.server.flask_app:create_app reminders
```

실행 간격은 호스팅 플랜에서 제공하는 범위로 설정합니다. 서버 알림 목록에 저장하는 기능이며 모바일 백그라운드 푸시를 구현한 것은 아닙니다.

## 검증

```sh
./together/.venv/bin/python -m unittest discover -s together/tests -v
```

로컬 Flask 테스트에서 초대·운전 여부·픽업 위치·탑승·영속성, 최대2명 제한, 교차 출처 차단, WSGI HTTPS 원점 처리, 동시 수정 충돌을 검증합니다. PythonAnywhere 실제 배포 및 실차 검증은 별도입니다.
