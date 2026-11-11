"""Isolated, local-only demonstration with two independent simulated passengers.

Never opens the personal together.sqlite3. GPS injection is restricted to an
explicit emulator serial; it does not write fabricated driver locations to Store.
"""
from __future__ import annotations

import argparse
import json
import math
import subprocess
import tempfile
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .app import AppServer, Handler, ROOT
from .domain import ApiError, PLACES, Store, distance

DEMO = ROOT / "demo"
PACKAGE = "io.pickuptogether.app.demo"
ACTORS = ("driver", "jiyeon", "hyunjun")


def nmea(sentence):
    checksum = 0
    for byte in sentence.encode("ascii"):
        checksum ^= byte
    return f"${sentence}*{checksum:02X}"


def gps_sentences(lat, lon, speed_mps=0, heading=0):
    now = datetime.now(timezone.utc)
    la = f"{int(abs(lat)):02d}{(abs(lat) % 1) * 60:07.4f}"
    lo = f"{int(abs(lon)):03d}{(abs(lon) % 1) * 60:07.4f}"
    ns, ew = ("N" if lat >= 0 else "S"), ("E" if lon >= 0 else "W")
    # Goldfish's GNSS HAL parses whole-second hhmmss with sscanf("%06d,").
    # Fractional seconds are valid NMEA, but that HAL silently drops them.
    return [nmea(f"GPGGA,{now:%H%M%S},{la},{ns},{lo},{ew},1,08,0.9,30.0,M,0.0,M,,"),
            nmea(f"GPRMC,{now:%H%M%S},A,{la},{ns},{lo},{ew},{speed_mps * 1.943844:.2f},{heading:.1f},{now:%d%m%y},0.0,E")]


class Emulator:
    def __init__(self, serial="emulator-5554", adb=None):
        if not serial.startswith("emulator-"):
            raise ValueError("시연 위치는 에뮬레이터에서만 재생할 수 있어요.")
        self.serial = serial
        self.adb = str(adb or Path.home() / "Library/Android/sdk/platform-tools/adb")
        self.last_injected = None
        self.error = None

    def command(self, *args, binary=False):
        result = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, timeout=12)
        if result.returncode:
            raise ApiError(503, "에뮬레이터 연결과 시연 앱 설치 상태를 확인해 주세요.")
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def open(self, command="open"):
        self.command("reverse", "tcp:8766", "tcp:8766")
        result = self.command("shell", "am", "start", "--user", "current", "-n", f"{PACKAGE}/io.pickuptogether.app.MainActivity", "--es", "demoCommand", command)
        if "Error" in result:
            raise ApiError(503, "함께가기 시연 앱을 먼저 설치해 주세요.")

    def inject(self, point, speed=0, heading=0):
        try:
            self.command("emu", "geo", "fix", str(point["lon"]), str(point["lat"]), "30", "8", str(speed * 1.943844))
            # This emulator emits geo-fix NMEA with a constant *47 checksum.
            # Supply correctly checksummed sentences for navigation diagnostics.
            for sentence in gps_sentences(point["lat"], point["lon"], speed, heading):
                self.command("emu", "geo", "nmea", sentence)
            self.last_injected, self.error = time.time(), None
        except (ApiError, subprocess.TimeoutExpired, OSError):
            self.error = "GPS를 보내지 못했어요. 에뮬레이터 연결을 확인해 주세요."

    def vehicle(self, speed=0):
        try:
            for endpoint in ["gear?gear=" + ("Drive" if speed else "Park"), f"controls?speed={speed * 3.6:.1f}&accelerator=0&brake=0&steering=0"]:
                request = urllib.request.Request("http://127.0.0.1:8088/vehicle/" + endpoint, method="PUT")
                urllib.request.urlopen(request, timeout=2).close()
        except (OSError, TimeoutError):
            pass  # GPS replay is independent of the optional Vehicle Console.

    def personal_trip_active(self):
        services = self.command("shell", "dumpsys", "activity", "services", "io.pickuptogether.app")
        # dumpsys' package filter also matches the .demo package prefix.
        return any(" io.pickuptogether.app/" in line and "JourneyService" in line for line in services.splitlines())


