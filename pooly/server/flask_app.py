"""Flask/WSGI API. Importing this module never starts a web server or scheduler."""
from __future__ import annotations

import os
import re
from pathlib import Path
from urllib.parse import urlsplit

import click
from flask import Flask, g, jsonify, redirect, render_template, request, send_from_directory
from werkzeug.exceptions import HTTPException
from werkzeug.security import check_password_hash

from .domain import ApiError, MAX_PICKUPS, Store
from .places import PlaceSearch

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "web"
CSP = "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; font-src 'self' data:; img-src 'self' data: blob: https://tiles.openfreemap.org; connect-src 'self' https://tiles.openfreemap.org; worker-src 'self' blob:; object-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'self'"


def create_app(config=None):
    app = Flask(__name__, static_folder=None)
    app.config.from_mapping(
        DATABASE=os.environ.get("POOLY_DATABASE_PATH", str(ROOT / "data/pooly-flask.sqlite3")),
        PUBLIC_ORIGIN=os.environ.get("POOLY_PUBLIC_ORIGIN", "").rstrip("/"),
        ROUTE_PROVIDER=os.environ.get("POOLY_ROUTE_PROVIDER", "estimate"),
        SQLITE_JOURNAL_MODE=os.environ.get("POOLY_SQLITE_JOURNAL_MODE", "DELETE"),
        MAX_CONTENT_LENGTH=200000,
        PICKUP_REQUIRE_PROXIMITY=os.environ.get("POOLY_PICKUP_REQUIRE_PROXIMITY", "1") != "0",
        DEMO_OWNER=os.environ.get("POOLY_DEMO_OWNER", ""),
        PUBLIC_DEMO_OWNER=os.environ.get("POOLY_PUBLIC_DEMO_OWNER", ""),
        DATABASE_VIEW_PASSWORD_HASH=os.environ.get("POOLY_DATABASE_VIEW_PASSWORD_HASH", ""),
    )
    if config:
        app.config.update(config)
    origin = app.config["PUBLIC_ORIGIN"]
    local_demo_owner = app.config["DEMO_OWNER"]
    public_demo_owner = app.config["PUBLIC_DEMO_OWNER"]
    demo_owner = local_demo_owner or public_demo_owner
    if local_demo_owner:
        if origin or not re.fullmatch(r"[a-f0-9]{24}", local_demo_owner):
            raise ValueError("The demo requires a local vehicle owner and no public origin.")
        app.config["TRUSTED_HOSTS"] = ["127.0.0.1", "localhost"]
    if public_demo_owner and (local_demo_owner or not origin.startswith("https://") or not re.fullmatch(r"[a-f0-9]{24}", public_demo_owner)):
        raise ValueError("The public demo requires a dedicated demo owner and HTTPS.")
    if origin:
        parsed = urlsplit(origin)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.path or parsed.query or parsed.fragment or parsed.username:
            raise ValueError("POOLY_PUBLIC_ORIGIN must be an origin such as https://name.pythonanywhere.com")
        app.config["TRUSTED_HOSTS"] = [parsed.hostname]
    app.json.ensure_ascii = False
    app.extensions["place_search"] = PlaceSearch()

    def store():
        if "store" not in g:
            g.store = Store(app.config["DATABASE"], app.config["ROUTE_PROVIDER"], app.config["SQLITE_JOURNAL_MODE"], pickup_require_proximity=app.config["PICKUP_REQUIRE_PROXIMITY"], demo_owner=demo_owner)
        return g.store

    @app.teardown_appcontext
    def close_store(error=None):
        connection = g.pop("store", None)
        if connection:
            connection.db.rollback()
            connection.close()

    @app.before_request
    def check_api_origin():
        if request.path.startswith("/api/") and request.method not in {"GET", "HEAD", "OPTIONS"}:
            expected = app.config["PUBLIC_ORIGIN"] or request.host_url.rstrip("/")
            origin_header = request.headers.get("Origin")
            if origin_header and origin_header != expected:
                raise ApiError(403, "이 출처의 요청은 허용되지 않아요.")

    @app.after_request
    def response_headers(response):
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Content-Security-Policy"] = CSP
        return response

    @app.errorhandler(ApiError)
    def api_error(error):
        return jsonify(error=error.message), error.status

    @app.errorhandler(HTTPException)
    def http_error(error):
        return jsonify(error="요청 내용이나 주소를 확인해 주세요."), error.code

    @app.errorhandler(Exception)
    def unexpected_error(error):
        # Do not include URLs, bearer tokens, invitation tokens, or request bodies.
        app.logger.error("API failure: %s", type(error).__name__)
        return jsonify(error="처리하지 못했어요. 잠시 후 다시 시도해 주세요."), 500

    @app.get("/health")
    def health():
        return jsonify(ok=True, service="pooly", framework="flask", version="1.0.0", max_pickups=MAX_PICKUPS)

    @app.get("/database")
    def database_view():
        local = not origin and request.remote_addr in {"127.0.0.1", "::1"} and urlsplit(request.host_url).hostname in {"localhost", "127.0.0.1", "::1"}
        if not local:
            password_hash = app.config["DATABASE_VIEW_PASSWORD_HASH"]
            if not origin.startswith("https://") or not password_hash:
                raise ApiError(404, "페이지를 찾을 수 없어요.")
            if not request.is_secure:
                return redirect(origin + "/database", code=308)
            auth = request.authorization
            if not auth or auth.type != "basic" or auth.username != "pooly" or not check_password_hash(password_hash, auth.password or ""):
                return "관리자 인증이 필요해요.", 401, {"WWW-Authenticate": 'Basic realm="Pooly database"'}
        totals, appointments = store().database_overview()
        return render_template("database.html", totals=totals, appointments=appointments)

    @app.route("/api/<path:path>", methods=["GET", "POST", "PATCH"])
    def api(path):
        try:
            if (demo_owner or origin) and path.startswith("invites/"):
                raise ApiError(404, "요청을 찾을 수 없어요.")
            local_demo_request = local_demo_owner and request.remote_addr in ("127.0.0.1", "::1")
            if path.startswith("demo/") and not (public_demo_owner or local_demo_request):
                raise ApiError(404, "요청을 찾을 수 없어요.")
            if public_demo_owner and path.startswith("pair/"):
                raise ApiError(404, "요청을 찾을 수 없어요.")
            data = {}
            if request.method != "GET":
                data = request.get_json() if request.content_length else {}
                if not isinstance(data, dict):
                    raise ApiError(400, "요청 내용을 확인해 주세요.")
            db = store()
            if request.method != "GET":
                # Serialize read-modify-write domain actions across WSGI workers.
                db.db.execute("BEGIN IMMEDIATE")
            if path == "demo/roster" and request.method == "GET":
                return jsonify(db.demo_roster())
            if path == "demo/session" and request.method == "POST":
                return jsonify(db.demo_session(data.get("contact_id")))
            if origin and request.method == "POST" and path in ("pair/roster", "pair/session"):
                db.use_pair_code(data.get("code"))
                return jsonify(db.demo_roster() if path.endswith("/roster") else db.demo_session(data.get("contact_id")))
            if request.method == "POST" and path == "sessions":
                return jsonify(db.register(data)), 201
            match = re.fullmatch(r"invites/([A-Za-z0-9_.-]{1,600})", path)
            if request.method == "GET" and match:
                return jsonify(db.invitation(match[1]))
            bearer = request.headers.get("Authorization", "")
            user = db.user(bearer[7:] if bearer.startswith("Bearer ") else "")
            uid = user["id"]
            if origin and not public_demo_owner:
                if request.method == "POST" and path == "pair/code":
                    return jsonify(db.create_pair_code(uid))
                db.enable_paired_demo(uid)
            if path == "seat-profile" and request.method in ["GET", "POST"]:
                return jsonify(db.seat_profile(uid, data if request.method == "POST" else None))
            if request.method == "GET" and path == "pickup-recommendations":
                hour = request.args.get("hour")
                return jsonify(db.pickup_recommendations(float(request.args["lat"]), float(request.args["lon"]), int(hour) if hour not in (None, "") else None))
            if request.method == "GET" and path == "state":
                return jsonify(db.state(user))
            if request.method == "GET" and path == "places":
                return jsonify(app.extensions["place_search"].search(request.args.get("q", "")))
            if request.method == "POST" and path == "contacts":
                return jsonify(db.contact(uid, data)), 201
            if request.method == "POST" and path == "appointments":
                return jsonify(db.create(user, data)), 201
            match = re.fullmatch(r"invites/([A-Za-z0-9_.-]{1,600})/join", path)
            if request.method == "POST" and match:
                return jsonify(db.join(user, match[1], data))
            match = re.fullmatch(r"appointments/([a-f0-9]{24})(?:/(route|actions|member|location|messages|seat-personalization|demo-position|stops))?", path)
            if match:
                aid, action = match.groups()
                public_trip = public_demo_owner and db._access(aid, uid)["owner_id"] == public_demo_owner
                if request.method == "POST" and action == "demo-position":
                    if not public_trip or uid != public_demo_owner:
                        raise ApiError(403, "시연 차량의 가상 위치만 공유할 수 있어요.")
                    return jsonify(db.demo_position(uid, aid, data))
                if request.method == "GET" and action == "route":
                    route = db.route(uid, aid)
                    if public_trip and uid != public_demo_owner:
                        route["stops"] = [stop for stop in route["stops"] if stop["kind"] != "start"]
                        route["geometry"] = []
                    return jsonify(route)
                if request.method == "PATCH" and action is None:
                    return jsonify(db.update(uid, aid, data))
                handlers = {"actions": db.action, "member": db.member_update, "location": db.location, "messages": db.message, "seat-personalization": db.seat_personalization, "stops": db.record_stop}
                if request.method == "POST" and action in handlers:
                    if public_trip and action in {"location", "seat-personalization", "stops"}:
                        raise ApiError(403, "공개 시연에서는 사용할 수 없어요.")
                    if public_trip and action == "member" and "share" in data:
                        data["share"] = False
                    return jsonify(handlers[action](uid, aid, data))
            raise ApiError(404, "요청을 찾을 수 없어요.")
        except (ValueError, TypeError, KeyError, OverflowError):
            raise ApiError(400, "입력한 내용을 확인해 주세요.")

    @app.get("/")
    @app.get("/<path:filename>")
    def web(filename="phone.html"):
        if demo_owner and filename == "phone.html" and (request.path == "/" or request.args.get("demo") != "1"):
            return redirect("/phone.html?demo=1")
        target = (WEB / filename).resolve()
        if not target.is_relative_to(WEB.resolve()) or not target.is_file() or target.suffix not in {".html", ".js", ".css", ".woff2", ".png", ".svg", ".json"}:
            raise ApiError(404, "페이지를 찾을 수 없어요.")
        return send_from_directory(WEB, filename)

    def run_reminders():
        with app.app_context():
            db = store()
            db.db.execute("BEGIN IMMEDIATE")
            db.reminders()

    app.extensions["run_reminders"] = run_reminders

    @app.cli.command("reminders")
    def reminders():
        """Run durable reminders once; schedule externally on PythonAnywhere."""
        run_reminders()
        click.echo("Departure reminders processed.")

    return app
