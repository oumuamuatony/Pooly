# Pooly mobile browser prototype verification — 2026-11-10

## Implemented
- Browser-native mobile UI, no native Android bridge requirement.
- Home, invitation and travel mode, own pickup map/list picker + meeting note, pickup status, full journey, inbox, profile/location consent.
- Recipient-only pickup selection and changes. Driver journey has no pickup edit button.
- Manual preview controls for roles and journey stages; example-data label; default preview does not mutate actual server data, request GPS or send real messages.
- Real invite API integration using a separate browser identity key. No fabricated driver coordinates or ETA in live mode.
- Existing vehicle application and running personal server were not restarted.

## Verification performed
- `node --check together/web/mobile-web.js` passed after final frontend edits.
- `python3 -m unittest discover -s together/tests -v`: 19 passed. Includes recipient-only pickup ownership checks. Log: /tmp/pooly-mobile-server-tests.log.
- Browser UI: demo invite -> location list selection -> explanatory note -> save -> join -> pickup status. Chosen place/note preserved. OpenFreeMap map tiles visually loaded.
- Browser UI: demo arrival -> boarding confirmation waits for driver. Driver role has zero pickup edit buttons.
- Actual API/browser E2E on isolated port 8769, disposable database /tmp/pooly-mobile-web-20261110.sqlite3: created synthetic driver/invite; browser selected and saved own place/note; joined real invitation; server persisted accepted membership and coordinates/note.
- HTTP: driver PATCH of recipient pickup returned403. Browser boarding confirmation persisted; driver board action advanced to destination. Browser then showed boarding complete and zero pickup editing buttons.
- Live browser console returned no errors during boarding test.
- Responsive DOM checks: widths360,430,1280 had equal innerWidth and document scrollWidth. Mobile status and desktop presentation visually inspected.

## Verification boundaries
- Existing8765 Python process was deliberately not restarted. New web assets are read dynamically, but the newly strengthened backend ownership checks require a later approved restart there.
- Isolated8769 test server was used for new backend checks and will be stopped after verification.
- Hosted Sites package is a static example-data preview; actual backend is not published or linked to that hosted domain.
- No physical phone or vehicle test. No background web GPS/push guarantee.
- No nationwide POI key configured; saved examples or direct map selection work. No heatmap/personalization implementation.

## Hosted result
- Site: https://pooly-mobile-web-prototype.turtle-02.chatgpt.site
- Private publication succeeded. Project appgprj_6af2b8250b7c8191bae68f0299ae35a6, commit2905b215b931cbd63a3debc8e14c023405bad2c0.
- Browser confirmed owner-login gate. Hosted app view behind sign-in was not inspected; the identical local package was tested.
- Final map canvas click selected a coordinate and enabled save, with no browser console errors.
- Final screenshots: mobile-home.png, mobile-picker.png, live-boarded.png.
- Temporary viewport override reset; owned isolated test server stopped. Existing personal8765 server preserved.