class DemoSession:
    def __init__(self, store, emulator=None):
        self.store, self.emulator = store, emulator
        self.lock = threading.RLock()
        self.actors = {key: store.register({"name": name}) for key, name in zip(ACTORS, ["시연 운전자", "지연 (가상)", "현준 (가상)"])}
        self.contacts = {key: store.contact(self.uid("driver"), {"name": self.actors[key]["user"]["name"]}) for key in ACTORS[1:]}
        self.road = json.loads((DEMO / "seongsu-road.json").read_text())
        self.running, self.connected, self.multiplier = False, False, 4
        self.leg, self.offset = 0, 0.0
        self.telemetry, self.telemetry_at = {}, None
        self.message = "차량 앱을 열고 출발한 뒤 위치 재생을 눌러 주세요."
        self.point = self.road_point(0, 0)[0]
        self.reset(True)

    def uid(self, actor):
        return self.actors[actor]["user"]["id"]

    def appointment(self):
        return next(a for a in self.store.state(self.actors["driver"]["user"])["appointments"] if a["id"] == self.aid)

    def reset(self, accepted=True):
        with self.lock, self.store.lock:
            self.running = False
            # Only this private temporary Store belongs to the demo process.
            with self.store.db:
                self.store.db.execute("DELETE FROM appointments")
                self.store.db.execute("DELETE FROM notifications")
            self.a = self.store.create(self.actors["driver"]["user"], {
                "name": "[시연] 친구들과 서울숲", "meeting_at": (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat(),
                "mode": "car", "origin": PLACES[0], "destination": PLACES[1],
                "members": [{"id": self.contacts[key]["id"], "mode": "pickup"} for key in ACTORS[1:]],
            })
            self.aid = self.a["id"]
            self.store.member_update(self.uid("driver"), self.aid, {"share": True})
            if accepted:
                for key in ACTORS[1:]:
                    self.actor(key, "join")
            self.leg, self.offset = 0, 0.0
            self.point = self.road_point(0, 0)[0]
            self.message = "새 시연 약속을 만들었어요. 차량에서 출발할 수 있어요." if accepted else "휴대폰 또는 친구 수락 버튼으로 초대부터 시험해 보세요."
            self.telemetry, self.telemetry_at = {}, None

    def actor(self, key, command):
        if key not in ACTORS[1:]:
            raise ApiError(400, "가상 친구를 선택해 주세요.")
        user = self.actors[key]["user"]
        if command == "join":
            a = self.appointment()
            m = next(m for m in a["members"] if m["id"] == self.contacts[key]["id"])
            return self.store.join(user, m["invite_token"], {"mode": "pickup", "pickup": PLACES[2 if key == "jiyeon" else 4], "share": False})
        if command in ("preparing", "ready", "walking", "waiting"):
            return self.store.member_update(user["id"], self.aid, {"ready": command})
        if command in ("board_confirm", "home", "arrive"):
            return self.store.member_update(user["id"], self.aid, {"action": command})
        if command == "share":
            a = self.store.member_update(user["id"], self.aid, {"share": True, "action": "depart"})
            m = next(m for m in a["members"] if m["id"] == a["me_id"])
            return self.store.location(user["id"], self.aid, {**m["pickup"], "accuracy": 5, "mock": True})
        if command == "unshare":
            return self.store.member_update(user["id"], self.aid, {"share": False})
        raise ApiError(400, "지원하지 않는 시연 동작이에요.")

    def road_point(self, leg, offset):
        coords = self.road["legs"][leg]["coordinates"]
        walked = 0.0
        for left, right in zip(coords, coords[1:]):
            p, q = {"lat": left[1], "lon": left[0]}, {"lat": right[1], "lon": right[0]}
            length = distance(p, q)
            if walked + length >= offset:
                f = min(1, max(0, (offset - walked) / max(length, .0001)))
                heading = math.degrees(math.atan2((q["lon"] - p["lon"]) * math.cos(math.radians(p["lat"])), q["lat"] - p["lat"])) % 360
                return {"lat": p["lat"] + f * (q["lat"] - p["lat"]), "lon": p["lon"] + f * (q["lon"] - p["lon"])}, heading, False
            walked += length
        return {"lat": coords[-1][1], "lon": coords[-1][0]}, 0, True

    def tick(self, dt=1):
        with self.lock:
            a = self.appointment()
            t = a["trip"]
            if t["phase"] in ("idle", "arrived", "parked", "finished", "cancelled"):
                self.running = False
            target = 2
            if t["phase"] in ("driving", "near", "arrived"):
                mid = t.get("active_pickups", [])[t["index"]]
                target = 0 if mid == self.contacts["jiyeon"]["id"] else 1
            if target != self.leg and t["phase"] != "idle":
                self.leg, self.offset = target, 0.0
            speed = 0
            if self.running and t["phase"] in ("driving", "near", "destination"):
                speed = 5 * self.multiplier
                self.offset += speed * min(dt, 2)
                self.point, heading, ended = self.road_point(self.leg, self.offset)
                if ended:
                    self.running, speed = False, 0
                    self.message = "픽업 지점에 멈췄어요. 도착 → 친구 탑승 확인 → 운전자 탑승 완료 순서로 진행해 주세요." if self.leg < 2 else "목적지에 멈췄어요. 주차 완료와 이동 종료를 확인해 주세요."
            else:
                _, heading, _ = self.road_point(self.leg, self.offset)
            if self.connected and self.emulator:
                self.emulator.inject(self.point, speed, heading)
                self.emulator.vehicle(speed)
            return speed

    def control(self, data):
        with self.lock:
            command = data.get("command")
            if command == "connect":
                if not self.emulator:
                    raise ApiError(503, "에뮬레이터를 연결하지 않았어요.")
                if self.emulator.personal_trip_active():
                    raise ApiError(409, "일반 함께가기 앱에서 위치 공유 중이에요. 해당 이동을 끝낸 뒤 시연을 연결해 주세요.")
                self.emulator.open()
                self.connected = True
            elif command == "play":
                if not self.connected:
                    raise ApiError(409, "차량 앱 열기를 먼저 눌러 주세요.")
                if self.appointment()["trip"]["phase"] not in ("driving", "near", "destination"):
                    raise ApiError(409, "차량을 출발시키거나 탑승을 완료한 뒤 재생해 주세요.")
                if self.road_point(self.leg, self.offset)[2]:
                    raise ApiError(409, "이 구간 끝에 도착했어요. 탑승 확인 또는 이동 종료를 진행해 주세요.")
                self.running = True
                self.message = "공개 도로 경로를 에뮬레이터 GPS로 재생 중이에요."
            elif command == "pause":
                self.running = False
                self.message = "위치 재생을 멈췄어요. 같은 위치에서 다시 시작할 수 있어요."
            elif command == "speed":
                if data.get("value") not in (1, 2, 4):
                    raise ApiError(400, "재생 속도를 확인해 주세요.")
                self.multiplier = data["value"]
            elif command == "reset":
                if self.emulator and self.connected:
                    self.emulator.open("stop")
                self.reset(data.get("accepted", True) is not False)
            elif command == "actor":
                self.actor(data.get("actor"), data.get("action"))
            elif command == "driver":
                action = data.get("action")
                if action not in ("depart", "arrive", "notify", "board", "park", "finish"):
                    raise ApiError(400, "운전자 동작을 확인해 주세요.")
                if action == "board":
                    a = self.appointment()
                    active = a["trip"].get("active_pickups", [])
                    index = a["trip"]["index"]
                    member = next((m for m in a["members"] if index < len(active) and m["id"] == active[index]), None)
                    if not member or not member.get("boarding_confirmed_at"):
                        raise ApiError(409, "친구가 탑승 확인을 보낸 뒤 완료해 주세요.")
                self.store.action(self.uid("driver"), self.aid, {"action": action})
                if action == "board":
                    self.tick(0)
                    if self.emulator and self.connected:
                        self.emulator.open("navigate")
                if action == "depart" and self.emulator and self.connected:
                    self.emulator.open("navigate")
                if action == "finish":
                    self.running = False
                    if self.emulator and self.connected:
                        self.emulator.open("stop")
            elif command == "navigation":
                if not self.emulator or not self.connected:
                    raise ApiError(409, "차량 앱 열기를 먼저 눌러 주세요.")
                self.emulator.open("navigate")
            else:
                raise ApiError(400, "지원하지 않는 시연 동작이에요.")
            return self.state()

    def state(self):
        with self.lock:
            a = self.appointment()
            return {"appointment": a, "running": self.running, "connected": self.connected, "speed": self.multiplier,
                    "position": self.point, "leg": self.leg, "message": self.message,
                    "telemetry": self.telemetry, "telemetry_at": self.telemetry_at,
                    "injected_at": self.emulator.last_injected if self.emulator else None,
                    "error": self.emulator.error if self.emulator else None,
                    "road": self.road, "actors": {key: {"name": self.actors[key]["user"]["name"], "member_id": self.contacts[key]["id"], "port": 8767 + n} for n, key in enumerate(ACTORS[1:])}}


class DemoHandler(Handler):
    def send(self, status, value, mime="application/json; charset=utf-8"):
        # Parent and passenger frames use separate ports to isolate localStorage.
        data = json.dumps(value, ensure_ascii=False).encode() if mime.startswith("application/json") else value
        self.send_response(status)
        for key, val in {"Content-Type": mime, "Content-Length": str(len(data)), "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer", "Content-Security-Policy": "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; font-src 'self' data:; img-src 'self' data: blob: https://tiles.openfreemap.org; connect-src 'self' https://tiles.openfreemap.org; worker-src 'self' blob:; frame-src http://127.0.0.1:8767 http://127.0.0.1:8768; frame-ancestors 'self' http://127.0.0.1:8766; object-src 'none'; base-uri 'self'; form-action 'self'"}.items():
            self.send_header(key, val)
        self.end_headers()
        self.wfile.write(data)

    def handle_request(self, method):
        try:
            port = self.server.server_port
            host = self.headers.get("Host", "")
            if host not in (f"127.0.0.1:{port}", f"localhost:{port}") or self.headers.get("Sec-Fetch-Site") == "cross-site":
                raise ApiError(403, "이 시연은 맥북 내부에서만 사용할 수 있어요.")
            if method != "GET" and self.headers.get("Origin") not in (None, "http://" + host):
                raise ApiError(403, "이 출처의 요청은 허용되지 않아요.")
            path = urllib.parse.urlsplit(self.path).path
            demo = self.server.demo
            if path.startswith("/api/demo/"):
                if self.server.actor != "driver":
                    raise ApiError(403, "시연 조작 화면에서 사용해 주세요.")
                if method == "GET" and path == "/api/demo/state":
                    return self.send(200, demo.state())
                if method == "POST" and path == "/api/demo/control":
                    return self.send(200, demo.control(self.body()))
                if method == "POST" and path == "/api/demo/telemetry":
                    user = demo.store.user(self.headers.get("Authorization", "")[7:])
                    if user["id"] != demo.uid("driver"):
                        raise ApiError(403, "시연 차량 앱만 연결할 수 있어요.")
                    data = self.body()
                    with demo.lock:
                        demo.telemetry = {key: data[key] for key in ("navigation", "navigationConnected", "navigationMode", "navigationPosition", "navigationEta", "navigationWaypoints", "fusedPosition", "fusedLocation", "vehicle", "foreground", "nmea") if key in data}
                        demo.telemetry_at = time.time()
                    return self.send(200, {"ok": True})
                if method == "GET" and path == "/api/demo/screen" and demo.emulator:
                    return self.send(200, demo.emulator.command("exec-out", "screencap", "-p", binary=True), "image/png")
                raise ApiError(404, "요청을 찾을 수 없어요.")
            if method == "POST" and path == "/api/sessions":
                self.body()
                return self.send(201, demo.actors[self.server.actor])
            if method == "GET" and path == "/bootstrap":
                actor = demo.actors[self.server.actor]
                role = "car" if self.server.actor == "driver" else "phone"
                # Only generated demo identities; never the user's real token.
                target = f"/{role}.html"
                if role == "phone":
                    m = next(m for m in demo.appointment()["members"] if m["id"] == demo.contacts[self.server.actor]["id"])
                    if m["status"] == "pending":
                        target += "?invite=" + urllib.parse.quote(m["invite_token"], safe="")
                script = f"localStorage.setItem('together.v2.{role}.token',{json.dumps(actor['token'])});localStorage.removeItem('together.v2.{role}.selected');location.replace({json.dumps(target)});"
                return self.send(200, ("<!doctype html><meta charset=utf-8><title>시연 연결</title><script>" + script + "</script>").encode(), "text/html; charset=utf-8")
            if method == "GET" and path in ("/", "/demo") and self.server.actor == "driver":
                return self.send(200, (DEMO / "index.html").read_bytes(), "text/html; charset=utf-8")
            if method == "GET" and path in ("/demo.js", "/demo.css"):
                return self.send(200, (DEMO / path[1:]).read_bytes(), "text/javascript" if path.endswith(".js") else "text/css")
            super().handle_request(method)
        except ApiError as error:
            self.send(error.status, {"error": error.message})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except (ValueError, TypeError, KeyError):
            self.send(400, {"error": "시연 요청을 확인해 주세요."})
        except (OSError, subprocess.TimeoutExpired):
            self.send(503, {"error": "에뮬레이터에 연결하지 못했어요."})


def make_demo_servers(demo, ports=(8766, 8767, 8768)):
    servers = []
    try:
        for port, actor in zip(ports, ACTORS):
            server = AppServer(("127.0.0.1", port), demo.store)
            server.RequestHandlerClass = DemoHandler
            server.demo, server.actor = demo, actor
            servers.append(server)
        return servers
    except Exception:
        for server in servers:
            server.server_close()
        raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="together-demo-") as directory:
        store = Store(Path(directory) / "demo.sqlite3")
        demo = DemoSession(store, Emulator(args.serial))
        servers = make_demo_servers(demo)
        stop = threading.Event()

        def replay():
            previous = time.monotonic()
            count = 0
            while not stop.wait(1):
                try:
                    if demo.connected and count % 5 == 0 and demo.emulator.personal_trip_active():
                        demo.running, demo.connected = False, False
                        demo.message = "일반 앱의 위치 공유가 시작되어 시연 GPS 제어를 해제했어요."
                        demo.emulator.vehicle(0)
                    current = time.monotonic()
                    demo.tick(current - previous)
                    previous = current
                    count += 1
                    if count % 10 == 0:
                        store.reminders()
                except Exception as error:
                    demo.running = False
                    demo.message = "시연 재생을 멈췄어요. 연결 상태를 확인해 주세요."
                    print("Demo replay:", type(error).__name__, flush=True)

        for server in servers:
            threading.Thread(target=server.serve_forever, daemon=True).start()
        threading.Thread(target=replay, daemon=True).start()
        print("시연 조작 화면: http://127.0.0.1:8766/", flush=True)
        print("가상 친구 휴대폰: http://127.0.0.1:8767/bootstrap · http://127.0.0.1:8768/bootstrap", flush=True)
        print("일반 서버와 분리된 임시 시연 데이터:", directory, flush=True)
        try:
            while not stop.wait(1):
                pass
        except KeyboardInterrupt:
            pass
        finally:
            stop.set()
            demo.running = False
            if demo.connected:
                demo.emulator.vehicle(0)
            for server in servers:
                server.shutdown()
                server.server_close()
            store.close()


if __name__ == "__main__":
    main()
