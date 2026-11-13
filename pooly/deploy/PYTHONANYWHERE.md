# Pooly Flask 서버 배포 (PythonAnywhere)

운영 서버: **https://yeonil.pythonanywhere.com**
차량 앱(`BuildConfig.API_BASE_URL`)과 휴대폰 웹은 이 서버 하나만 사용합니다. 웹 페이지와 API가 같은 도메인에 있으므로 CORS 설정은 필요하지 않습니다.

## 처음 배포하기

1. 프로젝트 루트에서 배포 ZIP을 만듭니다. DB·가상환경·키는 포함되지 않습니다.
   ```sh
   python3 pooly/scripts/package-pythonanywhere.py   # → pooly/artifacts/pooly-pythonanywhere.zip
   ```
2. ZIP을 PythonAnywhere에 업로드하고 Bash 콘솔에서 `/home/USERNAME/pooly`에 압축을 풉니다. 이 폴더 바로 아래에 `pooly/server/flask_app.py`가 있어야 합니다. (Git으로 받아도 됩니다: `git clone … /home/USERNAME/pooly`)
3. 가상환경을 만듭니다. Web 탭에서 선택할 Python 버전과 맞춥니다.
   ```sh
   mkvirtualenv --python=/usr/bin/python3.13 pooly
   cd /home/USERNAME/pooly && pip install -r pooly/requirements.txt
   ```
4. Web 탭 → Add a new web app → Manual configuration → 같은 Python 버전 → Virtualenv에 `/home/USERNAME/.virtualenvs/pooly`.
5. WSGI configuration file에 [`pythonanywhere_wsgi.py`](pythonanywhere_wsgi.py) 내용을 붙여 넣고 `USERNAME`을 실제 계정명으로 바꿉니다.
6. **Reload** 후 `https://USERNAME.pythonanywhere.com/health`가 `{"ok": true, "service": "pooly", ...}`를 반환하는지 확인합니다.

## 기존 배포를 이 버전으로 옮기기 (`together/` → `pooly/`)

이전 버전은 Python 패키지가 `together/`였습니다. 이번 버전에서 `pooly/`로 이름이 바뀌었으므로 아래 순서로 옮깁니다. **DB 파일을 먼저 백업**하세요.

```sh
cd /home/USERNAME/pooly
cp together/data/pooly-flask.sqlite3 ~/pooly-flask.backup.sqlite3   # 백업
# 새 소스 받기 (git pull 또는 새 ZIP 압축 해제) 후
mkdir -p pooly/data
mv together/data/pooly-flask.sqlite3 pooly/data/                       # 기존 데이터 유지
```

그다음 WSGI 파일에서 두 줄을 바꾸고 Reload 합니다.

```python
os.environ["POOLY_DATABASE_PATH"] = project_root + "/pooly/data/pooly-flask.sqlite3"
from pooly.server.wsgi import application        # 이전: from together.server.wsgi import application
```

`/health`의 `service`가 `pooly`, `version`이 `1.0.0`이면 완료입니다. 확인 후 남은 `together/` 폴더는 지워도 됩니다.
차량 앱은 패키지 이름이 `io.pooly.app`으로 바뀌었으므로 새 APK를 설치하고 다시 로그인(기기 프로필 생성)해야 합니다.

## 환경변수

