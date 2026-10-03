# Checkpoint 2026-10-03 — scanner 0.6 native gates passed
Accepted native source: `8093f50e2ccaa6a95d4dca03a6c343e42db0e4d0`.
CI `37155633765`: verify, backend-regression, native API26 and API36 ALL SUCCESS.
API26 finished 78 cases including 2 intentional diagnostic skips; API36 finished
76 cases. Decode actual counts from XML in release evidence. 300 real frames
detected, mean boundary edge error 2.61206 source px, P95 6.60429; 148 manual
review cases; unsafe automatic crop proposals accepted 0 in this dataset.
Processing 300 same frames: 1684 OCR lines, 9 codes, 36 weaker processing,
8 unfiltered safe fallbacks, mean isolated processing/validation 912.73ms.
50MP output 3741x1870: API26 pipeline4356ms, sampled PSS433695744B;
API36 pipeline5509ms, sampled PSS391084032B. Emulator samples, not device maxima.

The accepted camera test uses the SAME six-source-pixel outward crop padding as
production. It saves actual before/aligned/final images and camera-report.json.
No edge-ink exclusions were added to the capture guard. The sharper capture and
removed punctuation/signature/edge negatives all pass. The filter guard is strict.
Earlier native candidates failed this new fixture/guard and must NOT be released.

The signed release helper is gated on EXACT source8093/CI37155633765 and preserves
the existing preview certificate/package. Temporary OIDC exchange
`masahati-preview-signing-06` authorizes only the pinned release workflow SHA/run,
successful CI, correct repo ID/ref/event/attempt and short-lived signed identity.
Default repository policy is disabled and expires 23:00 UTC. Retire after delivery.
No application schema/data was modified. The old signing/Vault credentials must
never appear in source or logs. The local execution environment is disconnected;
all code is preserved on GitHub. The prepared work branch validates release code
and only signs on the alpha branch after all gates. Public APK remains0.5 until
the new signed release passes a full unauthenticated download verification.

---

# Current scanner work — 2026-10-03, preview 0.6 candidate

