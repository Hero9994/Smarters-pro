# MASAHATI ALPHA — 20 FEATURE ROADMAP

Branch: alpha/masahati-alpha
Base commit: 82792a201a4730840eac3729b2569fa1209ab6aa

## Stable behaviors that must not regress
- Current UI/composer/keyboard behavior
- Scanner
- Reminder parsing and reminder chat-message delivery
- Long-press actions
- No fake/default conversations
- Existing search and local/cloud AI fallback

## Alpha features
1. Universal Inbox — IN PROGRESS
2. Smart Auto-Naming — IN PROGRESS
3. Document Type Detection — IN PROGRESS
4. Structured Important Data Extraction — IN PROGRESS
5. Semantic Search — FOUNDATION
6. Offline Search — FOUNDATION
7. Persistent Context Memory — EXISTING + EXPANDING
8. Answer With Evidence — IN PROGRESS
9. Confidence / Ask Instead of Guess — IN PROGRESS
10. Document Action Center — IN PROGRESS
11. Automatic Deadline Detection — IN PROGRESS
12. Expiry Tracker — IN PROGRESS
13. Reminder Inbox — EXISTING
14. Conditional Reminders — PLANNED
15. Morning Brief / Today — FOUNDATION
16. Voice Inbox — IN PROGRESS
17. Scanner Intelligence 2.0 — EXISTING + EXPANDING
18. Duplicate Detector — IN PROGRESS
19. Trash + Version History — IN PROGRESS
20. Own Your Data / Backup + Export — FOUNDATION

## Architecture decisions
- Documents remain normal chat messages; structured intelligence is stored in normalized auxiliary tables keyed by message_id.
- No document AI fact is trusted without evidence/excerpt when available.
- Deterministic parsers handle reminders and exact facts; LLM handles ambiguous understanding.
- Semantic retrieval is additive: exact/ranked search remains available as a fallback.
- Embeddings are generated locally when the model pack is installed; no user document must be uploaded merely to create an embedding.
- Destructive actions become soft-delete first.
- Alpha database migrations must preserve all v0.7 user data.