| 이름 | 값 |
|---|---|
| `POOLY_DATABASE_PATH` | SQLite 절대 경로. 소스를 다시 배포할 때 덮어쓰지 않도록 소스 밖이나 `pooly/data/`(Git 제외)에 둡니다. |
| `POOLY_PUBLIC_ORIGIN` | 실제 HTTPS 원점. 예: `https://yeonil.pythonanywhere.com` |
| `POOLY_SQLITE_JOURNAL_MODE` | 기본 `DELETE` (PythonAnywhere 파일 시스템용) |
| `POOLY_PICKUP_REQUIRE_PROXIMITY` | 기본 `1`. `0`이면 자동 승차에서 80m 근접 조건만 끕니다(에뮬레이터 내비 위치가 움직이지 않는 시연용). |
| `VWORLD_API_KEY`, `VWORLD_DOMAIN` | 브이월드 장소 검색 키와 키에 등록한 서비스 URL |
| `KAKAO_REST_API_KEY` | 브이월드 키가 없을 때 쓰는 선택 검색 제공자 |
| `POOLY_SEARCH_CONFIG` | 선택. 검색 키를 담은 서버 전용 JSON 경로 (기본 `pooly/data/private/search-provider.json`) |
| `POOLY_ROUTE_PROVIDER` | 기본 `estimate`. 실제 차량 안내는 Pleos 내비가 담당합니다. |
| `POOLY_DATABASE_VIEW_PASSWORD_HASH` | 선택. 설정하면 `/database` 관리자 요약 화면을 Basic 인증(`pooly`)으로 엽니다. |

키는 WSGI 환경에만 넣고, 프런트엔드·Git·배포 ZIP에는 넣지 않습니다.

## API 요약

| 요청 | 기능 |
|---|---|
| `GET /health` | 서버 상태 |
| `POST /api/sessions` | 기기 프로필과 Bearer 토큰 생성 |
| `GET /api/state` | 내 약속·친구·알림 |
| `POST /api/contacts` | 친구 추가 |
| `POST /api/appointments` | 약속 생성 (픽업 대상 최대 2명) |
| `PATCH /api/appointments/<id>` | 약속 수정 |
| `GET /api/invites/<token>` · `POST /api/invites/<token>/join` | 초대 확인 · 참가 |
| `POST /api/appointments/<id>/member` | 내 픽업 위치·준비 상태·위치 공유 동의 |
| `POST /api/appointments/<id>/actions` | 운전자 동작: `depart` `arrive` `board` `skip` `park` `finish` `cancel`, 차량 신호 기반 `auto_arrive` `auto_board` `auto_alight` `auto_finish`, 내비 동기화 `navigation_status` `navigation_eta` |
| `POST /api/appointments/<id>/seat-personalization` | 승차가 확인된 일행의 좌석 설정 조회(운전자 전용) |
| `GET/POST /api/seat-profile` | 내 좌석 설정(열선·통풍 단계, 차량별 메모리 슬롯) |
| `POST /api/appointments/<id>/location` | 동의한 이동의 위치 갱신 |
| `POST /api/appointments/<id>/messages` | 일행 메시지 |
| `GET /api/places?q=...` | 장소 검색 프록시 |

`auto_board`·`auto_alight`는 운전자 권한, 현재 대상, 신호 시각(15초 이내), 좌석·차량 키 일치, 관찰 ID 중복을 서버에서 다시 검증합니다. 같은 관찰 ID를 다시 보내면 상태를 바꾸지 않고 현재 상태를 돌려줍니다.

## 출발 알림 예약 작업 (선택)

WSGI 안에서 반복 스레드를 만들지 않습니다. PythonAnywhere Tasks에 아래 명령을 등록하면 출발 알림을 한 번 계산합니다.

```sh
cd /home/USERNAME/pooly
POOLY_DATABASE_PATH=/home/USERNAME/pooly/pooly/data/pooly-flask.sqlite3 /home/USERNAME/.virtualenvs/pooly/bin/python -m flask --app pooly.server.flask_app:create_app reminders
```

## 로컬에서 확인

```sh
python3 -m venv .venv && ./.venv/bin/pip install -r pooly/requirements.txt
./.venv/bin/python -m unittest discover -s pooly/tests -t .
POOLY_DATABASE_PATH=/tmp/pooly-local.sqlite3 ./.venv/bin/python -m flask --app pooly.server.flask_app:create_app run --port 8000
```

로컬 서버는 서버 코드 확인용입니다. 차량 앱은 배포 서버 주소만 사용합니다.
