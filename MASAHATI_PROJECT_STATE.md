# Current continuation — 2026-09-17

This section supersedes older branch/status information below.

- Repository: `Hero9994/Smarters-pro`.
- Baseline: `alpha/masahati-alpha`, commit `6c6b7d66f298298a160708b695b5436ea6530b89` (2026-09-07).
- Active repair branch: `alpha/recovery-reliability-2026-09-16`.
- User request: recover the project, inspect actual behavior, and continue development while retaining the current UI, user-created spaces, keyboard behavior and existing functionality.
- Recovered user APK: `Masahati-Alpha-Latest-Test.zip`, dated 2026-09-07, contains a 302,937,799-byte debug APK; package `app.masahati.mobile.v07`, versionCode 8, `alpha-0.1-dev`.
- Last baseline CI run: `34125793406`. Build/lint/unit tests passed. Instrumentation failed on API 26 and 36 because two UI assertions still expected obsolete labels. Backend smoke job passed at that time; it is not proof of current LLM quality.

## Repairs in this branch

1. Bind assistant work to the source message's space and capture document focus before asynchronous work. Ignore messages newer than the question and discard results whose source moved/deleted.
2. Persist per-document cloud-analysis consent (SQLite v13). Files default to local; chat requests in a space containing unapproved documents stay local, and such spaces are excluded from cross-space cloud memory. Restored backups require fresh consent. An existing file's consent can be changed from its long-press menu.
3. Require an explicitly labelled, valid date before reporting a document start/end date. Never reinterpret a single issue date or borrow a date from an unrelated field. An unresolved date is handled locally without letting the cloud fallback guess it.
4. Prevent a stale reminder backup from delivering the following occurrence early; delivery claims must match the current due occurrence.
5. A server rule fallback with `ok=true` no longer prevents trying an installed local LLM.
6. Update the two obsolete UI tests without changing the layout, and wait for the asynchronous Today summary before asserting it.
7. Correct the local Qwen model's exact size from 977,000,000 to 977,184,032 bytes and pin its download revision. The old rounded value rejected valid completed downloads. Verified against Hugging Face LFS metadata on 2026-09-17; existing SHA-256 matches.
8. Refresh the message list and title in place when an assistant reply arrives, preserving the live composer, unsent draft and selection. Use coordinate-based scrolling instead of `fullScroll`, which steals composer focus on Android 8.
9. Explicitly install `platform-tools` in Android CI. The default setup action tried the retired SDK `tools` package and failed before compilation.
10. Calendar validation in the live document backend: reject impossible labelled dates, ask for source review rather than a model guess, and validate model-derived date metadata too. Deployed `masahati-document-alpha-v2` version 4; restored its source under `supabase/functions/masahati-document-alpha-v2/`.

## Validation status

- Source review and `git diff --check` complete.
- New regression tests added for document dates, cloud consent/migration, chronological context, cross-space reply isolation, deleted document focus, reminder claims and AI routing.
- Android source commit `5e4d6b9fce3110bf6e3a4966ef8ffdd0074a0276`, CI run `35224169870`: build, lint, unit tests, APK identity/signature verification and backend smoke tests all PASSED. All 28 instrumentation tests passed on API 26, and all 28 passed on API 36, with zero skipped or failed tests. This includes composer focus/draft, migration, consent, source-chat binding and reminder delivery regression tests.
- The previous run `35194736934` passed 27/28 on API 26; its sole composer-focus failure was fixed by replacing `fullScroll` with coordinate-based scrolling. Do not report that earlier failure as still open.
- Supabase project was INACTIVE on 2026-09-17. Restored the existing project and confirmed ACTIVE_HEALTHY. Live general-assistant quality remains insufficient, as documented below; smoke-test success does not establish model reasoning quality.
- Local physical-device testing has not occurred.
- The validated APK signer SHA-256 is `341980dbf26b36778af231043e45991f1469b668737032c72958fcdc75af39e4`, different from the recovered user APK. This build is not an in-place update for that APK. No matching signing/keystore secret was found by relevant Vault secret names. No new APK was distributed as an upgrade.
- Subsequent backend-source/audit commits do not change Android source. Backend version 4 was verified with three Node tests and five live cases, and was deployed before the successful backend-regression job in run `35224169870`.

## Live backend findings — 2026-09-17

- Restoring Supabase recovered HTTP 200 responses. Project health does not establish answer quality.
- Synthetic arithmetic probe: three boxes with four books each, five given away. Both `auto` (Nemotron Lightning) and `quality` (Nemotron Ultra) returned 8 instead of 7. Another auto response proposed an unrelated `create_space` action.
- Synthetic correction probe: keys first in a blue box, then explicitly moved to a green bag by the door. Auto fell back to generic note classification; quality mixed the old and new locations and proposed an unrelated document action. Observed response times were roughly 10–16 seconds.
- Document ingestion originally accepted `31.02.2027`. Fixed in backend version 4: three Node regression tests passed, plus five live API cases (impossible date, non-leap February 29, valid leap day, German contract with separate expiry/deadline, Arabic contract). Indefinite contracts with only an issue date already correctly leave expiry blank.
- Re-run pure backend tests with `node --test supabase/functions/masahati-document-alpha-v2/document-dates.test.ts` (Node 24).
- The backend advertises `create_space`, `rename_last_document`, and `move_last_document`, which the current Android action executor does not implement. Do not claim those chat commands work.
- Do not solve these failures by simply switching the default to the tested `quality` strategy: it failed both probes too. Provider/model evaluation and a regression suite are required before claiming general assistant reliability.
- Consent protection currently blocks cloud chat/memory for spaces containing local-only documents, including trash. Derived text left behind when a document is moved or permanently removed still needs provenance tracking; do not claim complete information-flow isolation.

## Next priorities after these repairs

- Add actual Arabic OCR. Current ML Kit Latin recognizer cannot read Arabic script; Arabic text extraction from text-based PDFs is a separate feature.
- Add page rendering + OCR for image-only and mixed PDFs; current PDFBox path only extracts existing text.
- Test scanner crop/shadow cleanup on real paper images, including faint text and colored/stamped documents. Do not promise CamScanner parity from synthetic tests.
- Validate multi-turn reasoning against real model responses and expose degraded/offline status accurately. The backend uses many rules and may return `ok=true` in fallback mode.
- Fix release delivery: stable signing key, version progression, arm64 build and dependency/resource trimming. Recovered debug signer SHA-256: `eed9a02f3af9279fcdb50d87af35c49f83da3154de96f6557e5d0c50c55d41d4`. Its private key is not in the repository; never instruct uninstalling without a verified backup.
- Split the large activity into services incrementally. Do not combine this repair with a UI rewrite or seed fake conversations.

---

# Historical handoff (retained for provenance; outdated versions/branches below)

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


## LOCKED STABLE FEATURES — DO NOT REGRESS
These behaviors are confirmed working by the user and must remain unchanged unless the user explicitly requests a change:
- Current UI/layout/colors and overall conversation appearance.
- Keyboard/composer behavior once verified on device.
- Long-press message actions: copy/copy OCR text, star/unstar, move, share, delete/open.
- Reminder parsing and local scheduling.
- Reminder notification opens the originating conversation.
- Reminder delivery writes a real assistant message into the same conversation.
- No fake/default/sample conversations.
- Scanner and assistant improvements already merged must not be removed while adding local/offline AI.

Any future AI/model work must be additive and isolated behind an engine/router layer so these stable behaviors are not rewritten.
