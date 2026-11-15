#!/usr/bin/env python3
"""Complete a demo appointment without fabricating a Pleos GPS or arrival event."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET


ORIGIN = "https://yeonil.pythonanywhere.com"
PACKAGE = "io.pooly.app"


def run():
    parser = argparse.ArgumentParser(description="Pooly 시연 일정의 목적지 도착")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--appointment", help="시연 일정 ID")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("Pleos 에뮬레이터에서만 사용할 수 있어요.")

    sdk = Path(os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Library/Android/sdk"))
    adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]
    user = subprocess.check_output(adb + ["shell", "am", "get-current-user"], text=True, timeout=5).strip()
    session = subprocess.check_output(
        adb + ["exec-out", "run-as", PACKAGE, "--user", user, "cat", "shared_prefs/pooly_session.xml"],
        timeout=5,
    )
    prefs = {item.get("name"): item.text for item in ET.fromstring(session)}
    if prefs.get("base") != ORIGIN or prefs.get("role") != "car" or not prefs.get("token"):
        raise ValueError("차량 앱에서 Pooly 시연 계정을 먼저 열어 주세요.")

    def api(path, action=None):
        data = None if action is None else json.dumps({"action": action}).encode()
        request = Request(ORIGIN + path, data=data, headers={"Content-Type": "application/json"})
        request.add_unredirected_header("Authorization", "Bearer " + prefs["token"])
        try:
            with urlopen(request, timeout=10) as response:
                return json.load(response)
        except HTTPError as error:
            message = json.load(error).get("error", "서버 요청을 처리하지 못했어요.")
            raise ValueError(message) from None

    state = api("/api/state")
    if state.get("auto_pickup_proximity_required") is not False:
        raise ValueError("Pooly 서버가 시연 모드일 때 사용할 수 있어요.")
    active = [
        a for a in state["appointments"]
        if a["is_driver"] and a["is_owner"] and a["trip"]["phase"] in {"driving", "near", "arrived", "destination", "parked"}
        and (args.appointment is None or a["id"] == args.appointment)
    ]
    if len(active) != 1:
        raise ValueError("시연할 이동 중 일정 한 개를 지정해 주세요.")
    trip = active[0]
    phase = trip["trip"]["phase"]
    if phase not in {"destination", "parked"}:
        raise ValueError("일행 픽업이 모두 끝난 뒤 실행해 주세요.")
    path = "/api/appointments/" + trip["id"] + "/actions"
    if phase == "destination":
        api(path, "park")
    finished = api(path, "finish")
    if finished["trip"]["phase"] != "finished":
        raise ValueError("도착 처리를 확인하지 못했어요.")
    print(f"시연 도착 완료 · {trip['destination']['name']} · 주행 기록에 저장됐어요.")


if __name__ == "__main__":
    try:
        run()
    except (OSError, ValueError, KeyError, ET.ParseError, subprocess.SubprocessError) as error:
        print(f"시연 도착 처리 실패 · {error}", file=sys.stderr)
        sys.exit(1)
