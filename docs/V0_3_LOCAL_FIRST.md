# Masahati v0.3 — Local-first working app

This milestone turns the Android foundation into a usable private self-space app while remaining fully local.

## Included
- Native Android home screen with seeded personal spaces.
- Create, rename, pin, archive, restore, and delete spaces.
- Search space titles, text messages, and attachment names.
- Native SQLite persistence (`masahati.db`).
- Open a space and add text messages.
- Import PDF, images, text, Word, and other files through Android's document picker.
- Copy imported files into app-private storage with a 50 MB per-file limit.
- Open stored attachments through a read-only content provider and temporary URI permission.
- Delete messages and their private attachment files.
- Restore the currently open space across Activity recreation.
- Debug package remains isolated as `app.masahati.mobile.dev`, so rollback builds can remain installed.

## Device-test acceptance
1. Launch without crash.
2. Create and rename a space.
3. Add text, close the app, reopen, and confirm persistence.
4. Attach a PDF or image and reopen it from the space.
5. Search for text and attachment filename.
6. Pin, archive, restore, and delete a temporary space.
7. Confirm Android Back returns from a space to the home screen.

## Later phases
Cloud sync/account login, scanner/camera, OCR, AI assistant, and Android reminders are intentionally excluded until this local-first baseline passes physical-device testing.
