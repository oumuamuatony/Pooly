"""Appointment domain and persistence. No third-party Python packages required."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import math
import os
import secrets
import sqlite3
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))
MAX_MEMBERS = 12
FAN_DIRECTIONS = {"FACE", "FLOOR", "FACE_AND_FLOOR", "DEFROST", "FACE_AND_DEFROST", "DEFROST_AND_FLOOR", "FACE_AND_FLOOR_AND_DEFROST"}
DEMO_FRIENDS = ("Max", "Kelly", "Jeff")


class ApiError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message
        super().__init__(message)


def now_iso():
    return datetime.now(timezone.utc).isoformat()


def timestamp(value):
    try:
        d = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if not d.tzinfo:
            d = d.replace(tzinfo=KST)
        return d.timestamp()
    except (ValueError, TypeError):
        raise ApiError(400, "날짜와 시간을 확인해 주세요.")


def text(value, maximum=120, required=False):
    s = str(value or "").strip()
    if len(s) > maximum or (required and not s):
        raise ApiError(400, "입력한 내용을 확인해 주세요.")
    return s


def point(value, required=True):
    if not value and not required:
        return None
    if not isinstance(value, dict):
        raise ApiError(400, "지도에서 장소를 선택해 주세요.")
    try:
        lat, lon = float(value["lat"]), float(value["lon"])
        if not math.isfinite(lat + lon) or not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise ValueError()
    except (KeyError, TypeError, ValueError):
        raise ApiError(400, "장소의 좌표를 확인해 주세요.")
    return {"lat": lat, "lon": lon, "name": text(value.get("name"), required=True),
            "address": text(value.get("address"), 300), "description": text(value.get("description"), 300)}


def distance(a, b):
    r = math.pi / 180
    dlat, dlon = (b["lat"] - a["lat"]) * r, (b["lon"] - a["lon"]) * r
    x = math.sin(dlat / 2) ** 2 + math.cos(a["lat"] * r) * math.cos(b["lat"] * r) * math.sin(dlon / 2) ** 2
    return 6371000 * 2 * math.atan2(math.sqrt(x), math.sqrt(max(0, 1 - x)))


PLACES = [
    {"id": "k", "name": "건국대학교 서울캠퍼스", "address": "서울 광진구 능동로 120", "lat": 37.5408, "lon": 127.0793},
    {"id": "d", "name": "서울숲", "address": "서울 성동구 뚝섬로 273", "lat": 37.5444, "lon": 127.0374},
    {"id": "s3", "name": "성수역 3번 출구", "address": "서울 성동구 아차산로 100", "lat": 37.5445, "lon": 127.0560},
    {"id": "s2", "name": "성수역 2번 출구", "address": "서울 성동구 아차산로 99", "lat": 37.5447, "lon": 127.0559},
    {"id": "t8", "name": "뚝섬역 8번 출구", "address": "서울 성동구 아차산로 18", "lat": 37.5472, "lon": 127.0474},
    {"id": "t5", "name": "뚝섬역 5번 출구", "address": "서울 성동구 아차산로 17", "lat": 37.5470, "lon": 127.0472},
    {"id": "wsr", "name": "왕십리역", "address": "서울 성동구 왕십리로", "lat": 37.5615, "lon": 127.0377},
    {"id": "hd", "name": "행당동 주민센터", "address": "서울 성동구 행당로", "lat": 37.5585, "lon": 127.0359},
]
PARKING = [
    {"id": "p1", "name": "서울숲 공영주차장", "lat": 37.5445, "lon": 127.0436, "address": "서울 성동구 서울숲 인근", "parking_minutes": 5, "walk_minutes": 5},
    {"id": "p2", "name": "성수1가 공영주차장", "lat": 37.5468, "lon": 127.0430, "address": "서울 성동구 성수동1가", "parking_minutes": 5, "walk_minutes": 8},
    {"id": "p3", "name": "서울숲 인근 주차장", "lat": 37.5425, "lon": 127.0400, "address": "서울 성동구 서울숲 인근", "parking_minutes": 5, "walk_minutes": 4},
]


class Store:
    def __init__(self, filename, route_provider="estimate", journal_mode="WAL", pickup_require_proximity=None, demo_owner=""):
        self.demo_owner = demo_owner
        # Server-owned switch. Clients cannot turn off location checks via request evidence.
        self.pickup_require_proximity = (os.environ.get("POOLY_PICKUP_REQUIRE_PROXIMITY", "1") != "0"
                                         if pickup_require_proximity is None else bool(pickup_require_proximity))
        self.filename = Path(filename)
        self.filename.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.filename, check_same_thread=False, timeout=15)
        self.db.row_factory = sqlite3.Row
        if journal_mode not in {"WAL", "DELETE"}:
            raise ValueError("Unsupported SQLite journal mode")
        self.db.execute("PRAGMA journal_mode=" + journal_mode)
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,name TEXT NOT NULL,token_hash TEXT UNIQUE NOT NULL);
            CREATE TABLE IF NOT EXISTS contacts(id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,name TEXT NOT NULL,user_id TEXT);
            CREATE TABLE IF NOT EXISTS appointments(id TEXT PRIMARY KEY,document TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS notifications(id INTEGER PRIMARY KEY AUTOINCREMENT,user_id TEXT NOT NULL,appointment_id TEXT,title TEXT,body TEXT,created_at TEXT);
            CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS seat_profiles(user_id TEXT PRIMARY KEY,document TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS pickup_stops(id INTEGER PRIMARY KEY AUTOINCREMENT,lat REAL NOT NULL,lon REAL NOT NULL,dwell_s INTEGER NOT NULL,hour INTEGER NOT NULL,kind TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS pickup_stops_cell ON pickup_stops(lat,lon);
        """)
        secret = self.db.execute("SELECT value FROM metadata WHERE key='secret'").fetchone()
        self.secret = secret[0] if secret else secrets.token_hex(32)
        self.db.execute("INSERT OR IGNORE INTO metadata VALUES('secret',?)", (self.secret,))
        self.db.commit()
        self.secret = self.db.execute("SELECT value FROM metadata WHERE key='secret'").fetchone()[0]
        os.chmod(self.filename, 0o600)
        self.route_provider = route_provider
        self.route_cache = {}
        self.route_lock = threading.Lock()

    def close(self):
        self.db.close()

    def user(self, token):
        if not token:
            raise ApiError(401, "기기를 다시 연결해 주세요.")
        with self.lock:
            row = self.db.execute("SELECT id,name FROM users WHERE token_hash=?", (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
        if not row:
            raise ApiError(401, "기기를 다시 연결해 주세요.")
        return dict(row)

    def seat_profile(self, uid, data=None):
        with self.lock, self.db:
            row = self.db.execute("SELECT document FROM seat_profiles WHERE user_id=?", (uid,)).fetchone()
            profile = json.loads(row[0]) if row else {"enabled": False, "cold_heat": 1, "hot_ventilation": 1, "vehicle_memories": {}}
            if data is None:
                return profile
            if type(data.get("enabled")) is not bool:
                raise ApiError(400, "자동 사용자화 사용 여부를 확인해 주세요.")
            for field in ["cold_heat", "hot_ventilation"]:
                if type(data.get(field)) is not int or not 0 <= data[field] <= 3:
                    raise ApiError(400, "열선·통풍 단계는 0~3으로 설정해 주세요.")
            updated = {**profile, **{k: data[k] for k in ["enabled", "cold_heat", "hot_ventilation"]}}
            # Memory indexes belong to the vehicle that was actually matched to this user.
            if "vehicle_memory" in data:
                preset = data["vehicle_memory"]
                if not isinstance(preset, dict) or type(preset.get("slot")) is not int or not 0 <= preset["slot"] <= 3:
                    raise ApiError(400, "차량 좌석 메모리를 확인해 주세요.")
                vehicle_key = text(preset.get("vehicle_key"), 80, True)
                matched = any(m.get("user_id") == uid and m.get("seat_binding", {}).get("vehicle_key") == vehicle_key
                              for row in self.db.execute("SELECT document FROM appointments")
                              for m in json.loads(row[0])["members"])
                if not matched:
                    raise ApiError(403, "승차가 확인된 차량의 좌석 메모리만 저장할 수 있어요.")
                updated["vehicle_memories"] = dict(profile.get("vehicle_memories", {}))
                if preset["slot"]:
                    updated["vehicle_memories"][vehicle_key] = preset["slot"]
                else:
                    updated["vehicle_memories"].pop(vehicle_key, None)
            updated["updated_at"] = now_iso()
            self.db.execute("INSERT OR REPLACE INTO seat_profiles VALUES(?,?)", (uid, json.dumps(updated)))
            return updated

    def seat_personalization(self, uid, aid, data):
        with self.lock:
            a = self._access(aid, uid)
            driver = self._driver(a)
            if not driver or driver.get("user_id") != uid or a["trip"]["phase"] in ["finished", "cancelled"]:
                raise ApiError(403, "현재 이동의 운전자만 승차한 일행 설정을 조회할 수 있어요.")
            m = next((m for m in a["members"] if m["id"] == data.get("member_id")), None)
            if not m or not m.get("boarded_at") or not m.get("seat_binding"):
                raise ApiError(409, "좌석 승차가 확인된 일행의 설정만 적용할 수 있어요.")
            return {"profile": self.seat_profile(m["user_id"]), "binding": m["seat_binding"]}

    def seat_learning(self, uid, aid, data):
        """Learns a passenger's heat/ventilation level from a change made in the car, by cabin temperature band."""
        with self.lock, self.db:
            a = self._access(aid, uid)
            driver = self._driver(a)
            if not driver or driver.get("user_id") != uid or a["trip"]["phase"] in ["idle", "finished", "cancelled"]:
                raise ApiError(403, "현재 이동의 운전자 차량만 좌석 사용 기록을 보낼 수 있어요.")
            m = next((m for m in a["members"] if m["id"] == data.get("member_id")), None)
            binding = (m or {}).get("seat_binding") or {}
            if not m or not m.get("boarded_at") or m.get("alighted_at") or not binding or not m.get("user_id"):
                raise ApiError(409, "좌석 승차가 확인된 일행만 기록할 수 있어요.")
            if data.get("seat") != binding.get("seat"):
                raise ApiError(409, "승차할 때 연결된 좌석의 변화만 기록할 수 있어요.")
            cabin, heat, vent = data.get("cabin_c"), data.get("heat"), data.get("vent")
            target, fan = data.get("target_temp"), data.get("fan_direction")
            if type(cabin) not in [int, float] or not math.isfinite(cabin) or not -40 <= cabin <= 60:
                raise ApiError(400, "실내 온도를 확인해 주세요.")
            for level in (heat, vent):
                if level is not None and (type(level) is not int or not 0 <= level <= 3):
                    raise ApiError(400, "열선·통풍 단계를 확인해 주세요.")
            if target is not None and (type(target) not in [int, float] or not math.isfinite(target) or not 10 <= target <= 35):
                raise ApiError(400, "희망 온도를 확인해 주세요.")
            if fan is not None and fan not in FAN_DIRECTIONS:
                raise ApiError(400, "바람 방향을 확인해 주세요.")
            learned = {}
            # Heat/ventilation follow the same bands as the car app: <= 20 C heat, >= 26 C ventilation.
            if heat is not None and cabin <= 20:
                learned["cold_heat"] = heat
            if vent is not None and cabin >= 26:
                learned["hot_ventilation"] = vent
            # The passenger's own climate zone: preferred target temperature and fan direction.
            if target is not None:
                learned["target_temp"] = round(float(target), 1)
            if fan is not None:
                learned["fan_direction"] = fan
            if not learned:
                return {"learned": [], "values": {}}
            profile = self.seat_profile(m["user_id"])
            updated = {**profile, **learned, "updated_at": now_iso()}
            if any(k in learned for k in ["target_temp", "fan_direction"]) or learned.get("cold_heat", 0) > 0 or learned.get("hot_ventilation", 0) > 0:
                updated["enabled"] = True
            self.db.execute("INSERT OR REPLACE INTO seat_profiles VALUES(?,?)", (m["user_id"], json.dumps(updated)))
            return {"learned": list(learned), "values": learned}

    def _auto_evidence(self, a, current, data, boarding=False):
        if not current or data.get("member_id") != current["id"]:
            raise ApiError(409, "현재 픽업 대상이 바뀌었어요.")
        evidence = data.get("evidence", {})
        if not isinstance(evidence, dict):
            raise ApiError(400, "차량 신호를 확인해 주세요.")
        now = time.time() * 1000
        observed = evidence.get("observed_at")
        if type(observed) not in [int, float] or not math.isfinite(observed) or not -5000 <= now-observed <= 15000:
            raise ApiError(409, "최근 차량 신호로 다시 확인해 주세요.")
        if self.pickup_require_proximity:
            raw_position = evidence.get("position")
            position = point(raw_position)
            position_at = raw_position.get("updatedAt")
            if type(position_at) not in [int, float] or not math.isfinite(position_at) or not 0 <= observed-position_at <= 10000:
                raise ApiError(409, "최근 내비 위치로 다시 확인해 주세요.")
            if not current.get("pickup") or distance(position, current["pickup"]) > 80:
                raise ApiError(409, "현재 픽업 위치 근처에서만 자동 확인할 수 있어요.")
        if boarding:
            import re
            seat = evidence.get("seat", "")
            driver_seat = evidence.get("driver_seat", "")
            if not isinstance(seat, str) or not isinstance(driver_seat, str) or not re.fullmatch(r"ROW_[123]_(LEFT|CENTER|RIGHT)", seat) or not re.fullmatch(r"ROW_[123]_(LEFT|CENTER|RIGHT)", driver_seat) or seat == driver_seat:
                raise ApiError(400, "동승자 좌석을 확인해 주세요.")
            changed = evidence.get("occupied_at")
            if type(changed) not in [int, float] or not math.isfinite(changed) or changed <= 0 or observed < changed:
                raise ApiError(409, "새로운 착석 신호를 확인해 주세요.")
            if any(m.get("boarded_at") and not m.get("alighted_at") and m.get("seat_binding", {}).get("seat") == seat for m in a["members"]):
                raise ApiError(409, "이미 다른 일행과 연결된 좌석이에요.")
            return {"seat": seat, "vehicle_key": text(evidence.get("vehicle_key"), 80, True), "observation_id": text(data.get("observation_id"), 200, True), "occupied_at": changed, "observed_at": observed, "method": "nearby_occupancy" if self.pickup_require_proximity else "demo_occupancy"}
        return None

    def _alight_evidence(self, member, data):
        """Validates a seat-vacated signal for a passenger whose boarding was bound to that seat."""
        binding = member.get("seat_binding") or {}
        evidence = data.get("evidence", {})
        if not isinstance(evidence, dict):
            raise ApiError(400, "차량 신호를 확인해 주세요.")
        now = time.time() * 1000
        observed = evidence.get("observed_at")
        if type(observed) not in [int, float] or not math.isfinite(observed) or not -5000 <= now-observed <= 15000:
            raise ApiError(409, "최근 차량 신호로 다시 확인해 주세요.")
        if evidence.get("parked") is not True:
            raise ApiError(409, "정차한 상태에서만 하차를 확인할 수 있어요.")
        if evidence.get("seat") != binding.get("seat"):
            raise ApiError(409, "승차할 때 연결된 좌석에서만 하차를 확인할 수 있어요.")
        if binding.get("vehicle_key") and evidence.get("vehicle_key") != binding["vehicle_key"]:
            raise ApiError(409, "승차를 확인한 차량에서만 하차를 확인할 수 있어요.")
        vacated = evidence.get("vacated_at")
        if type(vacated) not in [int, float] or not math.isfinite(vacated) or vacated <= binding.get("occupied_at", 0) or observed < vacated:
            raise ApiError(409, "새로운 좌석 이탈 신호를 확인해 주세요.")
        return {"seat": binding["seat"], "observation_id": text(data.get("observation_id"), 200, True),
                "vacated_at": vacated, "observed_at": observed, "method": "seat_vacated"}

    # Stop records for pickup recommendations. Only a coarse cell (~50 m), the stop length, the hour
    # and boarding/alighting are kept: no user, vehicle, appointment or exact time is stored.
    STOP_GRID = 0.0005
    STOP_MAX_S = 600
    STOP_MIN_RECORDS = 5

    def record_stop(self, uid, aid, data):
        with self.lock, self.db:
            a = self._access(aid, uid)
            driver = self._driver(a)
            if not driver or driver.get("user_id") != uid:
                raise ApiError(403, "운전자 차량만 정차 기록을 보낼 수 있어요.")
            if a["trip"]["phase"] in ["idle", "cancelled", "finished"]:
                raise ApiError(409, "이동 중에만 정차 기록을 보낼 수 있어요.")
            lat, lon, dwell, hour, kind = data.get("lat"), data.get("lon"), data.get("dwell_s"), data.get("hour"), data.get("kind")
            if any(type(v) not in [int, float] or not math.isfinite(v) for v in (lat, lon)) or not (-90 <= lat <= 90 and -180 <= lon <= 180):
                raise ApiError(400, "정차 위치를 확인해 주세요.")
            if type(dwell) is not int or not 1 <= dwell <= self.STOP_MAX_S:
                raise ApiError(400, "10분 이내의 정차만 기록해요.")
            if type(hour) is not int or not 0 <= hour <= 23 or kind not in ["board", "alight"]:
                raise ApiError(400, "정차 기록을 확인해 주세요.")
            cell = lambda v: round(round(v / self.STOP_GRID) * self.STOP_GRID, 6)
            self.db.execute("INSERT INTO pickup_stops(lat,lon,dwell_s,hour,kind) VALUES(?,?,?,?,?)", (cell(lat), cell(lon), dwell, hour, kind))
            return {"ok": True}

    def pickup_recommendations(self, lat, lon, hour=None, radius=2500, limit=8):
        """Ranks ~50 m cells near a place by recorded boardings: frequent, short stops close by rank first."""
        if not (math.isfinite(lat) and math.isfinite(lon) and -90 <= lat <= 90 and -180 <= lon <= 180):
            raise ApiError(400, "위치를 확인해 주세요.")
        if hour is not None and not 0 <= hour <= 23:
            raise ApiError(400, "시간대를 확인해 주세요.")
        dlat = radius / 111000
        dlon = radius / (111000 * max(0.2, math.cos(math.radians(lat))))
        with self.lock:
            rows = self.db.execute("""SELECT lat,lon,COUNT(*) n,AVG(dwell_s) dwell,GROUP_CONCAT(hour) hours FROM pickup_stops
                WHERE kind='board' AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? GROUP BY lat,lon HAVING n >= ?""",
                (lat-dlat, lat+dlat, lon-dlon, lon+dlon, self.STOP_MIN_RECORDS)).fetchall()
        spots = []
        for r in rows:
            meters = distance({"lat": lat, "lon": lon}, {"lat": r["lat"], "lon": r["lon"]})
            if meters > radius:
                continue
            hours = [int(h) for h in r["hours"].split(",")]
            near = sum(1 for h in hours if hour is not None and min(abs(h-hour), 24-abs(h-hour)) <= 1)
            # 오래 정차해도 괜찮았던 곳(단속·뒤차 부담이 적은 곳)일수록 높게, 5분부터는 같은 가중치
            score = r["n"] * (1 + (near / r["n"] if hour is not None else 0)) * (1 + min(r["dwell"], 300) / 300) / (1 + meters / 500)
            spots.append({"lat": r["lat"], "lon": r["lon"], "count": r["n"], "hour_count": near,
                          "avg_dwell_s": round(r["dwell"]), "distance_m": round(meters), "score": round(score, 4)})
        spots.sort(key=lambda s: -s["score"])
        for i, spot in enumerate(spots[:limit]):
            spot["rank"] = i + 1
        return {"spots": spots[:limit], "min_records": self.STOP_MIN_RECORDS, "source": "recorded"}

    def register(self, data):
        name = text(data.get("name"), 40, True)
        token, uid = secrets.token_urlsafe(32), secrets.token_hex(12)
        with self.lock, self.db:
            self.db.execute("INSERT INTO users VALUES(?,?,?)", (uid, name, hashlib.sha256(token.encode()).hexdigest()))
        return {"user": {"id": uid, "name": name}, "token": token}

    def _demo_contacts(self):
        return self.db.execute(
            "SELECT id,name FROM contacts WHERE owner_id=? AND user_id IS NULL AND name IN (?,?,?) ORDER BY rowid",
            (self.demo_owner, *DEMO_FRIENDS),
        ).fetchall()

    def enable_paired_demo(self, uid):
        row = self.db.execute("SELECT 1 FROM metadata WHERE key=?", ("paired-owner:" + uid,)).fetchone()
        if row:
            self.demo_owner = uid

    def create_pair_code(self, uid):
        code = secrets.token_hex(8).upper()
        digest = hashlib.sha256(code.encode()).hexdigest()
        with self.lock, self.db:
            owner_key = "paired-owner:" + uid
            old = self.db.execute("SELECT value FROM metadata WHERE key=?", (owner_key,)).fetchone()
            if old:
                self.db.execute("DELETE FROM metadata WHERE key=?", ("pair-code:" + old["value"],))
            self.db.execute("INSERT OR REPLACE INTO metadata VALUES(?,?)",
                            (owner_key, digest))
            self.db.execute("INSERT INTO metadata VALUES(?,?)",
                            ("pair-code:" + digest, json.dumps({"owner": uid, "expires": time.time() + 600})))
            self.demo_owner = uid
            for name in DEMO_FRIENDS:
                self.db.execute(
                    "INSERT INTO contacts SELECT ?,?,?,NULL WHERE NOT EXISTS "
                    "(SELECT 1 FROM contacts WHERE owner_id=? AND name=?)",
                    (secrets.token_hex(8), uid, name, uid, name),
                )
        return {"code": code, "expires_in": 600}

    def use_pair_code(self, value):
        code = str(value or "").replace("-", "").replace(" ", "").upper()
        if len(code) == 16:
            digest = hashlib.sha256(code.encode()).hexdigest()
            row = self.db.execute("SELECT value FROM metadata WHERE key=?", ("pair-code:" + digest,)).fetchone()
            if row:
                pair = json.loads(row["value"])
                if pair["expires"] >= time.time():
                    self.demo_owner = pair["owner"]
                    return
        raise ApiError(403, "차량에서 발급한 연결 코드를 다시 확인해 주세요.")

    def demo_roster(self):
        friends = [dict(c) for c in self._demo_contacts()]
        ids = {c["id"] for c in friends}
        rows = self.db.execute("SELECT document FROM appointments ORDER BY json_extract(document, '$.created_at') DESC")
        for row in rows:
            a = json.loads(row[0])
            if a["owner_id"] == self.demo_owner and a["trip"]["phase"] not in ("finished", "cancelled"):
                default = next((m["id"] for m in a["members"] if m["id"] in ids), None)
                if default:
                    return {"friends": friends, "default_id": default}
        return {"friends": friends, "default_id": friends[0]["id"] if friends else None}

    def _demo_account(self, contact):
        key = f"pooly-demo:{self.demo_owner}:{contact['id']}"
        token = hmac.new(self.secret.encode(), key.encode(), hashlib.sha256).hexdigest()
        uid = hashlib.sha256(key.encode()).hexdigest()[:24]
        self.db.execute("INSERT OR IGNORE INTO users VALUES(?,?,?)",
                        (uid, contact["name"], hashlib.sha256(token.encode()).hexdigest()))
        return {"user": {"id": uid, "name": contact["name"]}, "token": token}

    def demo_session(self, contact_id):
        with self.lock, self.db:
            contact = next((c for c in self._demo_contacts() if c["id"] == contact_id), None)
            if not contact:
                raise ApiError(404, "시연 친구를 찾을 수 없어요.")
            session = self._demo_account(contact)
            for row in self.db.execute("SELECT document FROM appointments").fetchall():
                a = json.loads(row[0])
                if a["owner_id"] != self.demo_owner or a["trip"]["phase"] != "idle":
                    continue
                member = next((m for m in a["members"] if m["id"] == contact_id and m["status"] == "pending" and not m.get("user_id")), None)
                if member:
                    member.update(user_id=session["user"]["id"], status="accepted")
                    self._save(a)
            return session

    def _get(self, aid):
        row = self.db.execute("SELECT document FROM appointments WHERE id=?", (aid,)).fetchone()
        if not row:
            raise ApiError(404, "약속을 찾을 수 없어요.")
        return json.loads(row[0])

    def _save(self, a):
        a["revision"] += 1
        a["updated_at"] = now_iso()
        self.db.execute("INSERT OR REPLACE INTO appointments VALUES(?,?)", (a["id"], json.dumps(a, ensure_ascii=False)))

    def _member(self, a, uid):
        if a["owner_id"] == uid:
            return a["owner"]
        return next((m for m in a["members"] if m.get("user_id") == uid and m["status"] == "accepted"), None)

    def _access(self, aid, uid):
        a = self._get(aid)
        if not self._member(a, uid):
            raise ApiError(403, "참가한 약속만 확인할 수 있어요.")
        return a

    def _owner(self, a, uid):
        if a["owner_id"] != uid:
            raise ApiError(403, "약속을 만든 사람만 변경할 수 있어요.")

    def delete(self, uid, aid):
        with self.lock, self.db:
            self._owner(self._get(aid), uid)
            self.db.execute("DELETE FROM notifications WHERE appointment_id=?", (aid,))
            self.db.execute("DELETE FROM appointments WHERE id=?", (aid,))
            return {"deleted": True}

    def _driver(self, a):
        return next((m for m in [a["owner"]] + a["members"] if m["status"] == "accepted" and m["mode"] == "car"), None)

    def _notify(self, a, title, body, targets=None, exclude=None):
        ids = targets if targets is not None else [m.get("user_id") for m in [a["owner"]] + a["members"]]
        for uid in set(ids):
            if uid and uid != exclude:
                self.db.execute("INSERT INTO notifications(user_id,appointment_id,title,body,created_at) VALUES(?,?,?,?,?)", (uid, a["id"], title, body, now_iso()))

    def _invite(self, a, m):
        payload = base64.urlsafe_b64encode(json.dumps([a["id"], m["id"], a["created_at"]], separators=(",", ":")).encode()).decode().rstrip("=")
        sig = hmac.new(self.secret.encode(), payload.encode(), hashlib.sha256).hexdigest()
        return payload + "." + sig

    def _resolve_invite(self, token):
        try:
            payload, sig = token.split(".")
            correct = hmac.new(self.secret.encode(), payload.encode(), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(sig, correct):
                raise ValueError()
            aid, mid, created = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
            a = self._get(aid)
            m = next(m for m in a["members"] if m["id"] == mid)
            if created != a["created_at"]:
                raise ValueError()
            if a["trip"]["phase"] == "cancelled":
                raise ApiError(410, "취소된 약속이에요.")
            if a["trip"]["phase"] == "finished":
                raise ApiError(410, "이미 종료된 이동이에요.")
            if timestamp(a["meeting_at"]) + 86400 < time.time():
                raise ApiError(410, "만료된 초대예요.")
            return a, m
        except (ValueError, KeyError, StopIteration, TypeError):
            raise ApiError(404, "유효하지 않은 초대 링크예요.")

    def invitation(self, token):
        with self.lock:
            a, m = self._resolve_invite(token)
            return {"appointment_id": a["id"], "name": a["name"], "meeting_at": a["meeting_at"], "destination": a["destination"], "inviter": a["owner"]["name"], "member": {**{k: m.get(k) for k in ["id", "name", "status", "mode", "pickup"]}, "pickup_requested": m.get("pickup_requested", m["mode"] == "pickup")}, "joined": bool(m.get("user_id"))}

    def contact(self, uid, data):
        name = text(data.get("name"), 40, True)
        with self.lock, self.db:
            existing = self.db.execute("SELECT id,name FROM contacts WHERE owner_id=? AND name=?", (uid, name)).fetchone()
            if existing:
                return dict(existing)
            cid = secrets.token_hex(8)
            self.db.execute("INSERT INTO contacts VALUES(?,?,?,NULL)", (cid, uid, name))
            return {"id": cid, "name": name}

    def _participant(self, cid, name, mode="pickup"):
        return {"id": cid, "name": name, "user_id": None, "mode": mode, "pickup_requested": mode == "pickup", "status": "pending", "ready": "preparing", "origin": None, "pickup": None, "share": False, "location": None, "started_at": None, "arrived_at": None, "home_at": None, "travel_minutes": None}

    def create(self, user, data):
        with self.lock, self.db:
            aid = secrets.token_hex(12)
            meeting = data.get("meeting_at")
            timestamp(meeting)
            if timestamp(meeting) < time.time() - 300:
                raise ApiError(400, "앞으로의 약속 시간을 선택해 주세요.")
            origin, dest = point(data.get("origin")), point(data.get("destination"))
            mode = data.get("mode", "car")
            if mode not in ["car", "walk", "pickup"]:
                raise ApiError(400, "이동 방법을 확인해 주세요.")
            owner = self._participant("owner", user["name"], mode)
            owner.update(user_id=user["id"], status="accepted", origin=origin)
            a = {"id": aid, "owner_id": user["id"], "owner": owner, "name": text(data.get("name") or dest["name"] + " 약속", 80, True), "meeting_at": meeting, "origin": origin, "destination": dest, "members": [], "pickup_order": [], "parking": None, "trip": {"phase": "idle", "index": 0, "boarded": [], "started_at": None, "finished_at": None}, "track": [], "distance_m": 0, "messages": [], "revision": 0, "created_at": now_iso(), "updated_at": now_iso(), "reminders": []}
            self._update_members(a, data.get("members", []), user["id"])
            a["pickup_order"] = [m["id"] for m in a["members"] if m["mode"] == "pickup"]
            if "pickup_order" in data:
                order = data["pickup_order"]
                if not isinstance(order, list) or any(not isinstance(mid, str) for mid in order) or len(set(order)) != len(order) or set(order) != set(a["pickup_order"]):
                    raise ApiError(400, "픽업 순서를 확인해 주세요.")
                a["pickup_order"] = order
            self._save(a)
            return self._view(a, user["id"])

    def _update_members(self, a, requested, uid):
        if not isinstance(requested, list) or len(requested) > MAX_MEMBERS:
            raise ApiError(400, f"일행은 최대 {MAX_MEMBERS}명까지 추가할 수 있어요.")
        members, seen = [], set()
        old = {m["id"]: m for m in a["members"]}
        demo_contacts = {c["id"]: c for c in self._demo_contacts()} if self.demo_owner and uid == self.demo_owner else {}
        for item in requested:
            if not isinstance(item, dict):
                raise ApiError(400, "일행 목록을 확인해 주세요.")
            if "pickup" in item:
                raise ApiError(403, "픽업 위치는 픽업받는 사람이 자신의 웹 화면에서 설정해야 해요.")
            cid = item.get("id")
            if cid in seen:
                raise ApiError(400, "중복된 일행이 있어요.")
            seen.add(cid)
            if cid in old:
                m = old[cid]
            else:
                c = self.db.execute("SELECT id,name FROM contacts WHERE id=? AND owner_id=?", (cid, uid)).fetchone()
                if not c:
                    raise ApiError(400, "일행 목록을 확인해 주세요.")
                m = self._participant(c["id"], c["name"])
            requested_mode = item.get("mode", m["mode"])
            if requested_mode not in ["pickup", "walk", "car"]:
                raise ApiError(400, "일행의 이동 방법을 확인해 주세요.")
            m["pickup_requested"] = requested_mode == "pickup"
            m["mode"] = requested_mode
            if cid in demo_contacts and m["status"] == "pending" and not m.get("user_id"):
                m.update(user_id=self._demo_account(demo_contacts[cid])["user"]["id"], status="accepted")
            members.append(m)
        for m in a["members"]:
            if m["id"] not in seen:
                self._notify(a, "약속 참가 변경", a["name"] + "의 참가자에서 제외되었어요.", [m.get("user_id")])
        a["members"] = members

    def update(self, uid, aid, data):
        with self.lock, self.db:
            a = self._access(aid, uid)
            self._owner(a, uid)
            if "pickup" in data:
                raise ApiError(403, "픽업 위치는 픽업받는 사람만 설정할 수 있어요.")
            if int(data.get("revision", -1)) != a["revision"]:
                raise ApiError(409, "일행이 정보를 변경했어요. 최신 내용을 확인하고 다시 저장해 주세요.")
            if a["trip"]["phase"] in ["finished", "cancelled"]:
                raise ApiError(409, "종료된 약속은 수정할 수 없어요.")
            if a["trip"]["phase"] != "idle" and any(k in data for k in ["origin", "destination", "mode", "members", "pickup_order"]):
                raise ApiError(409, "출발한 이동의 참가자와 픽업 순서는 변경할 수 없어요. 현재 픽업을 취소하려면 픽업 취소를 이용해 주세요.")
            for field in ["name", "meeting_at"]:
                if field in data:
                    if field == "meeting_at":
                        timestamp(data[field])
                    a[field] = text(data[field], 120, True)
            for field in ["origin", "destination"]:
                if field in data:
                    a[field] = point(data[field])
                    if field == "origin":
                        a["owner"]["origin"] = a[field]
            if "mode" in data:
                if data["mode"] not in ["car", "walk", "pickup"]:
                    raise ApiError(400, "이동 방법을 확인해 주세요.")
                a["owner"]["mode"] = data["mode"]
            if "members" in data:
                self._update_members(a, data["members"], uid)
            if "pickup_order" in data:
                order = data["pickup_order"]
                eligible = {m["id"] for m in a["members"] if m["mode"] == "pickup"}
                if not isinstance(order, list) or len(set(order)) != len(order) or set(order) != eligible:
                    raise ApiError(400, "픽업 순서를 확인해 주세요.")
                a["pickup_order"] = order
            else:
                eligible = [m["id"] for m in a["members"] if m["mode"] == "pickup"]
                a["pickup_order"] = [x for x in a["pickup_order"] if x in eligible] + [x for x in eligible if x not in a["pickup_order"]]
            if "parking" in data:
                parking = data["parking"]
                if parking:
                    p = point(parking)
                    p.update(id=text(parking.get("id"), 80), parking_minutes=min(60, max(0, int(parking.get("parking_minutes", 5)))), walk_minutes=min(120, max(0, int(parking.get("walk_minutes", 5)))))
                    a["parking"] = p
                else:
                    a["parking"] = None
            self._save(a)
            self._notify(a, "약속 변경", a["name"] + "의 일정 또는 경로가 변경되었어요.", exclude=uid)
            return self._view(a, uid)

    def join(self, user, token, data):
        with self.lock, self.db:
            a, m = self._resolve_invite(token)
            if a["trip"]["phase"] != "idle":
                raise ApiError(409, "이미 출발한 이동이에요. 운전자에게 다음 약속의 초대를 요청해 주세요.")
            if a["owner_id"] == user["id"]:
                raise ApiError(409, "내가 만든 초대는 다른 기기에서 참가해 주세요.")
            if m.get("user_id") and m["user_id"] != user["id"]:
                raise ApiError(409, "다른 사람이 사용한 초대예요.")
            if any(x.get("user_id") == user["id"] and x["id"] != m["id"] for x in a["members"]):
                raise ApiError(409, "이미 이 약속에 참가했어요.")
            status = "declined" if data.get("decline") else "accepted"
            m.update(user_id=user["id"], status=status)
            if status == "accepted":
                self._member_changes(m, data)
                self.db.execute("UPDATE contacts SET user_id=? WHERE id=? AND owner_id=?", (user["id"], m["id"], a["owner_id"]))
            self._save(a)
            self._notify(a, "초대 " + ("거절" if status == "declined" else "수락"), m["name"] + "님이 " + a["name"] + " 초대에 응답했어요.", [a["owner_id"]])
            return self._view(a, user["id"]) if status == "accepted" else {"declined": True}

    def _member_changes(self, m, data):
        data = dict(data)
        if "drives" in data:
            if type(data["drives"]) is not bool:
                raise ApiError(400, "운전 여부를 확인해 주세요.")
            data["mode"] = "car" if data["drives"] else "pickup" if m.get("pickup_requested", m["mode"] == "pickup") else "walk"
        if data.get("mode") == "pickup" and not m.get("pickup_requested", m["mode"] == "pickup"):
            raise ApiError(403, "픽업 대상은 운전자가 선택해요.")
        if data.get("pickup") is not None and data.get("mode", m["mode"]) != "pickup":
            raise ApiError(403, "픽업받는 사람만 자신의 픽업 위치를 설정할 수 있어요.")
        if "mode" in data:
            if data["mode"] not in ["pickup", "walk", "car"]:
                raise ApiError(400, "이동 방법을 확인해 주세요.")
            m["mode"] = data["mode"]
            if m["mode"] != "pickup":
                m["pickup"] = None
        for name in ["origin", "pickup"]:
            if name in data:
                m[name] = point(data[name], False)
        if "ready" in data:
            if data["ready"] not in ["preparing", "ready", "walking", "waiting"]:
                raise ApiError(400, "준비 상태를 확인해 주세요.")
            m["ready"] = data["ready"]
        if "share" in data:
            m["share"] = bool(data["share"])
            if not m["share"]:
                m["location"] = None
        if "travel_minutes" in data:
            m["travel_minutes"] = min(1440, max(1, int(data["travel_minutes"])))

    def member_update(self, uid, aid, data):
        with self.lock, self.db:
            a = self._access(aid, uid)
            m = self._member(a, uid)
            if a["trip"]["phase"] == "cancelled":
                raise ApiError(409, "취소된 약속이에요.")
            if a["trip"]["phase"] == "finished" and any(k in data for k in ["mode", "drives", "pickup", "origin", "ready"]):
                raise ApiError(409, "종료된 이동의 참가 정보를 변경할 수 없어요.")
            if a["trip"]["phase"] in ["cancelled", "finished"] and any(k in data for k in ["share", "location"]):
                if data.get("share"):
                    raise ApiError(409, "종료된 이동의 위치 공유는 다시 시작할 수 없어요.")
            if a["trip"]["phase"] != "idle" and any(k in data for k in ["mode", "drives", "origin"]):
                raise ApiError(409, "출발한 이동의 이동 방법과 출발지는 바꿀 수 없어요.")
            if a["trip"]["phase"] != "idle" and "pickup" in data:
                raise ApiError(409, "출발한 뒤에는 픽업 위치를 바꿀 수 없어요. 운전자에게 메시지를 보내 주세요.")
            self._member_changes(m, data)
            action = data.get("action")
            if action == "depart":
                m["started_at"] = m.get("started_at") or now_iso()
                self._notify(a, "일행 출발", m["name"] + "님이 출발했어요.", exclude=uid)
            elif action == "arrive":
                m.update(arrived_at=now_iso(), share=False, location=None)
                self._notify(a, "일행 도착", m["name"] + "님이 약속 장소에 도착했어요.", exclude=uid)
            elif action == "home":
                m["home_at"] = now_iso()
                self._notify(a, "귀가 확인", m["name"] + "님이 귀가 확인을 남겼어요.", exclude=uid)
            elif action == "board_confirm":
                active = a["trip"].get("active_pickups", [])
                idx = a["trip"]["index"]
                if a["trip"]["phase"] != "arrived" or idx >= len(active) or active[idx] != m["id"]:
                    raise ApiError(409, "내 픽업 장소에 차량이 도착한 뒤 확인해 주세요.")
                m["boarding_confirmed_at"] = now_iso()
                m["ready"] = "waiting"
                driver = self._driver(a)
                self._notify(a, "탑승 확인 요청", m["name"] + "님이 탑승 확인을 보냈어요.", [driver.get("user_id") if driver else a["owner_id"]])
            if m["mode"] == "pickup" and m["id"] not in a["pickup_order"]:
                a["pickup_order"].append(m["id"])
            a["pickup_order"] = [x for x in a["pickup_order"] if any(y["id"] == x and y["mode"] == "pickup" for y in a["members"])]
            self._save(a)
            if "ready" in data or "pickup" in data:
                driver = self._driver(a)
                self._notify(a, "픽업 상태 변경", m["name"] + "님의 픽업 정보가 변경되었어요.", [driver.get("user_id") if driver else a["owner_id"]], exclude=uid)
            return self._view(a, uid)

    def _navigation_status(self, a, data):
        # Guidance can stop without cancelling the appointment or claiming arrival.
        import re
        status, session = data.get("status"), data.get("session_id")
        requested = data.get("requested_at")
        if status not in {"requested", "guiding", "stopped"} or not isinstance(session, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", session):
            raise ApiError(400, "내비 안내 상태를 확인해 주세요.")
        if type(requested) not in [int, float] or not math.isfinite(requested) or requested <= 0 or requested > time.time()*1000+10000:
            raise ApiError(400, "내비 안내 시각을 확인해 주세요.")
        if a["trip"]["phase"] == "idle":
            raise ApiError(409, "출발한 일정의 안내 상태만 변경할 수 있어요.")
        previous = a["trip"].get("navigation", {})
        if previous:
            if previous.get("session_id") == session:
                ranks = {"requested": 0, "guiding": 1, "stopped": 2}
                if ranks.get(previous.get("status"), -1) >= ranks[status]:
                    return False
            elif requested <= previous.get("requested_at", 0):
                return False
        a["trip"]["navigation"] = {"status": status, "session_id": session, "requested_at": requested,
                                    "reason": text(data.get("reason"), 80), "updated_at": now_iso()}
        a["trip"].pop("navigation_etas", None)
        return True

    def _update_navigation_etas(self, a, data):
        trip = a["trip"]
        navigation = trip.get("navigation", {})
        if navigation.get("status") != "guiding" or data.get("session_id") != navigation.get("session_id"):
            return False
        entries = data.get("stops")
        if not isinstance(entries, dict) or len(entries) > MAX_MEMBERS + 1:
            raise ApiError(400, "내비 도착 시간을 확인해 주세요.")
        final_id = "lot" if a.get("parking") else "dest"
        allowed = {final_id, *trip.get("active_pickups", [])[trip["index"]:]}
        now = time.time() * 1000
        saved = trip.setdefault("navigation_etas", {})
        changed = False
        for stop, entry in entries.items():
            if stop not in allowed or not isinstance(entry, dict):
                continue
            seconds, observed = entry.get("seconds"), entry.get("observed_at")
            if type(seconds) not in (int, float) or not 0 <= seconds <= 86400:
                continue
            if type(observed) not in (int, float) or not max(now - 30_000, navigation["requested_at"]) <= observed <= now + 5_000:
                continue
            if observed <= saved.get(stop, {}).get("observed_at", 0):
                continue
            saved[stop] = {"observed_at": observed, "seconds": seconds}
            changed = True
        return changed

    def action(self, uid, aid, data):
        with self.lock, self.db:
            a = self._access(aid, uid)
            driver, t, action = self._driver(a), a["trip"], data.get("action")
            if action == "cancel":
                self._owner(a, uid)
            elif not driver or driver.get("user_id") != uid:
                raise ApiError(403, "운전자만 주행 상태를 변경할 수 있어요.")
            if action == "auto_board" and data.get("observation_id"):
                matched = next((m for m in a["members"] if m.get("seat_binding", {}).get("observation_id") == data["observation_id"]), None)
                if matched:
                    return self._view(a, uid)
            if action == "auto_alight" and data.get("observation_id"):
                matched = next((m for m in a["members"] if (m.get("alight") or {}).get("observation_id") == data["observation_id"]), None)
                if matched:
                    return self._view(a, uid)
            if t["phase"] in ["cancelled", "finished"]:
                raise ApiError(409, "이미 종료된 이동이에요.")
            eligible = [m for key in a["pickup_order"] for m in a["members"] if m["id"] == key and m["status"] == "accepted" and m["mode"] == "pickup"]
            if t["phase"] == "idle":
                t["active_pickups"] = [m["id"] for m in eligible]
            else:
                eligible = [m for mid in t.get("active_pickups", []) for m in a["members"] if m["id"] == mid]
            current = eligible[t["index"]] if t["index"] < len(eligible) else None
            if action == "navigation_status":
                if not self._navigation_status(a, data):
                    return self._view(a, uid)
            elif action == "navigation_eta":
                if not self._update_navigation_etas(a, data):
                    return self._view(a, uid)
            elif action == "auto_arrive":
                if t["phase"] not in ["driving", "near", "arrived"]:
                    raise ApiError(409, "현재 픽업 구간이 아니에요.")
                self._auto_evidence(a, current, data)
                t["phase"] = "arrived"
            elif action == "auto_board":
                if t["phase"] not in ["driving", "near", "arrived"]:
                    raise ApiError(409, "현재 픽업 구간이 아니에요.")
                binding = self._auto_evidence(a, current, data, True)
                current.update(boarded_at=now_iso(), ready="waiting", seat_binding=binding)
                t["boarded"].append(current["id"])
                t["index"] += 1
                t["phase"] = "driving" if t["index"] < len(eligible) else "destination"
                self._notify(a, "탑승 완료", current["name"] + "님이 탑승했어요.", exclude=uid)
            elif action == "auto_alight":
                if t["phase"] not in ["driving", "near", "arrived", "destination", "parked"]:
                    raise ApiError(409, "이동 중에만 하차를 확인할 수 있어요.")
                member = next((m for m in a["members"] if m["id"] == data.get("member_id")), None)
                if not member or not member.get("boarded_at") or not member.get("seat_binding"):
                    raise ApiError(409, "좌석 승차가 확인된 일행만 하차를 확인할 수 있어요.")
                if member.get("alighted_at"):
                    raise ApiError(409, "이미 하차한 일행이에요.")
                member.update(alighted_at=now_iso(), alight=self._alight_evidence(member, data))
                t.setdefault("alighted", []).append(member["id"])
                self._notify(a, "하차 완료", member["name"] + "님이 하차했어요.", exclude=uid)
            elif action == "depart":
                if t["phase"] != "idle":
                    raise ApiError(409, "이미 출발한 이동이에요.")
                if any(not m.get("pickup") for m in eligible):
                    raise ApiError(409, "픽업할 일행이 만날 장소를 지정해야 해요.")
                t.update(phase="driving" if eligible else "destination", started_at=now_iso())
                driver["started_at"] = t["started_at"]
                planned = self.route(uid, aid)
                start_time = timestamp(t["started_at"])
                t["planned"] = {"departure_at": t["started_at"], "parking_at": datetime.fromtimestamp(start_time + (planned["drive_minutes"] + planned["parking_minutes"]) * 60, timezone.utc).isoformat(), "arrival_at": datetime.fromtimestamp(start_time + planned["total_minutes"] * 60, timezone.utc).isoformat(), "distance_m": planned["distance_m"], "total_minutes": planned["total_minutes"]}
                elapsed = 0
                t["planned"]["pickup_arrivals"] = {}
                for leg, stop in zip(planned["legs"], planned["stops"][1:]):
                    elapsed += leg["duration_s"]
                    if stop["kind"] == "pickup":
                        t["planned"]["pickup_arrivals"][stop["id"]] = datetime.fromtimestamp(start_time + elapsed, timezone.utc).isoformat()
                self._notify(a, "운전자 출발", driver["name"] + "님이 출발했어요.", exclude=uid)
            elif action == "arrive":
                if not current or t["phase"] not in ["driving", "near"]:
                    raise ApiError(409, "현재 픽업 구간에서 도착을 확인해 주세요.")
                t["phase"] = "arrived"
                self._notify(a, "픽업 장소 도착", driver["name"] + "님이 픽업 장소에 도착했어요.", [current.get("user_id")])
            elif action == "notify":
                if not current or t["phase"] != "arrived":
                    raise ApiError(409, "픽업 장소 도착 후 알려 주세요.")
                self._notify(a, "운전자 도착 알림", driver["name"] + "님이 기다리고 있어요.", [current.get("user_id")])
            elif action == "board":
                if not current or t["phase"] != "arrived":
                    raise ApiError(409, "도착 확인 후 탑승을 확인해 주세요.")
                if current["ready"] not in ["ready", "waiting"]:
                    raise ApiError(409, "상대가 준비 완료 또는 장소 도착 상태여야 해요.")
                current["boarded_at"] = now_iso()
                t["boarded"].append(current["id"])
                t["index"] += 1
                t["phase"] = "driving" if t["index"] < len(eligible) else "destination"
                self._notify(a, "탑승 완료", current["name"] + "님이 탑승했어요.", exclude=uid)
            elif action == "skip":
                if not current or t["phase"] not in ["driving", "near", "arrived"]:
                    raise ApiError(409, "현재 픽업 구간에서 취소해 주세요.")
                current["mode"] = "walk"
                t["active_pickups"].remove(current["id"])
                a["pickup_order"] = [mid for mid in a["pickup_order"] if mid != current["id"]]
                t["phase"] = "driving" if t["index"] < len(t["active_pickups"]) else "destination"
                self._notify(a, "픽업 취소", current["name"] + "님의 픽업을 취소했어요. 각자 이동 방법을 확인해 주세요.", exclude=uid)
            elif action == "park":
                if t["phase"] != "destination":
                    raise ApiError(409, "목적지로 이동하는 구간에서 주차를 확인해 주세요.")
                t.update(phase="parked", parked_at=now_iso())
            elif action in {"finish", "auto_finish"}:
                if action == "auto_finish":
                    self._validate_destination_arrival(a, data)
                elif t["phase"] != "parked":
                    raise ApiError(409, "주차 완료 후 이동을 종료해 주세요.")
                t.update(phase="finished", finished_at=now_iso())
                driver["arrived_at"] = t["finished_at"]
                self._notify(a, "이동 완료", a["name"] + "의 차량 이동을 마쳤어요.", exclude=uid)
            elif action == "cancel":
                t.update(phase="cancelled", finished_at=now_iso())
                self._notify(a, "약속 취소", a["name"] + " 약속이 취소되었어요.", exclude=uid)
            else:
                raise ApiError(400, "지원하지 않는 동작이에요.")
            if t["phase"] in ["finished", "cancelled"]:
                if "navigation" in t:
                    t["navigation"].update(status="stopped", reason="app_ended", updated_at=now_iso())
                for m in [a["owner"]] + a["members"]:
                    m.update(share=False, location=None)
            self._save(a)
            return self._view(a, uid)

    def _validate_destination_arrival(self, a, data):
        trip = a["trip"]
        navigation = trip.get("navigation", {})
        session = data.get("session_id")
        arrival_event = data.get("source") == "arrival"
        valid_status = navigation.get("status") == "guiding" or (
            arrival_event and navigation.get("status") == "stopped" and navigation.get("reason") == "oem_stopped"
        )
        if trip["phase"] not in {"destination", "parked"} or not session or navigation.get("session_id") != session or not valid_status:
            raise ApiError(409, "현재 목적지 안내에서만 도착을 확인할 수 있어요.")
        observed = data.get("observed_at")
        now_ms = time.time() * 1000
        if type(observed) not in (int, float) or not max(navigation["requested_at"], now_ms - 25000) <= observed <= now_ms + 5000:
            raise ApiError(409, "최근 도착 정보가 필요해요.")
        target = a.get("parking") or a["destination"]
        position = point({"lat": data.get("lat"), "lon": data.get("lon"), "name": "도착 위치"})
        meters = data.get("remaining_m")
        if distance(position, target) > 80 or (
            not arrival_event and (data.get("source") != "nearby" or type(meters) not in (int, float) or not 0 <= meters <= 80)
        ):
            raise ApiError(409, "목적지에 가까워지면 이동이 종료돼요.")

    def location(self, uid, aid, data):
        with self.lock, self.db:
            a = self._access(aid, uid)
            m = self._member(a, uid)
            if not m["share"] or not m.get("started_at") or m.get("arrived_at") or a["trip"]["phase"] in ["cancelled", "finished"]:
                raise ApiError(409, "위치 공유 중인 이동에서만 위치를 전송할 수 있어요.")
            p = point({**data, "name": "현재 위치"})
            accuracy = float(data.get("accuracy", 100))
            if not math.isfinite(accuracy) or accuracy < 0 or accuracy > 100:
                raise ApiError(400, "위치 정확도가 충분하지 않아요.")
            p.update(accuracy=accuracy, updated_at=now_iso(), mock=bool(data.get("mock", False)))
            driver = self._driver(a)
            if driver and driver.get("user_id") == uid:
                track = a["track"]
                if track:
                    old = track[-1]
                    dt = time.time() - timestamp(old["updated_at"])
                    delta = distance(old, p)
                    if dt < 1:
                        return {"stored": False}
                    if delta / dt > 60:
                        raise ApiError(400, "위치가 갑자기 이동해 이번 기록을 제외했어요.")
                    if delta > max(3, accuracy / 2):
                        a["distance_m"] += delta
                if len(track) < 20000:
                    track.append(p)
                active = a["trip"].get("active_pickups", [])
                idx = a["trip"]["index"]
                current = next((x for x in a["members"] if idx < len(active) and x["id"] == active[idx]), None)
                if current and current.get("pickup") and a["trip"]["phase"] == "driving" and distance(p, current["pickup"]) < 150:
                    a["trip"]["phase"] = "near"
                    self._notify(a, "곧 도착해요", "운전자가 픽업 장소 근처에 도착했어요.", [current.get("user_id")])
            m["location"] = p
            self._save(a)
            return {"stored": True}

    def demo_position(self, uid, aid, data):
        with self.lock, self.db:
            a = self._access(aid, uid)
            driver = self._driver(a)
            if a["owner_id"] != self.demo_owner or not driver or driver["user_id"] != uid or data.get("mock") is not True:
                raise ApiError(403, "시연 차량의 가상 위치만 공유할 수 있어요.")
            if a["trip"]["phase"] not in {"driving", "near", "arrived", "destination"}:
                raise ApiError(409, "이동 중에만 시연 위치를 공유할 수 있어요.")
            observed = data.get("observed_at")
            now = time.time() * 1000
            if type(observed) not in (int, float) or not now - 20_000 <= observed <= now + 5_000:
                raise ApiError(400, "최근 시연 위치를 확인해 주세요.")
            if observed <= a["trip"].get("demo_position", {}).get("observed_at", 0):
                return {"stored": False}
            p = point({**data, "name": "시연 차량"})
            a["trip"]["demo_position"] = {"lat": p["lat"], "lon": p["lon"], "mock": True,
                                            "observed_at": observed, "updated_at": datetime.fromtimestamp(observed / 1000, timezone.utc).isoformat()}
            self._save(a)
            return {"stored": True}

    def message(self, uid, aid, data):
        with self.lock, self.db:
            a = self._access(aid, uid)
            m = self._member(a, uid)
            body = text(data.get("body"), 500, True)
            msg = {"id": secrets.token_hex(8), "sender": m["name"], "sender_id": uid, "body": body, "created_at": now_iso()}
            a["messages"] = (a["messages"] + [msg])[-100:]
            self._save(a)
            self._notify(a, m["name"] + "님의 메시지", body, exclude=uid)
            return msg

    def _travel_minutes(self, a, member, driver_minutes=None):
        driver = self._driver(a)
        if driver and member.get("user_id") == driver.get("user_id"):
            if driver_minutes is not None:
                return driver_minutes
            stops = [driver.get("origin") or a["origin"]]
            stops += [m["pickup"] for mid in a["pickup_order"] for m in a["members"] if m["id"] == mid and m["status"] == "accepted" and m.get("pickup") and m["mode"] == "pickup"]
            stops += [a.get("parking") or a["destination"]]
            drive = max(1, math.ceil(sum(distance(x, y) * 1.35 / 6.9 for x, y in zip(stops, stops[1:])) / 60))
            return drive + (a.get("parking") or {}).get("parking_minutes", 0) + (a.get("parking") or {}).get("walk_minutes", 0)
        if member.get("travel_minutes"):
            return member["travel_minutes"]
        if member.get("origin"):
            meters = distance(member["origin"], a["destination"])
            return max(1, math.ceil(meters * 1.2 / 75 if member["mode"] == "walk" else meters * 1.35 / 6.9 / 60))
        return None

    def _timing(self, a, member, driver_minutes=None):
        leaders = [m for m in [a["owner"]] + a["members"] if m["status"] == "accepted" and m["mode"] != "pickup" and m.get("started_at")]
        leaders.sort(key=lambda m: timestamp(m["started_at"]))
        leader = leaders[0] if leaders else None
        target = timestamp(a["meeting_at"]) - 300
        if leader:
            travel = self._travel_minutes(a, leader, driver_minutes)
            if leader.get("arrived_at"):
                target = timestamp(leader["arrived_at"])
            elif travel:
                target = timestamp(leader["started_at"]) + travel * 60
        travel = self._travel_minutes(a, member, driver_minutes) if member["mode"] != "pickup" else None
        return {"travel_minutes": travel, "arrival_target": datetime.fromtimestamp(target, timezone.utc).isoformat(), "departure_at": datetime.fromtimestamp(target - travel * 60, timezone.utc).isoformat() if travel else None, "leader": leader["name"] if leader else None, "basis": "거리 기반 추정 · 교통정보 미반영"}

    def _view(self, a, uid):
        v = json.loads(json.dumps(a))
        member = self._member(a, uid)
        v["me_id"] = member["id"] if member else None
        v["is_owner"] = uid == a["owner_id"]
        v["timing"] = self._timing(a, member)
        driver = self._driver(a)
        v["driver_id"] = driver["user_id"] if driver else None
        v["is_driver"] = bool(driver and driver["user_id"] == uid)
        if not v["is_owner"]:
            v.pop("origin", None)
        for m in [v["owner"]] + v["members"]:
            own = m.get("user_id") == uid
            if not own:
                m.pop("origin", None)
            if not m.get("share") or a["trip"]["phase"] in ["idle", "cancelled", "finished"]:
                m["location"] = None
            if not own:
                m.pop("travel_minutes", None)
            if not self.demo_owner and v["is_owner"] and m["id"] != "owner":
                m["invite_token"] = self._invite(a, m)
        if not v["is_driver"]:
            v.pop("track", None)
        demo_position = v["trip"].pop("demo_position", None)
        if demo_position and driver and a["trip"]["phase"] in {"driving", "near", "arrived", "destination"} and time.time() - timestamp(demo_position["updated_at"]) < 45:
            visible_driver = next(m for m in [v["owner"], *v["members"]] if m["user_id"] == driver["user_id"])
            visible_driver["location"] = demo_position
        return v

    def database_overview(self):
        tables = ("appointments", "users", "contacts", "seat_profiles", "pickup_stops")
        totals = {table: self.db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] for table in tables}
        rows = self.db.execute("SELECT document FROM appointments ORDER BY json_extract(document, '$.updated_at') DESC LIMIT 50")
        appointments = []
        for row in rows:
            a = json.loads(row["document"])
            meeting = datetime.fromtimestamp(timestamp(a["meeting_at"]), KST).strftime("%Y-%m-%d %H:%M")
            appointments.append({"destination": a["destination"]["name"], "meeting_at": meeting,
                                 "phase": a["trip"]["phase"], "riders": len(a["members"]),
                                 "pickups": len(a["pickup_order"]), "boarded": len(a["trip"]["boarded"])})
        return totals, appointments

    def state(self, user):
        with self.lock:
            uid = user["id"]
            appointments = []
            for row in self.db.execute("SELECT document FROM appointments"):
                a = json.loads(row[0])
                if self._member(a, uid):
                    appointments.append(self._view(a, uid))
            appointments.sort(key=lambda x: x["meeting_at"])
            contacts = [dict(row) for row in self.db.execute("SELECT id,name,user_id FROM contacts WHERE owner_id=? ORDER BY rowid", (uid,))]
            notifications = [dict(row) for row in self.db.execute("SELECT id,appointment_id,title,body,created_at FROM notifications WHERE user_id=? ORDER BY id DESC LIMIT 100", (uid,))]
            return {"user": user, "appointments": appointments, "contacts": contacts, "notifications": notifications, "seat_profile": self.seat_profile(uid), "auto_pickup_proximity_required": self.pickup_require_proximity, "server_time": now_iso(), "places": PLACES, "parking": PARKING}

    def route(self, uid, aid):
        with self.lock:
            a = self._access(aid, uid)
            driver = self._driver(a)
            origin = (driver or a["owner"]).get("origin") or a["origin"]
            ordered = [m for mid in a["pickup_order"] for m in a["members"] if m["id"] == mid and m["status"] == "accepted" and m["mode"] == "pickup" and m.get("pickup")]
            parking = a.get("parking")
            stops = [{"kind": "start", "id": "start", **origin}] + [{"kind": "pickup", "id": m["id"], "person": m["name"], **m["pickup"]} for m in ordered] + [{"kind": "lot" if parking else "dest", "id": "lot" if parking else "dest", **(parking or a["destination"])}]
            key = json.dumps([(s["lat"], s["lon"]) for s in stops]) + self.route_provider
        with self.route_lock:
            cached = self.route_cache.get(key)
        if cached and time.time() - cached[0] < 300:
            result = json.loads(json.dumps(cached[1]))
        else:
            legs = [{"distance_m": distance(x, y) * 1.35, "duration_s": distance(x, y) * 1.35 / 6.9} for x, y in zip(stops, stops[1:])]
            result = {"provider": "estimate", "traffic": False, "geometry": [[s["lon"], s["lat"]] for s in stops], "legs": legs, "notice": "직선거리 기반 추정 · 교통정보 미반영"}
            if self.route_provider == "osrm" and driver:
                coords = ";".join(f'{s["lon"]},{s["lat"]}' for s in stops)
                url = "https://router.project-osrm.org/route/v1/driving/" + coords + "?overview=full&geometries=geojson&steps=false"
                try:
                    request = urllib.request.Request(url, headers={"User-Agent": "Pooly/1.0"})
                    import ssl
                    with urllib.request.urlopen(request, timeout=5, context=ssl.create_default_context(cafile="/etc/ssl/cert.pem" if Path("/etc/ssl/cert.pem").exists() else None)) as response:
                        data = json.load(response)
                    r = data["routes"][0]
                    result.update(provider="osrm", geometry=r["geometry"]["coordinates"], legs=[{"distance_m": x["distance"], "duration_s": x["duration"]} for x in r["legs"]], notice="도로 경로 추정 · 교통정보 미반영")
                except Exception:
                    pass
            with self.route_lock:
                self.route_cache[key] = (time.time(), json.loads(json.dumps(result)))
        result["stops"] = stops
        result["distance_m"] = sum(x["distance_m"] for x in result["legs"])
        result["drive_minutes"] = max(1, math.ceil(sum(x["duration_s"] for x in result["legs"]) / 60))
        result["parking_minutes"] = (parking or {}).get("parking_minutes", 0)
        result["walk_minutes"] = (parking or {}).get("walk_minutes", 0)
        result["total_minutes"] = result["drive_minutes"] + result["parking_minutes"] + result["walk_minutes"]
        timing = self._timing(a, driver or a["owner"], result["total_minutes"])
        result["arrival_target"] = timing["arrival_target"]
        result["recommended_departure"] = timing["departure_at"] or datetime.fromtimestamp(timestamp(a["meeting_at"]) - (result["total_minutes"] + 5) * 60, timezone.utc).isoformat()
        result["destination"] = a["destination"]
        if not driver or driver.get("user_id") != uid:
            result["stops"] = [s for s in result["stops"] if s["kind"] != "start"]
            loc = driver.get("location") if driver and driver.get("share") else None
            result["geometry"] = [[loc["lon"], loc["lat"]]] + [[s["lon"], s["lat"]] for s in result["stops"]] if loc else []
        return result

    def reminders(self):
        """Durable, idempotent departure reminders for accepted independent travelers."""
        with self.lock, self.db:
            for row in list(self.db.execute("SELECT document FROM appointments")):
                a = json.loads(row[0])
                if a["trip"]["phase"] in ["finished", "cancelled"]:
                    continue
                changed = False
                for m in [a["owner"]] + a["members"]:
                    if m["status"] != "accepted" or m["mode"] == "pickup" or m.get("started_at"):
                        continue
                    timing = self._timing(a, m)
                    if not timing["departure_at"]:
                        continue
                    due = timestamp(timing["departure_at"])
                    marker = m["id"] + ":" + a["meeting_at"]
                    if due <= time.time() < timestamp(a["meeting_at"]) + 1800 and marker not in a["reminders"]:
                        body = (timing["leader"] + "님의 예상 도착에 맞춰 지금 출발해 주세요. " if timing["leader"] else a["name"] + "에 맞춰 지금 출발해 주세요. ") + timing["basis"]
                        self._notify(a, "출발할 시간이에요", body, [m.get("user_id")])
                        a["reminders"].append(marker)
                        changed = True
                if changed:
                    self._save(a)
