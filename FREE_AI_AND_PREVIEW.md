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

## Separate preview and data preservation

- Package `app.masahati.mobile.preview`, launcher label **مساحاتي تجريبي**.
- Version code 9 / `alpha-0.2-preview`; release-style, not debuggable.
- User build targets **arm64-v8a**. Emulator test copies also include x86_64.
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

The public certificate fingerprint is in `signing/preview-certificate.sha256`.
The private key and password are stored separately in the existing Supabase Vault
secret `masahati_preview_signing_v1`. Never log them, commit them, put them in an APK,
or provide a public signing endpoint. Future previews must reuse this key and
increment versionCode. Do not regenerate it if the local workspace is lost.
GitHub Actions secret management is currently unavailable to the connected token;
do not weaken that permission boundary to automate signing.

No matching private key for the original installation was recovered. This preview
does not resolve original-package signing or constitute a production release.
