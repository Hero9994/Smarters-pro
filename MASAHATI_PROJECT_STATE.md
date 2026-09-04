
# Masahati — Persistent Project State

This file exists so a new ChatGPT conversation can recover the project without depending on chat-local GitHub installation state.

## Canonical repository
- Repository: Hero9994/Smarters-pro
- Default branch: main
- Current development branch: develop/v0.6-agent-scanner-polish
- Android package base: app.masahati.mobile
- Current test release: v0.6
- CI workflow: .github/workflows/android-ci.yml

## Important access rule
The ChatGPT GitHub App installation list may be empty in a new conversation. Do NOT stop the project or ask the user to reinstall GitHub merely because installations/repositories show zero.

Read access can use the GitHub repository directly because the repository is public.

For writes, the project has a persistent Supabase-backed GitHub bridge:
- Supabase project ref: hxrvlvqlkfylbjicdfzs
- Database function: private.github_bridge_request(method, path, body jsonb)
- The GitHub token is stored in Supabase Vault as github_masahati_token.
- Never print, expose, commit, or log the token.
- Bridge is restricted to /repos/Hero9994/Smarters-pro.
- Use the bridge when native GitHub connector write access is unavailable.

## Current product direction
- Preserve the current v0.6 UI; avoid redesigning it unless explicitly requested.
- No default conversations/spaces for new installs.
- Local-first SQLite storage.
- Smart document scanner with perspective correction, OCR, multi-page PDF, and shadow cleanup.
- AI assistant should understand conversational context, classify content/documents, create useful search metadata, search locally, and propose/execute safe organization actions.
- Destructive/external actions require confirmation.
- Sensitive documents must not be silently sent to external AI.

## Current AI backend
- Supabase Edge Function: masahati-agent-dev
- Current implementation has remote-AI plus local fallback.
- Do not claim the assistant is fully intelligent unless actual end-to-end tests pass.
- Known goal: improve contextual reasoning/search/action behavior beyond heuristic fallback.

## Release safety
- Keep old working APKs as rollback points.
- Do not overwrite signing keys or commit secrets.
- Run Android CI (build + unit tests + lint + APK identity/signature checks) before giving the user a new APK.
- Physical-device verification is still required after automated CI.

## New-chat bootstrap procedure
1. Read this file.
2. Inspect the current development branch and latest CI.
3. If GitHub connector reports 0 installations, continue anyway.
4. Use public GitHub reads and Supabase bridge for writes if necessary.
5. Continue from the existing branch; do not recreate the project from scratch.
