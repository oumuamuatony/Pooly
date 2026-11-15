import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
import base64
import time

from werkzeug.security import generate_password_hash

from pooly.server.domain import PLACES
from pooly.server.flask_app import create_app


class FlaskWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.config = {"TESTING": True, "DATABASE": str(Path(self.temp.name) / "flask.sqlite3")}
        self.app = create_app(self.config)
        self.client = self.app.test_client()
        self.driver = self.client.post("/api/sessions", json={"name": "Jeff"}).get_json()
        self.auth = {"Authorization": "Bearer " + self.driver["token"]}
        self.contacts = [self.client.post("/api/contacts", json={"name": n}, headers=self.auth).get_json() for n in ["Kelly", "Max", "Sam"]]
        self.payload = {"name": "Flask pickup", "meeting_at": (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat(), "origin": PLACES[0], "destination": PLACES[1], "members": [{"id": c["id"], "mode": "pickup" if i < 2 else "walk"} for i, c in enumerate(self.contacts)]}
        response = self.client.post("/api/appointments", json=self.payload, headers=self.auth)
        self.assertEqual(201, response.status_code)
        self.a = response.get_json()

    def tearDown(self):
        self.temp.cleanup()

    def test_only_owner_can_delete_an_appointment_for_all_participants(self):
        client = create_app({**self.config, "DEMO_OWNER": self.driver["user"]["id"]}).test_client()
        rider = client.post("/api/demo/session", json={"contact_id": self.contacts[0]["id"]}).get_json()
        rider_auth = {"Authorization": "Bearer " + rider["token"]}
        path = "/api/appointments/" + self.a["id"]
        self.assertEqual(1, len(client.get("/api/state", headers=rider_auth).get_json()["appointments"]))
        self.assertEqual(401, client.delete(path).status_code)
        self.assertEqual(403, client.delete(path, headers=rider_auth).status_code)
        self.assertEqual({"deleted": True}, client.delete(path, headers=self.auth).get_json())
        for auth in (self.auth, rider_auth):
            self.assertEqual([], client.get("/api/state", headers=auth).get_json()["appointments"])
        self.assertEqual(404, client.get(path + "/route", headers=self.auth).status_code)

    def test_local_demo_links_saved_friends_without_invites(self):
        app = create_app({**self.config, "DEMO_OWNER": self.driver["user"]["id"]})
        client = app.test_client()
        invite = self.a["members"][0]["invite_token"]
        friend = self.contacts[0]
        self.assertEqual("/phone.html?demo=1", client.get("/").location)
        roster = client.get("/api/demo/roster").get_json()
        self.assertEqual(friend["id"], roster["default_id"])
        self.assertEqual({"Kelly", "Max"}, {c["name"] for c in roster["friends"]})
        self.assertEqual(404, client.get("/api/demo/roster", environ_overrides={"REMOTE_ADDR": "192.0.2.1"}).status_code)
        self.assertEqual(404, client.get("/api/invites/" + invite).status_code)
        self.assertEqual(404, client.post("/api/demo/session", json={"contact_id": self.contacts[2]["id"]}).status_code)
        session = client.post("/api/demo/session", json={"contact_id": friend["id"]}).get_json()
        rider_auth = {"Authorization": "Bearer " + session["token"]}
        saved = client.post("/api/appointments/" + self.a["id"] + "/member",
                            json={"pickup": PLACES[2]}, headers=rider_auth)
        self.assertEqual(200, saved.status_code)
        owner_view = client.get("/api/state", headers=self.auth).get_json()["appointments"][0]
        self.assertEqual(PLACES[2]["name"], owner_view["members"][0]["pickup"]["name"])
        self.assertNotIn("invite_token", owner_view["members"][0])
        created = client.post("/api/appointments", json={**self.payload, "members": [self.payload["members"][0]]}, headers=self.auth)
        self.assertEqual(201, created.status_code)
        self.assertEqual("accepted", created.get_json()["members"][0]["status"])
        self.assertEqual(2, len(client.get("/api/state", headers=rider_auth).get_json()["appointments"]))

    def test_public_pairing_scopes_web_friends_to_vehicle_and_revokes_old_code(self):
        origin = "https://pooly.example"
        client = create_app({**self.config, "PUBLIC_ORIGIN": origin}).test_client()
        self.assertEqual(401, client.post("/api/pair/code", base_url=origin, json={}).status_code)
        issued = client.post("/api/pair/code", base_url=origin, json={}, headers=self.auth).get_json()
        code = issued["code"]
        self.assertEqual(403, client.post("/api/pair/roster", base_url=origin, json={"code":"invalid"}).status_code)
        roster = client.post("/api/pair/roster", base_url=origin, json={"code":code}).get_json()
        self.assertEqual({"Max","Kelly","Jeff"}, {c["name"] for c in roster["friends"]})
        friend = next(c for c in roster["friends"] if c["name"] == "Kelly")
        created = client.post("/api/appointments", base_url=origin, headers=self.auth,
                              json={**self.payload, "members":[{"id":friend["id"],"mode":"pickup"}]}).get_json()
        self.assertEqual("accepted", created["members"][0]["status"])
        session = client.post("/api/pair/session", base_url=origin, json={"code":code,"contact_id":friend["id"]}).get_json()
        rider_auth = {"Authorization":"Bearer " + session["token"]}
        path = "/api/appointments/" + created["id"] + "/member"
        self.assertEqual(200, client.post(path, base_url=origin, headers=rider_auth, json={"pickup":PLACES[2]}).status_code)
        result = client.get("/api/state", base_url=origin, headers=self.auth).get_json()
        self.assertEqual(PLACES[2]["name"], next(a for a in result["appointments"] if a["id"] == created["id"])["members"][0]["pickup"]["name"])
        client.post("/api/pair/code", base_url=origin, json={}, headers=self.auth)
        self.assertEqual(403, client.post("/api/pair/roster", base_url=origin, json={"code":code}).status_code)

    def test_public_demo_syncs_across_clients_without_pairing(self):
        origin = "https://pooly.example"
        app = create_app({**self.config, "PUBLIC_ORIGIN": origin, "PUBLIC_DEMO_OWNER": self.driver["user"]["id"]})
        vehicle = app.test_client()
        phone = app.test_client()
        self.assertEqual("/phone.html?demo=1", phone.get("/", base_url=origin).location)
        self.assertEqual(404, vehicle.post("/api/pair/code", base_url=origin, headers=self.auth, json={}).status_code)
        roster = phone.get("/api/demo/roster", base_url=origin, environ_overrides={"REMOTE_ADDR": "192.0.2.10"}).get_json()
        friend = next(c for c in roster["friends"] if c["name"] == "Kelly")
        outsider = vehicle.post("/api/sessions", base_url=origin, json={"name": "Other driver"}).get_json()
        outsider_auth = {"Authorization": "Bearer " + outsider["token"]}
        private = vehicle.post("/api/appointments", base_url=origin, headers=outsider_auth,
                               json={**self.payload, "members": []}).get_json()
        created = vehicle.post("/api/appointments", base_url=origin, headers=self.auth,
                               json={**self.payload, "members": [{"id": friend["id"], "mode": "pickup"}]}).get_json()
        self.assertEqual("accepted", created["members"][0]["status"])
        session = phone.post("/api/demo/session", base_url=origin, json={"contact_id": friend["id"]}).get_json()
        rider_auth = {"Authorization": "Bearer " + session["token"]}
        state = phone.get("/api/state", base_url=origin, headers=rider_auth).get_json()
        view = next(a for a in state["appointments"] if a["id"] == created["id"])
        self.assertNotIn("origin", view)
        self.assertNotIn(private["id"], [a["id"] for a in state["appointments"]])
        path = "/api/appointments/" + created["id"]
        saved = phone.post(path + "/member", base_url=origin, headers=rider_auth, json={"pickup": PLACES[2], "share": True}).get_json()
        self.assertFalse(saved["members"][0]["share"])
        driver_state = vehicle.get("/api/state", base_url=origin, headers=self.auth).get_json()
        synced = next(a for a in driver_state["appointments"] if a["id"] == created["id"])
        self.assertEqual(PLACES[2]["name"], synced["members"][0]["pickup"]["name"])
        for action in ["location", "stops"]:
            self.assertEqual(403, vehicle.post(path + "/" + action, base_url=origin, headers=self.auth, json={}).status_code)
        # Seat settings of demo personas work in the public demo; boarding is still required.
        for action in ["seat-personalization", "seat-learning"]:
            response = vehicle.post(path + "/" + action, base_url=origin, headers=self.auth, json={"member_id": friend["id"]})
            self.assertIn(response.status_code, (403, 409))
            self.assertNotEqual("공개 시연에서는 사용할 수 없어요.", response.get_json()["error"])
        departed = vehicle.post(path + "/actions", base_url=origin, headers=self.auth, json={"action": "depart"}).get_json()
        self.assertIn(friend["id"], departed["trip"]["planned"]["pickup_arrivals"])
        now = int(time.time() * 1000)
        demo_point = {**PLACES[0], "mock": True, "observed_at": now}
        self.assertEqual(403, phone.post(path + "/demo-position", base_url=origin, headers=rider_auth, json=demo_point).status_code)
        self.assertEqual(403, vehicle.post(path + "/demo-position", base_url=origin, headers=self.auth, json={**demo_point, "mock": False}).status_code)
        self.assertEqual(200, vehicle.post(path + "/demo-position", base_url=origin, headers=self.auth, json=demo_point).status_code)
        for status in ("guiding", "stopped"):
            vehicle.post(path + "/actions", base_url=origin, headers=self.auth,
                         json={"action": "navigation_status", "status": status, "session_id": "demo-session", "requested_at": now - 100})
            vehicle.post(path + "/actions", base_url=origin, headers=self.auth,
                         json={"action": "navigation_eta", "session_id": "demo-session",
                               "stops": {friend["id"]: {"seconds": 480, "observed_at": now}, "other": {"seconds": 20, "observed_at": now}}})
            state = phone.get("/api/state", base_url=origin, headers=rider_auth).get_json()
            view = next(a for a in state["appointments"] if a["id"] == created["id"])
            self.assertEqual(PLACES[0]["lat"], view["owner"]["location"]["lat"])
            self.assertNotIn("demo_position", view["trip"])
            self.assertEqual({friend["id"]} if status == "guiding" else set(), set(view["trip"].get("navigation_etas", {})))

    def test_database_view_is_read_only_and_requires_auth_on_public_host(self):
        origin = "https://pooly.example"
        client = create_app({**self.config, "PUBLIC_ORIGIN": origin, "DATABASE_VIEW_PASSWORD_HASH": generate_password_hash("test-admin")}).test_client()
        unauth = client.get("/database", base_url=origin)
        self.assertEqual(401, unauth.status_code)
        auth = {"Authorization": "Basic " + base64.b64encode(b"pooly:test-admin").decode()}
        result = client.get("/database", base_url=origin, headers=auth)
        self.assertEqual(200, result.status_code)
        self.assertIn(PLACES[1]["name"], result.get_data(as_text=True))
        self.assertNotIn(self.driver["token"], result.get_data(as_text=True))
        self.assertEqual(405, client.post("/database", base_url=origin, headers=auth).status_code)

    def rider(self, index=0, drives=False):
        session = self.client.post("/api/sessions", json={"name": self.contacts[index]["name"]}).get_json()
        auth = {"Authorization": "Bearer " + session["token"]}
        data = {"drives": drives}
        if not drives and index < 2:
            data["pickup"] = PLACES[2]
        response = self.client.post("/api/invites/" + self.a["members"][index]["invite_token"] + "/join", json=data, headers=auth)
        self.assertEqual(200, response.status_code)
        return auth, response.get_json()

    def test_three_pickups_can_be_created_and_reordered(self):
        members = [{"id": c["id"], "mode": "pickup"} for c in self.contacts]
        response = self.client.post("/api/appointments", json={**self.payload, "members": members}, headers=self.auth)
        self.assertEqual(201, response.status_code)
        order = [member["id"] for member in reversed(members)]
        response = self.client.patch("/api/appointments/" + self.a["id"],
                                     json={"revision": self.a["revision"], "members": members, "pickup_order": order},
                                     headers=self.auth)
        self.assertEqual(200, response.status_code)
        self.assertEqual(order, response.get_json()["pickup_order"])

    def test_binary_driving_selection_and_recipient_owned_location(self):
        auth, joined = self.rider()
        self.assertEqual("pickup", joined["members"][0]["mode"])
        self.assertEqual(PLACES[2]["name"], joined["members"][0]["pickup"]["name"])
        path = "/api/appointments/" + self.a["id"] + "/member"
        driving = self.client.post(path, json={"drives": True}, headers=auth).get_json()
        self.assertEqual("car", driving["members"][0]["mode"])
        self.assertIsNone(driving["members"][0]["pickup"])
        self.assertTrue(driving["members"][0]["pickup_requested"])
        self.assertEqual(403, self.client.post(path, json={"pickup": PLACES[3]}, headers=self.auth).status_code)
        self.assertEqual(400, self.client.post(path, json={"drives": "false"}, headers=auth).status_code)
        auth2, direct = self.rider(2)
        self.assertEqual("walk", direct["members"][2]["mode"])
        self.assertEqual(403, self.client.post(path, json={"mode": "pickup", "pickup": PLACES[2]}, headers=auth2).status_code)

    def test_only_assigned_rider_can_select_pickup(self):
        auth, joined = self.rider(drives=True)
        changed = self.client.patch("/api/appointments/" + self.a["id"], json={"revision": joined["revision"], "members": [{"id": self.contacts[0]["id"], "mode": "car"}, {"id": self.contacts[1]["id"], "mode": "pickup"}, {"id": self.contacts[2]["id"], "mode": "pickup"}]}, headers=self.auth)
        self.assertEqual(200, changed.status_code)
        # Driver removed the original rider's pickup assignment; a client cannot re-add itself.
        response = self.client.post("/api/appointments/" + self.a["id"] + "/member", json={"mode": "pickup"}, headers=auth)
        self.assertEqual(403, response.status_code)

    def test_full_invite_boarding_and_persistence(self):
        auth, _ = self.rider()
        path = "/api/appointments/" + self.a["id"]
        for action in ["depart", "arrive"]:
            self.assertEqual(200, self.client.post(path + "/actions", json={"action": action}, headers=self.auth).status_code)
        self.assertEqual(200, self.client.post(path + "/member", json={"action": "board_confirm"}, headers=auth).status_code)
        boarded = self.client.post(path + "/actions", json={"action": "board"}, headers=self.auth).get_json()
        self.assertIsNotNone(boarded["members"][0]["boarded_at"])
        fresh = create_app(self.config).test_client()
        saved = fresh.get("/api/state", headers=auth).get_json()["appointments"][0]
        self.assertEqual(boarded["trip"], saved["trip"])

    def test_wsgi_https_origin_and_request_validation(self):
        client = create_app({**self.config, "PUBLIC_ORIGIN": "https://pooly.example"}).test_client()
        self.assertEqual(201, client.post("/api/sessions", json={"name": "test"}, base_url="http://pooly.example", headers={"Origin": "https://pooly.example"}).status_code)
        self.assertEqual(403, client.post("/api/sessions", json={"name": "test"}, base_url="http://pooly.example", headers={"Origin": "https://untrusted.example"}).status_code)
        self.assertEqual(400, client.get("/health", base_url="https://untrusted.example").status_code)
        self.assertEqual(401, self.client.get("/api/state").status_code)
        self.assertEqual(400, self.client.post("/api/sessions", json=[]).status_code)
        self.assertEqual(413, self.client.post("/api/sessions", data=b"x" * 200001, content_type="application/json").status_code)
        self.assertEqual(404, self.client.get("/../server/domain.py").status_code)
        with self.client.get("/") as response:
            self.assertEqual(200, response.status_code)
        self.assertEqual("no-store", self.client.get("/health").headers["Cache-Control"])

    def test_concurrent_updates_reject_stale_revision(self):
        def update(name):
            with self.app.test_client() as client:
                return client.patch("/api/appointments/" + self.a["id"], json={"revision": self.a["revision"], "name": name}, headers=self.auth).status_code
        with ThreadPoolExecutor(max_workers=2) as pool:
            codes = list(pool.map(update, ["first", "second"]))
        self.assertEqual([200, 409], sorted(codes))


if __name__ == "__main__":
    unittest.main()
