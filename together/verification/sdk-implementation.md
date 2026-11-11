# SDK integration verification

- App: io.pickuptogether.app, version 0.3.0-dev (3).
- Preserved original car/phone design. Added native features within existing connection and moving views.
- Server: 13 tests passed. Existing personal server was reused, not restarted.
- Android: 6 integration tests passed in a separate application identity and disposable database.
- After guarding non-finite optional FusedLocation fields: location-sharing test and debug build/lint passed again.
- NaviHelper: actual ROUTE_DRIVE and matching destination ETA received; binding restored after Activity recreation.
- FusedLocation: actual pleos-fused source received; sharing started and stopped with consent.
- TTS: actual ready / speaking / ready events received for an on-device synthesis request.
- STT: service initialization succeeded. Live microphone speech recognition remains untested.
- Vehicle: capability checks and read callbacks succeeded. Door/odometer values do not automatically confirm boarding or replace GPS distance records.
- Gleo: schema has three functions; an actual Android action intent used the stored authenticated session and completed a server status lookup. Callback format and parameter/appointment boundaries were tested.
- Gleo vendor schema review/activation and an actual Gleo voice invocation remain unverified.
- Current Gleo callback target is the system package verified in this emulator. Physical vehicle target must be verified before release.
- Emulator checks do not establish physical vehicle behavior.

Evidence: android-sdk-tests.xml, android-sdk-location-tests.xml, android-sdk-build-and-tests.txt, android-sdk-final-build.txt, pleos-sdk-runtime.txt, native-sdk-connections.png, apk.sha256.
