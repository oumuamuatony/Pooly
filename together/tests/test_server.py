import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

from together.server.app import AppServer
from together.server.domain import ApiError, PLACES, Store


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "db.sqlite3"
        self.store = Store(self.path)
        self.driver = self.store.register({"name": "운전자"})
        self.rider = self.store.register({"name": "지연"})
        self.other = self.store.register({"name": "다른 사람"})
        self.uid = self.driver["user"]["id"]
        self.rid = self.rider["user"]["id"]
        self.contact = self.store.contact(self.uid, {"name": "지연"})
        self.a = self.store.create(self.driver["user"], {
            "name": "서울숲 약속", "meeting_at": (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat(),
            "origin": PLACES[0], "destination": PLACES[1],
            "members": [{"id": self.contact["id"], "mode": "pickup"}],
        })
        self.aid = self.a["id"]
        self.invite = self.a["members"][0]["invite_token"]

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def join(self):
        return self.store.join(self.rider["user"], self.invite, {"mode": "pickup", "pickup": PLACES[2], "share": False})

    def assert_api(self, status, function, *args):
        with self.assertRaises(ApiError) as cm:
            function(*args)
        self.assertEqual(status, cm.exception.status)

    def test_invites_stay_pending_until_real_acceptance(self):
        state = self.store.state(self.driver["user"])
        self.assertEqual("pending", state["appointments"][0]["members"][0]["status"])
        self.assertEqual([], self.store.state(self.rider["user"])["appointments"])
        self.join()
        self.assertEqual("accepted", self.store.state(self.driver["user"])["appointments"][0]["members"][0]["status"])
        self.assertTrue(any("수락" in n["title"] for n in self.store.state(self.driver["user"])["notifications"]))

    def test_authorization_and_invite_binding(self):
        self.assert_api(401, self.store.user, "wrong")
        self.assert_api(403, self.store.route, self.other["user"]["id"], self.aid)
        self.join()
        self.assert_api(409, self.store.join, self.other["user"], self.invite, {"mode": "walk"})
        self.assert_api(403, self.store.action, self.rid, self.aid, {"action": "depart"})
        self.assert_api(403, self.store.update, self.rid, self.aid, {"revision": self.a["revision"]})

    def test_only_pickup_recipient_can_set_their_pickup(self):
        joined = self.join()
        self.assert_api(403, self.store.update, self.uid, self.aid, {
            "revision": joined["revision"], "members": [{"id": self.contact["id"], "mode": "pickup", "pickup": PLACES[4]}]})
        self.assert_api(403, self.store.update, self.uid, self.aid, {
            "revision": joined["revision"], "pickup": PLACES[4]})
        self.assert_api(403, self.store.member_update, self.uid, self.aid, {"pickup": PLACES[4]})
        self.assert_api(403, self.store.member_update, self.other["user"]["id"], self.aid, {"pickup": PLACES[4]})
        changed = self.store.member_update(self.rid, self.aid, {"pickup": PLACES[4]})
        self.assertEqual(PLACES[4]["name"], changed["members"][0]["pickup"]["name"])
        self.assertIsNone(changed["owner"]["pickup"])

    def test_complete_pickup_and_return_are_separate(self):
        self.join()
        self.store.action(self.uid, self.aid, {"action": "depart"})
        self.assert_api(409, self.store.action, self.uid, self.aid, {"action": "board"})
        self.store.action(self.uid, self.aid, {"action": "arrive"})
        self.assert_api(409, self.store.action, self.uid, self.aid, {"action": "board"})
        self.store.member_update(self.rid, self.aid, {"ready": "waiting", "action": "board_confirm"})
        a = self.store.action(self.uid, self.aid, {"action": "board"})
        self.assertEqual("destination", a["trip"]["phase"])
        self.store.action(self.uid, self.aid, {"action": "park"})
        a = self.store.action(self.uid, self.aid, {"action": "finish"})
        self.assertIsNone(a["members"][0]["home_at"])
        self.assertFalse(a["members"][0]["share"])
        self.store.member_update(self.rid, self.aid, {"action": "home"})
        self.assertIsNotNone(self.store.state(self.driver["user"])["appointments"][0]["members"][0]["home_at"])
        self.assert_api(410, self.store.invitation, self.invite)

    def test_private_origin_and_location_consent(self):
        joined = self.join()
        self.assertNotIn("origin", joined)
        self.assertNotIn("origin", joined["owner"])
        route = self.store.route(self.rid, self.aid)
        self.assertEqual([], route["geometry"])
        self.assertFalse(any(s["kind"] == "start" for s in route["stops"]))
        self.store.action(self.uid, self.aid, {"action": "depart"})
        pos = {"lat": 37.5408, "lon": 127.0793, "accuracy": 10, "mock": True}
        self.assert_api(409, self.store.location, self.uid, self.aid, pos)
        self.store.member_update(self.uid, self.aid, {"share": True})
        self.store.location(self.uid, self.aid, pos)
        a = self.store.state(self.rider["user"])["appointments"][0]
        self.assertTrue(a["owner"]["location"]["mock"])
        self.store.member_update(self.uid, self.aid, {"share": False})
        self.assertIsNone(self.store.state(self.rider["user"])["appointments"][0]["owner"]["location"])
        self.assert_api(409, self.store.location, self.uid, self.aid, pos)

    def test_stale_update_rejected_and_database_persists(self):
        revision = self.a["revision"]
        self.store.update(self.uid, self.aid, {"revision": revision, "name": "변경한 약속"})
        self.assert_api(409, self.store.update, self.uid, self.aid, {"revision": revision, "name": "오래된 값"})
        self.store.close()
        self.store = Store(self.path)
        user = self.store.user(self.driver["token"])
        self.assertEqual("변경한 약속", self.store.state(user)["appointments"][0]["name"])

    def test_multiple_appointments_and_messages(self):
        self.join()
        self.store.create(self.driver["user"], {"name": "다른 약속", "meeting_at": self.a["meeting_at"], "origin": PLACES[2], "destination": PLACES[1], "members": []})
        self.assertEqual(2, len(self.store.state(self.driver["user"])["appointments"]))
        self.store.message(self.rid, self.aid, {"body": "출구 앞에 있어요"})
        original = next(a for a in self.store.state(self.driver["user"])["appointments"] if a["id"] == self.aid)
        self.assertEqual("출구 앞에 있어요", original["messages"][0]["body"])

    def test_cancel_revokes_sharing_and_prevents_late_join(self):
        self.join()
        self.store.action(self.uid, self.aid, {"action": "cancel"})
        self.assert_api(410, self.store.invitation, self.invite)
        self.assert_api(409, self.store.member_update, self.rid, self.aid, {"action": "depart"})

    def test_reminders_are_not_duplicated(self):
        self.join()
        self.store.member_update(self.rid, self.aid, {"mode": "walk", "travel_minutes": 125})
        self.store.reminders()
        self.store.reminders()
        notifications = self.store.state(self.rider["user"])["notifications"]
        self.assertEqual(1, sum(n["title"] == "출발할 시간이에요" for n in notifications))

    def test_later_departure_aligns_with_first_travelers_eta(self):
        import time
        from unittest.mock import patch
        self.join()
        a = self.store.member_update(self.rid, self.aid, {"mode": "walk", "travel_minutes": 60, "action": "depart"})
        route = self.store.route(self.uid, self.aid)
        target = datetime.fromisoformat(a["members"][0]["started_at"]) + timedelta(minutes=60)
        self.assertLess(abs((datetime.fromisoformat(route["arrival_target"]) - target).total_seconds()), 1)
        self.store.reminders()
        self.assertFalse(any(n["title"] == "출발할 시간이에요" for n in self.store.state(self.driver["user"])["notifications"]))
        with patch("together.server.domain.time.time", return_value=time.time() + 50 * 60):
            self.store.reminders()
            self.store.reminders()
        notes = [n for n in self.store.state(self.driver["user"])["notifications"] if n["title"] == "출발할 시간이에요"]
        self.assertEqual(1, len(notes))
        self.assertIn("지연", notes[0]["body"])

    def test_private_route_does_not_mutate_driver_cache(self):
        self.join()
        private = self.store.route(self.rid, self.aid)
        self.assertEqual([], private["geometry"])
        driver = self.store.route(self.uid, self.aid)
        self.assertGreaterEqual(len(driver["geometry"]), 3)
        a = self.store.action(self.uid, self.aid, {"action": "depart"})
        self.assertGreater(a["trip"]["planned"]["distance_m"], 0)
        self.assertIsNotNone(a["trip"]["planned"]["arrival_at"])

    def test_departed_pickup_order_is_stable(self):
        self.join()
        a = self.store.action(self.uid, self.aid, {"action": "depart"})
        self.assert_api(409, self.store.update, self.uid, self.aid, {"revision": a["revision"], "pickup_order": []})
        self.assert_api(409, self.store.member_update, self.rid, self.aid, {"mode": "walk"})
        self.assert_api(409, self.store.join, self.rider["user"], self.invite, {"mode": "pickup"})
        self.assertEqual([self.contact["id"]], self.store.state(self.driver["user"])["appointments"][0]["trip"]["active_pickups"])

    def test_cancel_current_pickup_advances_without_boarding(self):
        self.join()
        self.store.action(self.uid, self.aid, {"action": "depart"})
        a = self.store.action(self.uid, self.aid, {"action": "skip"})
        self.assertEqual("destination", a["trip"]["phase"])
        self.assertEqual([], a["trip"]["boarded"])
        self.assertEqual("walk", a["members"][0]["mode"])
        self.assertTrue(any(n["title"] == "픽업 취소" for n in self.store.state(self.rider["user"])["notifications"]))

    def test_http_requires_auth_and_rejects_cross_origin(self):
        server = AppServer(("127.0.0.1", 0), self.store)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            with self.assertRaises(urllib.error.HTTPError) as cm:
                urllib.request.urlopen(base + "/api/state")
            self.assertEqual(401, cm.exception.code)
            request = urllib.request.Request(base + "/api/sessions", data=b'{"name":"x"}', headers={"Origin": "https://untrusted.invalid", "Content-Type": "application/json"})
            with self.assertRaises(urllib.error.HTTPError) as cm:
                urllib.request.urlopen(request)
            self.assertEqual(403, cm.exception.code)
            request = urllib.request.Request(base + "/api/state", headers={"Authorization": "Bearer " + self.driver["token"]})
            with urllib.request.urlopen(request) as response:
                self.assertEqual(self.uid, json.load(response)["user"]["id"])
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
