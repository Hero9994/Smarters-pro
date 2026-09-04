# MASAHATI PROJECT STATE

This file is the persistent handoff for future ChatGPT sessions. Read it before changing the app.

## Repository
- Repo: Hero9994/Smarters-pro
- Working branch: develop/v0.6-agent-scanner-polish
- User-facing baseline name: v0.6
- Android package: app.masahati.mobile
- Keep the current UI unchanged unless the user explicitly requests a UI change.
- Never add fake/default/sample conversations or spaces.

## Continuity / repository access
If the GitHub App reports 0 installations, DO NOT stop the project and DO NOT ask the user to reconnect GitHub again.
- Public repo reads can use normal GitHub access.
- Verified write fallback: Supabase project hxrvlvqlkfylbjicdfzs contains a Vault secret named github_masahati_token.
- Use the secret only inside Supabase/Vault-backed GitHub API calls. Never expose or print the token.
- The old temporary GitHub bridge Edge Functions are not the source of truth.

## Current Android changes on this branch
Latest functional Android HEAD before this state-file commit:
- f0891a83db6221336effeb868cffdc0609ad401a — context-aware local fallback
Prior relevant commits:
- 78b096bb291ed9d88c06fc7cdb1771c4a2abeee1 — adaptive scanner shadow cleanup
- f10c4d0f2788715a525798e92e41c6864bd9a8cb — structured AI context from Android
- 2dbb28c96313fe9b67339c3fdcec3a47a04fd303 — SmartSearch tests
- ed301490bfb6a7173253c6d69c685a05ed684b56 — ranked search + expanded AI context
- 3407536fcf4e544fb285f4468d9feb5e7e4f522a — SmartSearch implementation

### Assistant/context
Android now sends:
- up to 20 recent messages
- message kind
- filename/display name
- classification
- tags
- summary
- text
- OCR text
- created time
- current space
- space list
- current document metadata/OCR
- now + timezone

OCR-only documents are included in recent AI context.

### Local search
SmartSearch now:
- normalizes Arabic spelling/diacritics/tatweel
- handles Arabic definite article variants
- weights filename/title > tags > classification > summary > text > OCR
- tolerates one-character OCR mistakes
- can match useful German substrings/compound words
- ranks by relevance, then recency

### Default conversations
The active database implementation no longer seeds the old defaults:
- ملاحظات
- يومي
- أوراقي
- أفكار المشروع
Do not reintroduce any defaults.

There is an older duplicate database class under app/.../mobile/data/MasahatiDatabase.kt. It appears unused by the current MainActivity. Do not instantiate it; remove its old seed block when convenient.

### Scanner
Google ML Kit Document Scanner remains in FULL mode.
DocumentImageEnhancer is adaptive:
- measures uneven illumination first
- leaves clean ML Kit output unchanged when correction is unnecessary
- applies local correction only when shadows are detected
- protects dark text/ink
- caps gain to reduce washout

## Production AI backend
Supabase project: hxrvlvqlkfylbjicdfzs
Production Edge Function: masahati-agent-dev
Current production version: 16

Production v16 keeps quota/security logic and adds:
- structured document-aware context
- currentDocument/appState use
- deterministic fast routing for explicit search
- deterministic fast document date/summary/reference handling
- deterministic document-enrichment action for follow-up descriptions
- Lightning model first, Nano fallback
- classification override for document/work context
- truthful tool execution behavior

Verified production examples:
1. "وين حطيت عقد الإيجار؟" -> search action query "عقد الإيجار"
2. "شو تاريخ انتهاء هاد العقد؟" with OCR ending 31.12.2026 -> answers 31.12.2026, classification document
3. "شو فيها؟" on Wohngeld OCR -> explains the document from context
4. "هاي ورقة تسمح بالنقل من البيت للطبيب" after a scanned file -> enrich_previous_document action

Candidate function masahati-agent-v06-candidate exists for isolated testing; production is currently v16.

