# Flask and two-pickup update — 2026-11-10

- Web invitation choices reduced to driving / not driving. Internal walk is legacy compatibility for non-driving independent attendees, not a measured transport mode.
- Assigned non-drivers set their own pickup; other attendees have no pickup selector. Server prevents self-assignment and driver edits to recipient locations.
- Maximum2 pickup recipients enforced at create/update/member/join/depart boundaries, including pending recipients. No automatic deletion or rewriting of existing appointments.
- Vehicle browser UI: added3 synthetic friends, selected Kelly and Max, attempted Sam. Third remained unselected and maximum2 toast appeared. Screenshot: two-pickup-limit.png. Existing emulator/APK not rebuilt or installed in this task.
- Browser preview: driving option hid pickup controls; joined without location. Live Flask invitation: non-driver picked place and joined; same coordinate persisted via HTTP state read.
- Live HTTP Flask PATCH with3 pickups returned409.
- 25 unittest cases passed, including full invite/boarding, recipient ownership, binary driving, create/update limit rollback, SQLite persistence, HTTPS origin and host checks, malformed/oversized requests, static file boundary, concurrent stale revision conflict. Log: /tmp/pooly-flask-tests-20261110.log.
- Isolated Flask3.1.3 server on8769 used /tmp/pooly-flask-browser-20261110.sqlite3. Existing personal8765 process preserved and not restarted. New Python logic is not activated in that old process.
- Flask CLI reminders completed once against a separate disposable DB. No background scheduler started on WSGI import.
- PythonAnywhere ZIP extracted to a temporary clean directory: WSGI health, mobile and car HTML returned200. Runtime DB and virtualenv excluded.
- WSGI template, requirements and PythonAnywhere guide included. Real PythonAnywhere account deployment, external Kakao connectivity, real phone background location and real vehicle testing were not performed.
- Profile personalization APIs (seat/climate) remain unimplemented; current persisted data covers driving choice and existing appointment/location/boarding state.
- Sites preview updated successfully at https://pooly-mobile-web-prototype.turtle-02.chatgpt.site; commit0e347ae382bfd36e07235151e1d11ae3c215be68. Static example-data preview remains separate from Flask backend.

- Temporary Flask QA server stopped; personal8765 remains running. Sites owner login gate observed; same local assets were tested, while the signed-in hosted view was not inspected.
