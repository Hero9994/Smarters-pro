# Scanner 0.6 — development and acceptance record

This changes the existing native scanner. All originals, crop editing, filter modes,
page ordering, PDF export, comparison, backup/restore and the previous scanner remain.

## Inspected baseline

Source `fdebd411bb0287bb6f343f2d3b37af4035a079a1`, published preview 0.5,
workflow `37127777968`. Native API 26/36 artifacts were recovered and their archive
SHA-256 hashes checked. Baseline native geometry records and memory samples are
in `benchmarks/fdebd41/`. Local source/toolchain were restored; no application
data or signing key was replaced.

Existing: CameraX/Camera2 AF and 1x capture, 8-frame stability gate, DocQuadNet mask
and corner heatmaps, native-resolution corridor Sobel/RANSAC/TLS refinement,
homography and composed deskew, manual corners, geometry-only conditional UVDoc,
tiled LAB illumination/paper masking/whitening and Sauvola B&W, PP-OCRv5 Latin/Arabic,
ZXing and source-detail guard, reversible files and OEM HDR registration/validation.

## Changes

- OCR guard now assigns reference lines one-to-one. A remaining copy of a number
  cannot hide a changed/removed second copy, or a number exchanged between fields.
  A large confidence collapse also triggers weaker processing; incomplete OCR
  produces a visible review warning instead of a claim that all text was validated.
- UVDoc checks Jacobians at all four corners of every bilinear cell, rather than
  only one corner. Before remapping, its source mesh must cover original local
  ink/colored content, including edge signatures not represented by recognized text.
  The coverage check uses a bounded 1400-side preview; it is not a proof for every
  individual native-resolution dot. Unsafe grids retain the ordinary perspective scan.
- Adaptive best-frame capture adds at most one real full-resolution JPEG when ZSL
  is supported, preview corners/AF/quality are stable and fresh, first capture took
  at most 450 ms, and sharpness is marginal. Its capture timeout is 1300 ms. This
  path and OEM HDR are mutually exclusive during capture. Good/slow/unsupported
  captures keep the single JPEG path.
- Both original JPEGs remain immutable. The second JPEG is registered from its
  native source and adopted only for at least 18% improved measured sharpness,
  preserved exposure, successful structural/OCR/QR/color validation and enough
  readable reference content. A frame that is not better skips expensive OCR checks.
- The additional capture is hash-verified through restart and backup/restore.
  Old v1 manifests remain readable; extra filenames and ZIP paths are strictly checked.
- Native regression adds printed Code 128, edge-signature mesh coverage, a real
  UVDoc invocation on mathematically curved captured pixels, adaptive frame adoption,
  capture immutability and unsafe manifest-path rejection.
- The native-resolution illumination guide now protects faint ink only three
  LAB lightness levels below its local paper, even without a hard shadow. A
  194-gray punctuation dot on 202-gray paper previously rounded close to white
  in the whitening blend. A dedicated color-mode regression requires retained
  dot contrast and a cleaned background. Closing/Sauvola tile halos include their
  complete dependencies (8/24 rows). This preserves faint marks conservatively;
  ambiguous printer specks are not automatically erased.
- A separate native processing diagnostic covers all 300 real SmartDoc frames with
  AUTO/CLEAN_WHITE, QR/barcodes, local ink/color protection and the first 12 OCR lines.
  It records reduced-strength and original fallbacks. Publisher corners are used
  ONLY as input to the isolated FILTER benchmark; predicted crop evaluation is
  separate and never uses these labels to change predictions.

## Rejected precision experiment

RGB-vector gradient fallback was compared with the production rules on the same
300 images. It improved 8 boundaries, regressed 3 and left 289 unchanged. Mean
edge error rose from 2.61206 to 2.61919 source pixels; mean corner error rose from
4.96010 to 4.96706. All changed proposals remained manual, but individual inward
errors increased. **Rejected: no production edge code was changed.** Predictions,
metrics and the decision are saved in `benchmarks/0.6-host/`.

## Model/license review — 2026-10-03

Latest official source commits still match the pinned audit: MakeACopy
`01bebd394b9dd6f3a692f28aea7c0638085eb4da`, UVDoc
`4c9b82b537057aff2526e6dd118a847cdd072e82`, FSENet
`b9395ed333c916051d7ae58be212ac62119b61f5`, GCDRNet
`415c97a9a64d0a796dbbad43485ce2ab102f2a7d`.

No new third-party library, runtime or weight is added in 0.6. The models total
66,160,843 raw bytes. Apache/MIT notices, model hashes and conversion provenance
remain in `THIRD_PARTY_NOTICES.md` and packaged `scanner/licenses/`.

FSENet remains excluded: archived MIT source alone does not resolve the required
explicit grant for external release weights. GCDRNet's author responded to a
converted-weight redistribution question by applying MIT, but its original
OneDrive weights could not be obtained anonymously (401). The questioner's
public repository contains no weights or conversion recipe/releases. An unverified
mirror was not substituted. Therefore no claim of an FSENet or GCDRNet benchmark
or integration is made. Heavy shadows use the existing deterministic classical
illumination path, not either unavailable neural model.

## Acceptance limits

SmartDoc provides 300 frames, 150 sequences and **30 independent documents**.
Training overlap is unknown; these are development diagnostics, not an independent
500-document validation. They do not cover every requested receipt, card, white
on white, glare, fold, printer stain or book scenario. Synthetic negative cases
supplement but do not count as real documents. No personal letter is published.

Physical-device CameraX HDR/ZSL behavior and middle-range device speed/RAM require
device profiling. OEM HDR is conditional; a custom exposure-bracket/MergeMertens
path is not activated without a measured need and a ghosting/latency benchmark.
CPU ORT remains the tested default; NNAPI/GPU are not forced without a device
comparison. Millimetre accuracy cannot be claimed from arbitrary uncalibrated photos.
Source originals and manual correction remain essential in uncertain crop cases.

Final source CI, per-stage timings, measured RAM, API test counts, processing fallback
counts, screenshots and signed/public-download APK evidence will be recorded after
the final regression gates, before distribution.

## Camera comparison regression correction
The first native CI runs failed the new sharper-capture test, first at an
offscreen mapped corner (the fixture was flush with the image boundary), and
then at the exact-tone filter guard: sharpening reduced a defocus halo and was
mistaken for erased ink/color. The realistic camera fixture includes all paper
and its surrounding surface; the frame-bounds guard remains strict and has an
explicit lost-edge negative regression.

Independent sharper captures now use ScanCaptureContentGuard: bounded 1400-side
local-paper ink masks, connected source components and hue-specific neighborhoods
with a two-pixel registration tolerance. Disappearing punctuation/signatures are
negative tests. OCR/QR, exposure/sharpness and symmetric ghosting gates remain
mandatory. The filter comparison guard is unchanged. This preview check is not
proof of preservation of every native-resolution pixel.
Native source under test: 8093f50e2ccaa6a95d4dca03a6c343e42db0e4d0,
CI 37155633765: all four required jobs passed. The capture integration fixture now uses the production six-source-pixel outward padding and exports before/aligned/final images. No boundary-ink exception was introduced. Signing/public download verification remain separate delivery gates.
