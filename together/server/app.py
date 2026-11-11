#!/usr/bin/env python3
"""Local development HTTP API and the shared vehicle/mobile UI."""
from __future__ import annotations

import argparse
import json
import mimetypes
import os
import re
import signal
import ssl
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

try:
    from .domain import ApiError, PLACES, Store, text
except ImportError:
    from domain import ApiError, PLACES, Store, text

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "web"


class AppServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, store):
        super().__init__(address, Handler)
        self.store = store
        self.search_cache = {}
        self.search_lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    server: AppServer
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        # Invitation and session tokens must not be written to an access log.
        return

    def send(self, status, value, mime="application/json; charset=utf-8"):
        data = json.dumps(value, ensure_ascii=False).encode() if mime.startswith("application/json") else value
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; font-src 'self' data:; img-src 'self' data: blob: https://tiles.openfreemap.org; connect-src 'self' https://tiles.openfreemap.org; worker-src 'self' blob:; object-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'self'")
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 <= length <= 200000:
                raise ValueError()
            data = json.loads(self.rfile.read(length) or b"{}")
            if not isinstance(data, dict):
                raise ValueError()
            return data
        except (ValueError, UnicodeDecodeError):
            raise ApiError(400, "요청 내용을 확인해 주세요.")

    def do_GET(self):
        self.handle_request("GET")

    def do_POST(self):
        self.handle_request("POST")

    def do_PATCH(self):
        self.handle_request("PATCH")

    def handle_request(self, method):
        try:
            parsed = urllib.parse.urlsplit(self.path)
            path = urllib.parse.unquote(parsed.path)
            query = urllib.parse.parse_qs(parsed.query)
            if method == "GET" and path == "/health":
                return self.send(200, {"ok": True, "service": "together", "version": "0.2.0"})
            if not path.startswith("/api/"):
                if method != "GET":
                    raise ApiError(405, "지원하지 않는 요청이에요.")
                relative = path.lstrip("/") or "car.html"
                target = (WEB / relative).resolve()
                if not target.is_relative_to(WEB.resolve()) or not target.is_file() or target.suffix not in {".html", ".js", ".css", ".woff2", ".png", ".svg", ".json"}:
                    raise ApiError(404, "페이지를 찾을 수 없어요.")
                return self.send(200, target.read_bytes(), mimetypes.guess_type(str(target))[0] or "application/octet-stream")
            if method != "GET":
                origin = self.headers.get("Origin")
                expected = "http://" + self.headers.get("Host", "")
                if origin and origin != expected:
                    raise ApiError(403, "이 출처의 요청은 허용되지 않아요.")
            data = self.body() if method != "GET" else {}
            store = self.server.store
            if method == "POST" and path == "/api/sessions":
                return self.send(201, store.register(data))
            match = re.fullmatch(r"/api/invites/([A-Za-z0-9_.-]{1,600})", path)
            if method == "GET" and match:
                return self.send(200, store.invitation(match[1]))
            bearer = self.headers.get("Authorization", "")
            token = bearer[7:] if bearer.startswith("Bearer ") else ""
            user = store.user(token)
            uid = user["id"]
            if path == "/api/seat-profile" and method in ["GET", "POST"]:
                return self.send(200, store.seat_profile(uid, data if method == "POST" else None))
            if method == "GET" and path == "/api/state":
                return self.send(200, store.state(user))
            if method == "POST" and path == "/api/contacts":
                return self.send(201, store.contact(uid, data))
            if method == "POST" and path == "/api/appointments":
                return self.send(201, store.create(user, data))
            if method == "GET" and path == "/api/places":
                return self.send(200, self.search(query.get("q", [""])[0]))
            match = re.fullmatch(r"/api/invites/([A-Za-z0-9_.-]{1,600})/join", path)
            if method == "POST" and match:
                return self.send(200, store.join(user, match[1], data))
            match = re.fullmatch(r"/api/appointments/([a-f0-9]{24})(?:/(route|actions|member|location|messages|seat-personalization))?", path)
            if match:
                aid, action = match.groups()
                if method == "GET" and action == "route":
                    return self.send(200, store.route(uid, aid))
                if method == "PATCH" and action is None:
                    return self.send(200, store.update(uid, aid, data))
                if method == "POST":
                    handlers = {"actions": store.action, "member": store.member_update, "location": store.location, "messages": store.message, "seat-personalization": store.seat_personalization}
                    if action in handlers:
                        return self.send(200, handlers[action](uid, aid, data))
            raise ApiError(404, "요청을 찾을 수 없어요.")
        except ApiError as error:
            self.send(error.status, {"error": error.message})
        except (ValueError, TypeError, KeyError, OverflowError):
            self.send(400, {"error": "입력한 내용을 확인해 주세요."})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            print("API error:", type(error).__name__, flush=True)
            self.send(500, {"error": "처리하지 못했어요. 잠시 후 다시 시도해 주세요."})

    def search(self, query):
        from .places import PlaceSearch
        if not hasattr(self.server, "place_search"):
            self.server.place_search = PlaceSearch()
        return self.server.place_search.search(query)


def make_server(port=8765, database=None, route_provider="estimate"):
    database = database or ROOT / "data/together.sqlite3"
    return AppServer(("127.0.0.1", port), Store(database, route_provider))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--database", default=str(ROOT / "data/together.sqlite3"))
    parser.add_argument("--route-provider", choices=["estimate", "osrm"], default=os.environ.get("TOGETHER_ROUTE_PROVIDER", "estimate"))
    args = parser.parse_args()
    server = make_server(args.port, args.database, args.route_provider)
    stop = threading.Event()

    def reminders():
        while not stop.wait(10):
            try:
                server.store.reminders()
            except Exception as error:
                print("Reminder error:", type(error).__name__, flush=True)

    threading.Thread(target=reminders, daemon=True).start()
    print(f"Together development server: http://127.0.0.1:{server.server_port}", flush=True)
    print("Local device access only. Use adb reverse for Android devices.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()
        server.store.close()


if __name__ == "__main__":
    main()
