# Scanner development checkpoint — 2026-10-01
This is unfinished development, not an acceptance or release report.

## Existing scanner
Preview 0.3 uses Google's ML Kit scanner UI. Its delivered PDF is kept verbatim to avoid a second crop.
The optional older OpenCV rectifier uses Canny/contours/approxPolyDP at <=1280px and was removed from the main export path.
Existing file import, local text classification/search, consent for cloud document analysis and old scanner remain.

## Added pipeline
CameraX 1.6.2 preview + continuous AF state + supported high-quality lens correction + 1x back camera
-> preview DocQuadNet mask/corners + sharpness/exposure/glare/shadow checks
-> stable complete corners for >=8 frames and >=900ms before optional auto shutter
-> durable original -> full-resolution segmented Sobel corridors / subpixel peaks / RANSAC / TLS
-> edge intersections + conservative margin -> manual zoom/magnifier corner review
-> tiled native-source homography and composed fine deskew
-> conditional MIT UVDoc coordinate-grid remap when curvature evidence and validation support it
-> deterministic tiled LAB illumination normalization / content masks / whitening
-> Original / Auto / Clean White / Clear Text / B&W / Photo modes
-> PP-OCRv5 number/text consistency + ZXing + local stroke/chroma preservation
-> weaker cleanup or unchanged rectified image if guard fails
-> identical-geometry before/after slider -> lossless PNG -> PDFBox temp-backed multipage PDF.

Original captures, recipes and processed pages are included in ZIP backups; restore checks names, image bounds and source hashes.
Reopening a saved PDF exposes the original and allows exporting a new PDF. Previous PDFs retain access to original sessions.

## License review
See THIRD_PARTY_NOTICES.md and packaged licenses. Source commits/checksums are pinned.
DocQuad/PP-OCR weights have explicit Apache grants in MakeACopy.
UVDoc checkpoint is tracked under the original repository's root MIT license, without a restrictive override.
FSENet code is MIT, but external weights lack a clear weight grant: NOT DISTRIBUTED.
GCDRNet code is MIT; author's weight download requires further retrieval and comparison: NOT DISTRIBUTED.
MakeACopy lists DTD with unknown dataset provenance; no dataset is bundled or newly used for training.
The explicit publisher model license does not establish rights to that dataset itself.

## Evidence so far
- Geometry/filter/guard unit tests and debug build/lint passed locally at the pre-UI checkpoint.
- Geometry engine compiled locally before scanner UI integration.
- UVDoc geometry-only ONNX: 31,602,475 bytes; PyTorch/ORT max coordinate difference 3.5763e-7.
- Desktop CPU conversion diagnostic ~185–193ms, NOT phone latency.
- CI 1095c3b: original regression suites passed on Android APIs 26 and 36; preview dex build failed with Java heap OOM.
- Build heap raised to 4GB with two workers; new full regression is required.
- 300 real SmartDoc video frames selected from 150 sequences, 30 independent documents; 15,274,894 image bytes.
  SmartDoc appears in training provenance; overlap is UNKNOWN. This is a diagnostic, not unseen-data accuracy.
- New Android tests cover PNG/PDF rendering, backup originals, OCR digits, QR/color/tiny marks, editor recreation.
- Numeric benchmark records corners/edges, inward crop error, manual review, sampled memory and failures/overlays.
  NO BENCHMARK RESULT OR PHYSICAL DEVICE MEASUREMENT IS CLAIMED YET.

## Still required before acceptance
- Run all new tests and fix failures, then repeat old regressions.
- Examine 300-frame error distributions and bad-case images; tune on separate sequences and retest held-out cases.
- Private German folded-letter visual evaluation; never upload personal images to GitHub.
- Add real receipt/card/white-on-white/yellow/strong-shadow/curved/stamp/small-text cases beyond SmartDoc.
- Curved-book/page benchmark and character/QR preservation for UVDoc.
- Actual 12–50MP memory stress and medium physical Android phone timing; live focus/rotation/capture validation.
- Adaptive multi-exposure alignment/fusion with ghosting rejection: not implemented; ZSL is not HDR fusion.
- GCDRNet appearance comparison when weights are retrievable; FSENet remains blocked on explicit weight licensing.
- Complete stage visual reports, model/APK size and 16KB native alignment checks.
- Version 11 alpha-0.4 remains an unsigned development candidate until complete CI, persistent signing and verified delivery.
- Do not label this scanner production-ready, millimetre-accurate or equivalent to CamScanner based on compilation.

## Recovery
The local execution service went offline during integration. Changes are checkpointed directly to the scanner GitHub branch.
Fetch/reset or carefully reconcile the remote checkpoint before continuing local development; keep private samples outside Git.


## Native-resolution and reader fixes (post-c9 diagnostic)
- Keep c9's original workflow artifact (300 real frames and overlays). Do not
  treat its logical-corner mean as geometric localization error: SmartDoc
  labels follow page printing orientation, while inference follows image
  orientation. The next benchmark aligns only a cyclic starting corner to the
  initial detection and uses the same correspondence for the refined result.
  It exports ground truth, legacy logical error, confidence, peak probabilities,
  prominence, mask agreement, edge residuals and unsafe automatic crop count.
- Subdivide output warp tiles adaptively BEFORE decoding native source ROIs.
  This fixes the 50 MP analysis warp without increasing the 4 MP source-region
  cap or allocating a full 50 MP bitmap.
- Scanner-only PDF export now streams lossless RGB Flate data into PDF 1.4,
  one page at a time, with classic cross-reference byte offsets. Validate the
  platform PdfRenderer and source-readable QR/barcodes before publishing.
  PDFBox remains available for the application's existing reader/import paths.
- New independent tests cover paper-edge refinement despite a printed rectangle
  and multi-page portrait/landscape PDF geometry. Existing OCR, original hashes,
  color, tiny punctuation, backup, UI recreation, native warp, 50 MP and both
  Android API levels must pass again. These changes are NOT acceptance evidence
  until their workflow completes.
