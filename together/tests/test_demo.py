import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from together.server.demo import DemoSession, Emulator, gps_sentences, make_demo_servers
from together.server.domain import ApiError, Store


class DemoWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.temp.name) / "demo.sqlite3")
        self.demo = DemoSession(self.store)

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def test_reset_replaces_only_demo_appointments_and_keeps_actor_identity(self):
        ids = {key: actor["user"]["id"] for key, actor in self.demo.actors.items()}
        old_aid = self.demo.aid
        self.demo.reset(False)
        self.assertNotEqual(old_aid, self.demo.aid)
        self.assertEqual(ids, {key: actor["user"]["id"] for key, actor in self.demo.actors.items()})
        self.assertEqual(["pending", "pending"], [m["status"] for m in self.demo.appointment()["members"]])
        self.demo.actor("jiyeon", "join")
        self.assertEqual("accepted", self.demo.appointment()["members"][0]["status"])
        self.assertEqual([], self.store.state(self.demo.actors["hyunjun"]["user"])["appointments"])

    def test_replay_stops_at_pickups_and_does_not_fabricate_sdk_driver_points(self):
        self.demo.store.action(self.demo.uid("driver"), self.demo.aid, {"action": "depart"})
        self.demo.running = True
        for _ in range(200):
            self.demo.tick(2)
        self.assertFalse(self.demo.running)
        a = self.demo.appointment()
        self.assertEqual("driving", a["trip"]["phase"])
        self.assertIsNone(a["owner"]["location"])
        self.assertEqual([], a["track"])
        self.assertEqual([], a["trip"]["boarded"])
        with self.assertRaises(ApiError):
            self.demo.actor("jiyeon", "board_confirm")
        self.store.action(self.demo.uid("driver"), self.demo.aid, {"action": "arrive"})
        with self.assertRaises(ApiError):
            self.demo.control({"command": "driver", "action": "board"})
        self.demo.actor("jiyeon", "board_confirm")
        self.demo.control({"command": "driver", "action": "board"})
        self.assertEqual(1, self.demo.leg)
        self.assertEqual(0, self.demo.offset)
        self.demo.running = True
        for _ in range(150):
            self.demo.tick(2)
        self.assertFalse(self.demo.running)
        self.assertEqual("driving", self.demo.appointment()["trip"]["phase"])
        self.demo.control({"command": "driver", "action": "arrive"})
        self.demo.actor("hyunjun", "board_confirm")
        self.demo.control({"command": "driver", "action": "board"})
        self.assertEqual(2, self.demo.leg)
        self.demo.running = True
        for _ in range(150):
            self.demo.tick(2)
        self.assertFalse(self.demo.running)
        self.assertEqual("destination", self.demo.appointment()["trip"]["phase"])
        self.demo.control({"command": "driver", "action": "park"})
        self.demo.control({"command": "driver", "action": "finish"})
        self.assertEqual("finished", self.demo.appointment()["trip"]["phase"])
        self.demo.actor("jiyeon", "home")
        self.assertIsNotNone(self.demo.appointment()["members"][0]["home_at"])

    def test_emulator_only_and_checksummed_nmea(self):
        with self.assertRaises(ValueError):
            Emulator("physical-phone-serial")
        for sentence in gps_sentences(37.54, 127.05, 10, 90):
            body, checksum = sentence[1:].split("*")
            value = 0
            for byte in body.encode():
                value ^= byte
            self.assertEqual(checksum, f"{value:02X}")
            self.assertIn("3732.4000,N,12703.0000,E", body)
            self.assertRegex(body, r"^GP(?:GGA|RMC),[0-9]{6},")

    def test_personal_sharing_guard_does_not_mistake_demo_for_personal_app(self):
        emulator = Emulator()
        emulator.command = lambda *args: "* ServiceRecord{a u10 io.pickuptogether.app.demo/io.pickuptogether.app.JourneyService}"
        self.assertFalse(emulator.personal_trip_active())
        emulator.command = lambda *args: "* ServiceRecord{a u10 io.pickuptogether.app/.JourneyService}"
        self.assertTrue(emulator.personal_trip_active())

    def test_http_actor_origins_and_management_boundary(self):
        servers = make_demo_servers(self.demo, ports=(0, 0, 0))
        for server in servers:
            threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            tokens = []
            for port in [s.server_port for s in servers]:
                req = urllib.request.Request(f"http://127.0.0.1:{port}/api/sessions", data=b'{}', headers={"Content-Type": "application/json"})
                with urllib.request.urlopen(req) as response:
                    tokens.append(json.load(response)["token"])
            self.assertEqual(3, len(set(tokens)))
            for headers in ({"Origin": "https://external.example"}, {"Host": "external.example"}):
                req = urllib.request.Request(f"http://127.0.0.1:{servers[0].server_port}/api/demo/control", data=b'{"command":"reset"}', headers=headers)
                with self.assertRaises(urllib.error.HTTPError) as result:
                    urllib.request.urlopen(req)
                self.assertEqual(403, result.exception.code)
            with self.assertRaises(urllib.error.HTTPError) as result:
                urllib.request.urlopen(f"http://127.0.0.1:{servers[1].server_port}/api/demo/state")
            self.assertEqual(403, result.exception.code)
        finally:
            for server in servers:
                server.shutdown()
                server.server_close()


if __name__ == "__main__":
    unittest.main()
