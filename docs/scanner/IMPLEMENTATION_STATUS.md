# Scanner checkpoint — 2026-10-01

Signed development preview: **alpha-0.4-preview**, versionCode 11, source
`a146ef5deee1886676d7d784aa68961d93ae6217`.
The complete [CI run](https://github.com/Hero9994/Smarters-pro/actions/runs/36881972854)
passed build/lint/JVM tests, Android APIs 26 and 36, and backend regression.
Android 16: 60 passed tests. Android 8: 59 passed, with the real-frame diagnostic
intentionally skipped there and executed on API 36.

Read the [detailed Arabic evidence report](SCANNER_EVIDENCE_2026-10-01.md),
[all 300 measurements](benchmarks/a146ef5/records.json),
[public visual cases](benchmarks/a146ef5/visual/README.md),
and [signed APK receipt](delivery-0.4.json).
This checkpoint is a usable preview, **not full scanner acceptance**.

Backend CI now separates 46 unit tests, seven conservative-reading cases and
four offline provider fixtures from a strict semantic live workflow. The first
diagnosed live run returned wrong/missing contract dates despite HTTP 200 and
semantic analysis. That failure is preserved, not retried into a pass; see the
[backend diagnostics](../backend/REGRESSION.md). The document backend source
was not changed or redeployed by this test-design fix.

## Implemented and checked

- CameraX/Camera2 live document/focus/stability checks and optional stable auto capture.
- Apache-licensed DocQuad/Paddle inference assets, single ONNX Runtime, conditional MIT UVDoc geometry grid.
- Native source-region Sobel/subpixel peaks; one vote per normal profile in RANSAC/TLS.
- Coherent four-edge fit, explicit 6-source-pixel outward margin, broad-gradient localization check and manual zoom/corners.
- Native tiled homography, bounded 50 MP source handling, conservative deskew.
- Conditional dewarp with valid Jacobian, OCR/QR and measured-curvature guards.
- Tiled LAB illumination/content masks/whitening; six explicit modes and before/after comparison.
- Source-domain boundary OCR and barcode checks, plus post-filter text/code/detail guards.
- Durable immutable originals, recipes, safe restore, reopened scans and prior-PDF aliases.
- Scanner-only streaming lossless PDF 1.4; native-renderer/code validation before publication.
- Android 8 malformed-PDF preflight prevents PDFium's prior-error state poisoning a later valid document.

## Actual geometry results

SmartDoc 2015 challenge 1 v2.0.0: 300 real frames, 150 sequences, 30 independent
documents. Training overlap UNKNOWN, so these are not unseen-data accuracy claims.
Detection 300/300; **148 manual-review suggestions**, 81 suggestions with inward
error >2 px, **0 such inward errors accepted as confident** on this dataset.
Raw boundary edge mean 2.67 px, p95 6.60 px; corner mean 5.07 px.
Final padded-crop edge mean 5.98 px and corner mean 9.51 px, reported separately.
All images still pass through the crop editor before the user applies the crop.
The earlier `4f25e62` failure is retained with its measurements and pictures.

Synthetic 50 MP test on API 36: 3741x1870 output, 4782 ms pipeline,
5322 ms entire fixture, sampled PSS 389,063,680 bytes. Emulator diagnostics only;
not phone speed or a guarantee against OOM on every source/device.
Signed APK: 448,031,288 bytes. Same package and signing certificate as Preview 0.3.
ZIP CRC, SHA-256, signature v2/v3 and 16 KB alignment of all 28 native libraries pass.

## Acceptance work still open

- Improve uncertain boundaries on clutter/low contrast; manual fallback is not accurate automatic detection.
- Independent real receipts/cards/white-on-white/yellow/strong-shadow/stamp/curved-book test cases.
- Establish real curved-page quality and text/code preservation; the user's private folded letter remains private and is not an acceptance success.
- Physical medium Android/Samsung camera, focus/rotation, thermal, timing and memory profiling.
- Adaptive multi-exposure alignment/fusion and ghosting rejection; **ZSL is not HDR fusion**.
- FSENet is excluded because external weights lack an explicit grant. GCDRNet weights were not retrievable or benchmarked.
- OCR is partial above its line limit and explicitly warned; it does not prove preservation of every glyph/signature.
- Heavy stain/ink removal remains conservative so small real characters are not erased.

No Non-Commercial/Research-Only inference asset or generative text restoration is included.
Source URLs, pinned commits, asset hashes, rights and packaged licenses are in
[THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).
The local execution outage interrupted development; recovery and all results are
now saved in the repository. No merge into the main branch is required for this preview.
