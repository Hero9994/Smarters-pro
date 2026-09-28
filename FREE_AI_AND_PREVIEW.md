# Free AI and a separate Android preview

The user chose free development on 2026-09-26. Do not create billing, top up credit,
enable a paid provider, or change this decision automatically when tests pass.

## Gemini preparation

Both Edge Functions use `_shared/chat-provider.ts`. Defaults preserve the existing
public free route. Gemini is NOT active until a credential from a **Free Tier Google
project with no linked billing** is installed in Supabase Edge Function secrets:

```
MASAHATI_GEMINI_API_KEY=<entered privately in server secrets>
MASAHATI_GEMINI_FREE_TIER_CONFIRMED=true
MASAHATI_GEMINI_MODEL=gemini-3.1-flash-lite
```

The confirmation flag records an operator's actual check of the Google project's
tier; it is not a billing API check and cannot stop charges if somebody later links
billing to that Google project. Never set it merely to bypass configuration errors.
Keep Google billing disabled. Free quotas vary; quota exhaustion returns limited
analysis or an unavailable reply. There is one bounded request and no automatic
switch to a different or paid service. No search, image-generation or paid tools.

Only reviewed text models are accepted. Gemini receives low reasoning effort and
room for reasoning plus complete JSON, which still goes through the same evidence,
calendar, action and truncation checks. Model quality remains unverified until live
synthetic evaluation succeeds. No personal paper is needed for this test:

```
node --test supabase/functions/_shared/*.test.ts supabase/functions/masahati-agent-dev/*.test.ts supabase/functions/masahati-document-alpha-v2/*.test.ts
node scripts/evaluate-documents.mjs --live --require-semantic
node scripts/evaluate-assistant.mjs --live-agent
```

Do not mix Gemini settings with `MASAHATI_CHAT_URL/MODEL/API_KEY`. Any partial or
ambiguous configuration fails closed. Custom providers additionally require
`MASAHATI_CUSTOM_PROVIDER_ENABLED=true`, which is currently NOT enabled. A paid
launch still needs the user's budget approval, real user authentication and enforced
server spending limits. The public app key is not user authentication.

Official documentation reviewed 2026-09-26:
- https://ai.google.dev/gemini-api/docs/openai
- https://ai.google.dev/gemini-api/docs/pricing
- https://supabase.com/docs/guides/functions/secrets

### Completing the free connection (reviewed 2026-09-28)

The September 27 and September 28 browser attempts failed at Google sign-in with HTTP 502. Preparing
provider code is not activation; no Gemini API key has been installed by this work.
Gmail access also does not grant Gemini API credentials. The account owner can finish
without sharing a password or API key in the conversation:

1. Open https://aistudio.google.com/api-keys and sign in. Create a Gemini API key in
   a project whose plan is **Free Tier**, with **no linked Cloud Billing account**.
   Do not select Set up billing, Upgrade, prepay or a paid project.
2. Open https://supabase.com/dashboard/project/hxrvlvqlkfylbjicdfzs/functions/secrets
   with the project owner's account. Add both `MASAHATI_GEMINI_API_KEY` (the key)
   and `MASAHATI_GEMINI_FREE_TIER_CONFIRMED` (`true`) together, then Save. Set the
   confirmation only after actually checking the Google project's free status.
   Leave unrelated secrets alone; do not add the custom-provider settings.
3. Keep the key server-side. The default model is `gemini-3.1-flash-lite`; its
   current free quota is limited. After the save, run the existing live synthetic
   evaluations and require semantic results before claiming Gemini works.

The deployed provider reads environment secrets; this switch does not require a
new APK. Account setup and live Gemini quality remain incomplete until those steps
have actually succeeded. Official key/billing references:
- https://ai.google.dev/gemini-api/docs/api-key
- https://ai.google.dev/gemini-api/docs/billing

## Separate preview and data preservation

- Package `app.masahati.mobile.preview`, launcher label **مساحاتي تجريبي**.
- Version code 9 / `alpha-0.2-preview`; release-style, not debuggable.
- User build targets **arm64-v8a and x86_64** (64-bit phones and ChromeOS).
  Emulator test copies include all dependency-supported architectures.
- It installs alongside the recovered original `app.masahati.mobile.v07`.
  It is NOT an in-place update and cannot read that app's private database.
- Do not uninstall the original. Export a backup from the original, then import a
  **copy** into the preview if the user wants their existing spaces/files there.
  Imports retain originals and require fresh cloud consent.
- Do not use both copies for the same active reminders without reviewing them;
  each app schedules its own reminders.
- CI tests the preview on API 26 and 36 while a separate original-package copy and
  a private sentinel file remain installed. Existing backup/restore regression
  tests also run on the preview. Physical-phone testing remains necessary.

## Signing and delivery

CI creates the `masahati-preview-unsigned` artifact with source provenance,
checksums and the official Android SDK signing tool. Its unsigned APK is not a user
download. After **all CI jobs pass**, download the bundle and verify its GitHub
artifact digest. Run `scripts/sign-preview.py` with the exact source commit and
persistent preview key. The script checks package, checksums and expected signer.

The first delivery on September 27 was **truncated**, despite a successful check
before delivery. Its 323,121,060 bytes matched only the prefix of the complete
327,716,531-byte APK; its ZIP directory and APK signing block were missing. The
exact truncation stage is not established. Do not treat a successful upload or a
pre-upload signature check as proof of a usable download.

`sign-preview.py` now signs to a temporary path, verifies and flushes it, then
publishes the complete APK atomically. The receipt records exact byte length and
SHA-256. After storing and downloading the actual deliverable, run:

```
python scripts/verify-preview-download.py DOWNLOADED_APK signing/preview-0.2-verification.json APKSIGNER_JAR
```

The trusted receipt must come from the signing step or this reviewed repository,
not an untrusted download. The check rejects truncation and byte changes, validates
ZIP entry CRCs, verifies all native libraries' 16 KiB alignment and rechecks the
persistent APK signature. Delivery is not complete until this round trip passes.
The September 28 corrected Library version 1 passed this gate; the first version
must not be reused. Physical Samsung installation remains to be confirmed.

The public certificate fingerprint is in `signing/preview-certificate.sha256`.
The private key and password are stored separately in the existing Supabase Vault
secret `masahati_preview_signing_v1`. Never log them, commit them, put them in an APK,
or provide a public signing endpoint. Future previews must reuse this key and
increment versionCode. Do not regenerate it if the local workspace is lost.
GitHub Actions secret management is currently unavailable to the connected token;
do not weaken that permission boundary to automate signing.

No matching private key for the original installation was recovered. This preview
does not resolve original-package signing or constitute a production release.
