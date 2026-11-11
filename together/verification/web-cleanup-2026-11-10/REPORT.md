# Mobile web cleanup — 2026-11-10

- Home: appointment list and detail action; greeting, preview badge, preparation tag, pickup box and lower help removed. Navigation has Home / Journey only.
- Pickup: idle heading changed, readiness controls and journey shortcut removed. Compact location row with change action. Moving demo shows preparation heading and example ETA.
- Location picker: no search/history list/helper copy. Larger map (470px at 360×800); map click, coordinate marker, optional location note and save tested.
- Journey menu: authorized past appointments sorted newest first, separate from current detail. Ended appointments have no pickup editing or active pickup action.
- Browser checks at 390×844 / 360×800: no horizontal overflow; saved map point and note; accepted invitation; moving/finished demo states; history detail read-only.
- Live Flask QA on owned port8769 with disposable /tmp/pooly-web-cleanup-20261110.sqlite3: browser joined two synthetic invites. Completed one via domain depart/park/finish. Home showed current appointment only; Journey showed completed one only; completed detail editingButtons=0. Fixed selection of a new invite when another appointment exists and reset member state on appointment changes.
- Existing personal server8765 and its records preserved. No emulator changes, no real device GPS shared. Backend source unchanged.
- Actual remaining pickup time is still unavailable from current server payload: live UI honestly shows “도착 시간 확인 중”, while demo uses a clearly scoped example time. No final-destination ETA is substituted for pickup ETA.
- Hosted Site is static example-data prototype, not the Flask API. PythonAnywhere package refreshed; actual PythonAnywhere deployment not performed.

- Published successfully to https://pooly-mobile-web-prototype.turtle-02.chatgpt.site (source commit ab188236902c7e9bcbd666db08a75bcba8f70955). Browser reaches owner-private ChatGPT sign-in gate; signed-in hosted contents were not inspected. Local matching assets tested above.
- Temporary viewport overrides reset; owned8769 QA server stopped. Personal8765 listener retained.
