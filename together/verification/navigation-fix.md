# Navigation window fix - 0.3.1-dev

Before: departure only saved trip state; SDK route responses did not establish that OEM navigation was visible.
After: both the real navigation button and departure open the existing OEM navigation window. The app restores its window when activated again. No duplicate OEM Activity is created.

Other fixes required for stable integration:
- Keep one native SDK connection per process across Activity recreation and Gleo actions.
- Ignore cancellation of the previous route while a replacement route is pending; explicit cancellation still ends the request.
- Keep updates active for visible multi-window Activities and restore the pickup window on activation.
- Select a newly created appointment after state synchronization, so navigation uses that appointment.

Verification: 8 Android integration tests passed, including real UI-button/departure flows, focused OEM window, matching destination response, return to pickup, SDK restoration, synthesis, location sharing and the existing pickup workflow. Build and lint passed. Screenshots were inspected; physical vehicle behavior was not tested.

The personal server on port 8765 was reused. Tests used a separate application identity and disposable database. The existing personal appointment was preserved. Browser maps remain previews; actual guidance is in the vehicle navigation app.
