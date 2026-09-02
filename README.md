# Masahati

Masahati is a private, local-first personal workspace for notes, documents, reminders, and an AI assistant.

## Current milestone

**v0.2 — Android Foundation**

This branch establishes the native Android baseline before scanner, sync, OCR, and agent features are added.

### Foundation guarantees

- Android application ID remains `app.masahati.mobile`.
- No secrets are stored in source control.
- Cleartext network traffic is disabled.
- App backups are disabled by default because future data may contain sensitive documents.
- The app can launch without any cloud or AI dependency.
- CI runs lint, unit tests, and a debug APK build on every relevant change.

See [`docs/V0_2_FOUNDATION.md`](docs/V0_2_FOUNDATION.md) for the acceptance criteria.