## Verification
GitHub Actions Android CI run 33871667746 for commit f0891a83db6221336effeb868cffdc0609ad401a succeeded.
The workflow ran:
- :app:lintDebug
- :app:testDebugUnitTest
- :app:assembleDebug
- APK identity/signature verification

Artifact:
- masahati-v0.7-agent-scanner-apk
- artifact id 9936263566
The branch's build metadata currently labels the debug build 0.7.0-dev even though the user refers to this workstream as v0.6. Do not change UI/product behavior just to reconcile naming unless explicitly requested.

## Required next regression work
Continue improving, with no UI changes:
- richer semantic classification/metadata extraction
- more conversational follow-up tests across 2–3 turns
- mixed German OCR + Arabic questions
- local search ranking with real user documents
- scanner testing using real hand-shadow photos from the user
- compare OCR/readability before/after shadow correction where practical

## Handoff instruction
When the user says "نكمل مشروع مساحاتي":
1. Read this file.
2. Use branch develop/v0.6-agent-scanner-polish unless this file records a newer branch.
3. Do not stop because GitHub App says 0 installations.
4. Continue with the verified Supabase Vault GitHub-write fallback if needed.
5. Keep UI unchanged.
6. Do not add default conversations.
7. Build and test before handing over an APK.
8. Update this file after meaningful changes.


## 2026-09-04 chat/reminder usability fix
Latest verified HEAD: b8233b4efc165603747dccb4431959665a6ed763
CI run: 33886899538 — SUCCESS
APK artifact id: 9942334877

Implemented:
- MainActivity uses adjustResize so the composer stays visible above the software keyboard.
- Composer scrolls into view on focus/click.
- Long press on text/file messages opens practical actions instead of delete-only:
  copy/copy OCR text, star/unstar, move to another space, share, delete, and open file where applicable.
- Message starred state is persisted in SQLite (DB v4 migration) and starred items are accessible from the chat menu.
- NaturalReminderParser handles colloquial Arabic/German/English reminder times locally.
- Time reminders bypass cloud AI and are resolved before any remote request.
- Supported examples include relative delays, tomorrow/today, one-time weekdays, explicit weekly recurrence, Arabic digits, and multi-turn morning/evening clarification.
- Do not treat a weekday mention as weekly unless recurrence is explicit.
- Latest unit tests cover these reminder cases and CI passed build, lint, tests, APK identity/signature verification.

Next device checks:
1. Keyboard/composer behavior on the user's Samsung Android 16 device.
2. Long-press menu actions.
3. Real notifications: "ذكرني بعد دقيقتين"; "ذكرني بكرا الساعة 17:30"; ambiguous "بكرا الساعة 5" then follow-up "مساء".


## 2026-09-04 keyboard + delivered reminder message fix
Latest verified functional HEAD: dee8b72b8e44b8ba7a3cb23b47d489b4856b5ff5
CI run: 33891063512 — SUCCESS
APK artifact id: 9943963344

Implemented:
- Added View/IME WindowInsets handling in MainActivity. adjustResize remains enabled, and root bottom padding now follows max(IME, system bars), so the composer should move above the Android 16 keyboard instead of being covered.
- ReminderReceiver now creates a real assistant message in the originating space when the alarm fires.
- Notification text is the same delivered reminder message.
- Tapping a reminder opens the originating conversation even when MainActivity is already running (onNewIntent handling).
- ReminderDeliveryText cleans reminder command/time words from the task.
- Verified exact example:
  input: "ذكرني اعبي ديزل اليوم الساعة 20.30"
  delivered message: "تذكير: اليوم الساعة 20.30 اعبي ديزل"
- Added unit tests for delivered reminder message formatting and recurring-text cleanup.
- CI passed lintDebug, testDebugUnitTest, assembleDebug and APK identity/signature verification.

Device checks requested next:
1. On Samsung Android 16, open keyboard and verify the composer remains fully visible above it.
2. Create "ذكرني اعبي ديزل اليوم الساعة <2 minutes from now>", close/minimize app, verify notification text, tap it, and confirm the assistant reminder message appears in the same conversation.