Source work is on `alpha/scanner-professional-2026-10-01`. Version code 13;
preview package `app.masahati.mobile.preview`. Latest published APK is still
[0.5](https://github.com/Hero9994/Smarters-pro/releases/download/masahati-preview-0.5/Masahati-Preview-0.5.apk)
until the complete 0.6 CI gates and persistent-key signing/public-download check pass.

0.6 adds one-to-one number/OCR protection, four-corner Jacobian and source-ink
coverage validation for UVDoc, adaptive real-JPEG best-frame capture with
registration/content guard, immutable second capture with backup restoration,
Code 128/curved-page/content-loss negative tests, and 300-frame native paper
processing diagnostics. Local build/native-test compilation passed and all
117 JVM tests passed. API 26/36 full regression is pending. No new weights or
runtime; FSENet external weight grant remains unresolved, original GCDRNet weights
remain inaccessible anonymously. The experimental RGB edge fallback was rejected
by measured regression; existing crop engine remains. See `docs/scanner/UPGRADE_0.6.md`.

Recovered verified 0.5 baseline artifacts are in `docs/scanner/benchmarks/fdebd41`.
Signing certificate remains `134b86f90a4d739167f9890139fefb665979c410fa2b011e3b82e7bd1bb0cd9a`.
Never replace the key or publish an APK before all CI jobs succeed.

---

# Scanner crop correction — 2026-09-30 (Android preview 0.3)

Signed preview 0.3 is published at
https://github.com/Hero9994/Smarters-pro/releases/tag/masahati-preview-0.3
(direct APK: https://github.com/Hero9994/Smarters-pro/releases/download/masahati-preview-0.3/Masahati-Preview-0.3.apk).
Source `ed4e6d46df491e3895937fd39f0578e658866864`, CI `36759821080`:
build/lint/unit/backend gates passed, and all 46 Android tests passed on each
API 26 and API 36. Signed size 327,732,915 bytes, SHA-256
`d4e0a9a3f2a44b03e015829861459f4466dba6dc1f95b8ba71cbe729134b0361`.
After publication the public GitHub URL was downloaded without authentication
and passed size, SHA, ZIP CRC, APK signature and 16 KiB native library checks
against `signing/preview-0.3-verification.json`. This is an in-place update for
the existing *preview* package; the recovered original remains a separate app.
Physical Samsung installation and real-paper auto-crop remain unverified.

The ML Kit FULL scanner already detects and deskews page borders. Preview 0.2
discarded its PDF and applied a second OpenCV perspective crop to its JPEG, which
could select an inner printed border as the page edge. Preview 0.3 saves the
scanner PDF verbatim, uses JPEG pages for OCR only, and never runs the second
geometry crop in the JPEG-only fallback. An emulator regression tests a page with
a prominent inner border; physical-phone results on the supplied paper remain
unverified. Version code is 10 with the same persistent preview signing key.
No personal document image is checked into Git.
The scanner release gate also exposed a live Gemini omission: an explicit German
contract cancellation deadline was quoted as action text but not returned as a
due date. A bounded source-clause check now recovers that date only when the
model has quoted the complete mandatory contract clause; optional clauses remain
non-actionable. Document function v11 (`7301f158e13806294de9ad9bba6890ac01608f0b3bb814f3b7e9261ad6047dce`)
was deployed and both German and Arabic contract examples returned their distinct
dates and required action. The seven live synthetic semantic checks passed; this
does not prove accuracy on the supplied personal paper. Android API 26 initially
rejected a `PdfRenderer` read of the test's generated fallback PDF; the final CI
checked PDF structure and page geometry with the app's PDFBox fallback on both
Android versions successfully. It does not establish PdfRenderer compatibility
for that rare JPEG-only fallback on Android 8.

---

# Gemini activated and verified — 2026-09-30 (current status)

This section supersedes the connection status in the dated historical entries below.
Continue on `alpha/recovery-reliability-2026-09-16` in `Hero9994/Smarters-pro`.
The user's free-only decision remains in force; do not enable billing automatically.

## Activation and deployed backend

- User explicitly authorized completing setup on September 29. Google AI Studio
  showed project `my-project-111-390221` as **Free tier**, still offering **Set up
  billing**. Saved `MASAHATI_GEMINI_API_KEY` and
  `MASAHATI_GEMINI_FREE_TIER_CONFIRMED=true` in Supabase at 17:17:20 UTC that day.
  No paid setup occurred. No key is stored in this repository or the APK.
- Default and actually reported model: **gemini-3.1-flash-lite**.
- Active document function `masahati-document-alpha-v2`: **version 11**, bundle
  SHA-256 `7301f158e13806294de9ad9bba6890ac01608f0b3bb814f3b7e9261ad6047dce`.
- Active conversation function `masahati-agent-dev`: **version 32**, bundle
  SHA-256 `415760d7bf638d6c6bed2ce7a6503cece6e536b49fa8902015560cebb793b10a`.
- These are backend-only changes. Use the corrected, already verified preview APK
  described below; no Android rebuild or reinstall is necessary for Gemini activation.

## Repairs and observed verification

- Gemini document requests now require a structured schema and exact source quotes.
  The prompt distinguishes school invitations, invoice totals/paid/remaining values
  (including zero), cancellation confirmations, and complete labelled date clauses.
- The evidence validator recovers a short date quote's actual source clause before
  checking the role; it still rejects impossible dates and borrowed labels.
- An explicit transient Gemini HTTP 500/502/503/504 gets at most one same-provider
  retry under the same deadline (23 seconds for documents, 24 for conversation).
  Quota/auth/client errors get no retry or provider change. No paid fallback.
- Safe failure codes now distinguish capacity, timeout, authentication, blocked,
  incomplete/malformed output and configuration errors. Conversation failures have
  zero confidence and no actions; no upstream body or secret is returned or logged.
- **34/34 pure backend tests passed** on September 30, including retry limits,
  evidence checks, timeout/auth/quota handling, and sanitization. `git diff --check`
  passed. No unchanged Android build/emulator suite was rerun.
- Final full deployed evaluation on September 30: **7/7 document cases passed with
  semantic Gemini analysis, zero rule fallbacks; 6/6 conversation cases passed**.
  Receipt: `verification/gemini-free-2026-09-30.json`. These are synthetic examples,
  not the user's private papers. Documents include cancellation, paid invoice,
  benefit refusal, medical transport, salary, Arabic school invitation and invalid
  date. Conversations cover arithmetic, latest correction, pronoun reference,
  document amount, missing name and negated actions.
- Preserve the earlier failures in the assessment: September 29's post-fix run
  passed 6/7 semantic document cases and 5/6 conversation cases; medical transport
  and the missing-name question fell back without a precise upstream reason. Both
  passed a targeted September 30 check and the final complete run. Current passing
  tests do not establish continuous free-service availability or perfect accuracy.

## Remaining hands-on validation

The corrected preview's physical installation on the user's Samsung and real-paper
scanner/OCR/reminder tests remain unverified. Keep the original app and its data;
the preview installs separately. Existing derived-text privacy/provenance limits
documented below are unchanged. Do not describe the whole app as 100% complete or
production-ready. The app's public key is compatibility gating, not user identity.

---

# Installation-file repair — 2026-09-28

The first user download failed Android installation because the delivered APK was
truncated. The downloaded Library version 0 was 323,121,060 bytes, SHA-256
`3569f611cb33c31d172cd8421006be41b3b70eb7b614d95455f0141c411f92f8`.
Every available byte matched the start of the correct signed APK, but the final
4,595,471 bytes (including the APK signing block and ZIP directory) were absent.
The exact preparation/transfer stage responsible is not established; do not blame
the user's phone, Google, or a signing-key mismatch for this observed failure.

- Recovered the same verified CI artifact and persistent preview signer. Android
  source and package/version remain unchanged; this repairs the downloadable file.
- `scripts/sign-preview.py` now signs to a temporary file, verifies and flushes it,
  then publishes the delivery filename atomically. It records the exact byte count.
- Added `scripts/verify-preview-download.py` and trusted public receipt
  `signing/preview-0.2-verification.json`. The gate checks size, SHA-256, ZIP CRCs,
  20 stored native libraries' 16 KiB alignment, and the persistent APK signature.
- A regression check reproducing the original truncation was correctly rejected.
  The complete replacement was saved, independently materialized, and passed the
  entire gate. Correct size: **327,716,531 bytes**. SHA-256 remains
  `b6a7e55b81aa818aa5b9ba884e2fda7d78f181144313de31e8eebe42a165a5ea`.
- Replaced the existing Library item `libfile_b911853282f88191a65b877bfdd0acaa`;
  current version **1**, file `file_000000005c0881f893b4e371ae33a33c`.
  Verified download: `/workspace/scratch/91ed36f9d2a3/verified-download/Masahati-Preview-0.2.apk`.
  Recover version 1 or later, never the original truncated version. Installation
  on the physical Samsung still needs the user to try this corrected download.
- Gemini is still not activated. A fresh September 28 browser attempt again
  reached Google sign-in with HTTP 502 / Connection refused. No sign-in values,
  API key, Google billing, or paid service were submitted or enabled. The account
  owner can add a Free Tier key directly to the existing server secrets following
  `FREE_AI_AND_PREVIEW.md`; never request a key in chat. Live semantic verification
  remains necessary after configuration, with no paid fallback.

No unchanged Android or backend suite was rerun for this packaging-only repair;
the full CI result below still describes the exact APK source.

---

# Free development and separate preview — continuation 2026-09-27

This section supersedes older delivery/provider status below. User chose **free only**;
paid AI requires a later explicit decision and is not automatically enabled after tests pass.
Current source: `50cd1fe74a330acb7b9be964826c73d0f783adbe` on
`alpha/recovery-reliability-2026-09-16`. See `FREE_AI_AND_PREVIEW.md`.

- Shared server provider configuration prepares Gemini Free Tier, defaults to
  `gemini-3.1-flash-lite`, accepts only reviewed free-tier text models and requires
  confirmation that the Google project has no linked billing. The flag records an
  operator check, not a billing API guarantee. Unknown models, mixed settings and
  partial settings fail closed. Generic custom providers need a separate explicit
  enable flag. No Gemini credential, Google billing or paid provider was activated.
- Both endpoints use the shared settings; Gemini requests reserve room for reasoning
  and complete JSON, disallow redirects and make no silent paid/provider fallback.
  Existing source/date/action validation and Android cloud-consent boundaries remain.
- Deployed on 2026-09-26: document function **v7**, assistant function **v29**, ACTIVE.
  Existing free BlockRun routes remain default and have historically hit capacity.
  Gemini has NOT undergone live semantic evaluation. All **27 pure backend tests**
  and seven limited-rule document fixtures passed locally on September 26.
- Gemini browser setup reached a Google sign-in wall. A September 26 browser read
  was rejected by automatic approval review because of a usage limit. On September
  27 the review limit was no longer reported, but Google sign-in returned 502
  Connection refused twice (one reload). No sign-in values were entered. This is a
  browser connection failure, not evidence of a Google bot challenge or outage.
- Separate preview: `app.masahati.mobile.preview`, launcher **مساحاتي تجريبي**,
  versionCode **9**, `alpha-0.2-preview`, release-style/non-debuggable, arm64-v8a +
  x86_64. Existing `app.masahati.mobile.v07` remains installed alongside it. It is
  not an in-place update and cannot automatically access that app's private data.
  Export/import a backup copy to move data; keep the original. Review duplicate
  reminder schedules when running both copies. Cloud consent resets on import.
- Persistent private preview signing key was created and backed up in the existing
  Supabase Vault secret `masahati_preview_signing_v1`. It was successfully recovered
  after workspace pruning; do not regenerate. Public certificate SHA-256:
  `134b86f90a4d739167f9890139fefb665979c410fa2b011e3b82e7bd1bb0cd9a`.
  CI never receives the key. Its unsigned preview bundle includes source provenance
  and the official SDK apksigner; `scripts/sign-preview.py` verifies hashes, source,
  package and signer before producing the signed user APK. No original signing key
  was recovered. GitHub Actions secret permissions remain unchanged.
- First preview CI `36232515048` on `fc48cd4`: all 45 app instrumentation tests
  completed successfully on both API 26 and API 36. Build/lint failed on missing
  ChromeOS x86_64 support, and API 26's *post-test* preservation check used an
  unavailable `run-as ... test` command. Added x86_64 support and changed that check
  to read the preserved marker with `cat`. No test assertion or lint gate disabled.

## Current validation and delivery

- Final CI [36342066327](https://github.com/Hero9994/Smarters-pro/actions/runs/36342066327)
  on `50cd1fe74a330acb7b9be964826c73d0f783adbe`: **ALL GATES PASSED** on
  2026-09-27. Build, both lint variants, unit tests and APK identity/alignment checks
  passed. **45/45 instrumentation tests passed on API 26 and API 36**, zero failures
  or skips in progress logs; both old-package private marker checks and package
  presence checks passed. Backend **27/27 pure tests**, seven limited-rule fixtures
  and four live contract/agent checks passed. This does not establish Gemini quality.
- Final jobs: verify `108683964980`; API 26 `108683965166`; API 36 `108683965098`;
  backend `108685381450`. Source commit includes the ABI and portable marker fixes.
- Downloaded unsigned bundle artifact `10939820955`; verified its GitHub SHA-256
  `41a903da7326ca8c0261282f9b2569720a0b38d901c18e4974a68af6002b378a` and all internal
  source/file checksums. Actual APK includes arm64-v8a and x86_64 native libraries.
- Produced **Masahati-Preview-0.2.apk**, **327,716,531 bytes**. Verified APK Signature
  Schemes v2 and v3 with the persistent certificate above. Signed APK SHA-256:
  `b6a7e55b81aa818aa5b9ba884e2fda7d78f181144313de31e8eebe42a165a5ea`.
- The initial Library write reported success but stored a truncated file. The old
  `file_00000000ef6c81f58c35914a9451bde3` is invalid. Use the corrected version 1
  and verified download listed in the September 28 repair section above.
- This is the first delivered separately signed preview, not a compatible update to
  the recovered original installation. The user has not yet installed/tested it on
  their physical Samsung. Preserve the original app and its backup.

## Remaining scope

Physical Samsung testing, real-paper/scanner quality and live Gemini/provider
validation remain outstanding. Passing CI is not proof of 100% accuracy or no bugs.
The prior derived-text privacy/provenance limitation remains unresolved. The free
public app key is not user authentication; real identity and enforced spending caps
are still prerequisites before paid traffic. Do not claim strong semantic AI has
been activated just because this preview installs or its tests pass.

---

# Document-understanding continuation — verified 2026-09-26

This section supersedes the earlier status below. User now wants the app to understand and classify papers intelligently. Branch: `alpha/recovery-reliability-2026-09-16`. Validated source commit: `8e96f42a1257b057b7e112ac572974435f1a9baf` (includes backend v6 source and corrected instrumentation fixture).

## Implemented

- Replaced document-v2's keyword-count confidence shortcut and nested call to old v1 with a bounded semantic extraction request on every nonblank, consented document. The existing URL remains compatible; deployed function `masahati-document-alpha-v2` **v6** returns schema 3. No new account, paid model or provider key was enabled.
- Prompt distinguishes communicative purpose: invoice vs contract mention, cancellation confirmation/request, benefits refusal, medical transport prescription, payslip, school invitation, etc. The ontology has 22 specific types plus `other`; this is schema coverage, NOT a validated accuracy claim for every type.
- Structured source-checked issuer/names/references, separate date roles and amount roles, explicit action status, review reasons, original-language quotes. Numeric values must occur in real OCR quotes; dates also pass calendar/role checks. Missing, conflicting, fabricated, partially read and truncated information stays uncertain. A short action quote cannot hide negation in its full sentence. Model-generated actions remain an empty array.
- Arabic summaries are generated from accepted fields. Advanced understanding and limited fallback are visibly distinct; raw percentage confidence is no longer shown for schema 3 in the new Android details dialog. A conservative internal confidence estimate is retained for old-client compatibility, not presented as measured accuracy.
- Android sends up to 24k OCR characters with an explicit truncation flag, does not use an old filename/summary as factual evidence, retains structured limited output rather than replacing it with generic local chat, and preserves metadata/tasks when a generic reply has no document schema. Existing cloud-consent checks remain in place; no user document was used in testing.
- Generated tasks require an original OCR excerpt. Reanalysis preserves the ID of an unchanged task and does not reopen a completed/skipped task. Uncertain analysis does not clear earlier tasks; a grounded explicit no-action result can clear obsolete open generated tasks. Existing spaces, layout and user filenames remain unchanged.

## Verification and actual service limitation

- Baseline live v4 returned a paid invoice as requiring payment, confidence 0.97, despite `Bitte nicht erneut überweisen`. A cancellation confirmation was classified `other`. These were synthetic probes.
- Local backend regression suite now **23/23 PASS**. Seven synthetic document evaluations pass using the explicitly labelled rules fallback. Additional cases cover fabricated quotes, omitted negation, optional appeals, impossible/omitted dates, mixed document headings, and separate paid/remaining amounts.
- Live deployed v5: **7/7 expected structured fallback results**, **0 semantic results**. Every request hit `provider_capacity`. This is NOT evidence that an LLM understood those documents. Post-v6 live regression status: PASS: an impossible date and a mixed-document file both suppressed automatic tasks. The impossible-date call returned a semantic needs-review result; the mixed-document call reported provider capacity. This confirms intermittent availability, not stability.
- Current official BlockRun catalogue listed Lightning and Ultra unavailable; Nano Omni was listed available, but its real completion request returned HTTP 429 `FREE_MODEL_FAILED`. Document requests now use the free Nano Omni alias and report actual response model when available, with a 23-second bound. Optional `MASAHATI_CHAT_URL/MODEL/API_KEY` remains server-only and fails closed on partial config. The assistant function v28 is unchanged.
- Final Android CI [36161677093](https://github.com/Hero9994/Smarters-pro/actions/runs/36161677093): **ALL GATES PASSED**. Build, lint, unit tests and APK checks passed; all **45 instrumentation tests completed successfully on API 26 and API 36**. Progress logs reported zero skipped/failed tests. Backend: **23/23 pure tests**, seven synthetic rules evaluations and four live contract/agent checks passed. Run completed 2026-09-25; final logs reviewed 2026-09-26.
- Final job IDs: verify `108159492720`; API 26 `108159492727`; API 36 `108159492387`; backend `108161232592`. Four new instrumentation cases cover repeated analysis/task completion, metadata preservation, fake evidence/partial OCR, and Arabic structured details. Earlier OCR/conversation tests remain included.
- Earlier run `36160858417` failed at instrumentation compilation because the new test fixture missed a closing parenthesis. Fixed in `8e96f42`; final run above supersedes it. No production assertions were weakened.
- Fresh 2026-09-26 live semantic gate (`--live --case=paid_invoice --require-semantic`) **FAILED**: returned correct limited fields (`invoice`, remaining `0,00 EUR`, no task), but `analysis_method=rules`, `degraded_reason=provider_capacity`, ~8.7 seconds. This failure is intentionally reported rather than counting fallback as model success. Do not keep retrying the same unavailable provider or claim the strong-model requirement is complete.
- Final APK debug signer SHA-256: `42a19d66dd4d04b01a185ffb3fd5826a214477dfdf94968b8526966782ac81c3`; it still differs from the recovered original installation. No APK was distributed as a compatible upgrade.
- A small subsequent backend-only patch detects impossible dates even if the model omits them, and warns on distinct standalone headings in multi-document files without treating a generic heading prefix or contract reference as a second paper. Local tests, final CI and deployed v6 cover this patch. The final source commit includes it.

## Remaining work — do not overclaim

- The requested strong, reliable semantic understanding is still blocked by free-provider capacity. We have prepared and tested the extraction/verification pipeline; real-model accuracy on varied papers is unverified. A stable provider requires an explicit user choice/budget plus actual user authentication and a server spending limit before paid traffic. The public app key is not user identity or sufficient billing protection.
- Quotes support provenance, not a formal proof of semantic truth. Handwriting, tables, layout, multilingual OCR and novel document wording can still be misread. Current cloud input is OCR text, not original page images. Date-role/amount-role guards and emergency headings are deliberately conservative and can miss valid information.
- Local-only documents keep their existing local processing and consent boundary; no new local semantic classifier was claimed. No automatic movement into new spaces was added.
- Existing APK signing mismatch remains unresolved; no compatible update APK was delivered or installed. Original app data must be preserved. Physical Samsung/real-document validation remains outstanding.

---

# Current continuation — 2026-09-25

This section supersedes the older status below. Active branch remains `alpha/recovery-reliability-2026-09-16`.

## Implemented in the OCR/conversation phase

- Local Arabic/German/English OCR via Tesseract4Android 4.9.0 and pinned tessdata_fast packs. Packs are downloaded and SHA-256-verified at build time, bundled in the APK, then verified/extracted into private no-backup storage. Phone OCR makes no network requests. ML Kit remains the Latin fallback. Arabic reading combines automatic and uniform-block page segmentation to recover isolated form fields; detected missing numeric rows can also be read as individual lines. If the surrounding label remains unreadable, raw-line recovery retains only numeric values independently agreed with ML Kit, preserves number signs/separators, and records an explicit missing-label diagnostic; uncertain surrounding letters are not merged. The per-image work budget is 30 seconds.
- Shared reader for scanner pages, image attachments (including EXIF orientation) and PDFs. PDF text layers are preserved; image-only and mixed pages are rendered for OCR. Native rendering has a PDFBox fallback. Read limits: 2,400-pixel longest side; 24,000 extracted characters; 120 PDF pages, up to 20 OCR pages, 90-second document budget plus one bounded page in flight. PDFBox uses a 16 MiB memory cache with private disk overflow.
- SQLite v14 stores `extraction_note` separately from actual OCR text. Partial/unreadable/low-confidence results are visible on file cards, included in AI context and preserved in exports/imports. Existing files gain a local re-read menu item; blank re-reads preserve previous text. OCR is approximate, especially handwriting, faint text, complex layouts and low-quality photos.
- Extracted `AgentActionExecutor` and `AgentActionPolicy`. Only explicit user commands can mutate data; confirmation flags are honored. File operations use captured IDs and last-file lookup before the request. Added actual local creation of spaces, file rename/move, search and document clarification, including local-only spaces. No default/sample spaces or UI redesign.
- Model-generated actions are disabled; deterministic commands run separately. Remote conversation preserves user/assistant roles and latest corrections, treats documents as data, and reports the actual returned model. The local prompt has the same boundaries.
- Recovered and versioned the assistant Edge Function under `supabase/functions/masahati-agent-dev`. Production deployed as version 28 on 2026-09-18. Default provider and existing quota/auth semantics are retained. Optional server-only provider configuration is prepared but no new provider/key/account/billing is enabled.

## Validated outcome

- Android source commit **`06a3cfa4f26df7170b7ab87a7b448f17b4cb4327`**, [CI run `35346290710`](https://github.com/Hero9994/Smarters-pro/actions/runs/35346290710): **all gates PASSED**. The run completed on 2026-09-18; final job logs were reviewed on 2026-09-25. Build, lint, unit tests and APK identity/signature checks passed. All **41/41 instrumentation tests passed on API 26**, and all **41/41 passed on API 36**, with zero failed or skipped tests. Backend: **9 pure tests plus 4 live deterministic checks passed**.
- Successful job IDs: verify `105603531817`; API 26 `105603531828`; API 36 `105603531564`; backend `105605183645`. Later documentation-only commits do not change this validated Android source.
- Coverage includes actual Arabic pixels, rotated JPEG import, image-only and mixed PDFs, Activity import/search with cloud consent disabled, partial-reading limits, preservation of existing text after a failed re-read, local commands/captured document targets, and existing composer/migration/reminder regressions. Exact Arabic-word, reference-number and date assertions were retained. Isolated numeric recovery must additionally show the missing-label warning.
- The mixed-page fixture can still have an unreadable Arabic label. Its number is recovered only when two local recognizers agree, and the UI/context receives a warning about missing surrounding words. Passing tests do not mean every glyph was read, OCR is lossless, or the app is bug-free.
- Supabase project checked on 2026-09-25: **ACTIVE_HEALTHY**. This is a project-health observation, not a fresh assessment of model reasoning or provider availability.
- Live v28 probes on 2026-09-18: first run passed correction, pronoun resolution, German invoice amount, unknown contract owner and negated actions (5/6). Arithmetic first returned an unavailability response, then correctly answered 7 on one retry (~21 seconds). Earlier direct provider probes explicitly returned `FREE_MODEL_FAILED` (free capacity exhausted). Do not claim uninterrupted reliability or that switching to the old quality strategy solves it.
- Verified the OCR dependency's four arm64 native libraries have 16 KiB LOAD alignment. Physical Samsung testing and real-paper scanner/OCR validation remain outstanding.

## Remaining release and service work

- Old installation signing is unresolved. Original signer is recorded below. The existing GitHub token cannot manage Actions secrets (public-key read returned HTTP 403); no signing key was created or exposed. Do not instruct uninstalling or distribute a differently signed APK as an in-place update.
- A dependable AI provider remains necessary. Prepared server settings: `MASAHATI_CHAT_URL`, `MASAHATI_CHAT_MODEL`, `MASAHATI_CHAT_API_KEY` together; partial configuration fails closed. No new provider/account/billing was enabled. Before paid use, add real user authentication and a spending limit; the current public API key and IP quota are not user authentication.
- Derived text left after moving/deleting a local-only document still needs provenance tracking. Do not claim complete information-flow isolation.
- Continue physical-device/scanner validation, stable signing/version progression and APK size reduction. Keep the UI and user-created spaces intact.

## OCR diagnostic history (resolved gates)

- `35317612140` / `ac6457d9d606939cf2428bc5ee16ced027c6e894`: API 26 could not OCR rendered PDF pages; API 36 omitted a short numeric row. A PDFBox rendering fallback and broader EXIF/import/re-read tests followed.
- `35319118380` / `8a98fe366ba1c1d0491df0e3b508af16edacbfb2` and `35342981604` / `45cde25182915eb05b076cdb9f86a358335ec488`: API 26 passed 41/41; API 36 failed four numeric-row assertions. Native diagnostics showed AUTO and SPARSE omit the row, while SINGLE_BLOCK reads the bitmap fixture fully with confidence 92.
- `35344254693` / `b67d098915b056640e6c6d775654df8247824098` and `35345190432` / `61fa3bcf961ef240a0d4f1103d9ddb53f292594b`: API 36 improved to 40/41. The sole remaining mixed-page case had ML Kit reading `7319`, block/line modes returning empty, and raw-line mode reading `7319` at confidence 57 with unreadable surrounding letters. This led to agreed-number recovery plus an explicit missing-context warning, validated by the final successful run. Diagnostics log synthetic fixtures only.

---

# Historical continuation — 2026-09-17

This dated section is retained for provenance. Its outstanding OCR/command/model findings are superseded by the 2026-09-25 section above.

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
