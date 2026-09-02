# Masahati v0.2 — Android Foundation

## Purpose

This milestone replaces the fragile APK-generation path with a reproducible native Android project.

## Non-negotiable contracts

1. Application ID stays exactly `app.masahati.mobile`.
2. v0.1 remains untouched as the rollback APK.
3. The app must start without cloud, AI, scanner, or account services.
4. No private signing material or API secrets are committed.
5. Cleartext HTTP is disabled.
6. Android backup is disabled until the encrypted backup policy is designed.
7. CI must pass lint, unit tests, and a debug APK build before the branch is considered stable.

## Acceptance gates

### Automated

- Gradle configuration resolves on JDK 17.
- Android API 36 build succeeds.
- `lintDebug` passes.
- `testDebugUnitTest` passes.
- `assembleDebug` produces an APK.
- The APK is published as a CI artifact.

### Physical-device test after automated gates

On the Samsung Galaxy S25 Ultra:

- Install the v0.2 debug APK separately from the signed v0.1 rollback build.
- Cold-start the app at least 10 times.
- Send the app to background and restore it at least 5 times.
- Rotate or otherwise recreate the activity and verify no crash.
- Close and reopen the app and verify the local launch counter persists.
- Confirm Android Back exits normally.
- Confirm there are no unexpected permission prompts.

## Deliberately not included yet

- SQLite document database
- Supabase sync
- Native document scanner
- OCR
- Real AI assistant
- Production signing

These are separate phases and are not allowed to destabilize this foundation.
