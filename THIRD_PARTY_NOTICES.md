# Scanner third-party notices

No training datasets are bundled. Geometry models predict coordinates/grids; no generative text rendering is used. Original images remain immutable.

| Component | Pinned source | Code license | Weight license / attribution |
| --- | --- | --- | --- |
| DocQuadNet-256 | [MakeACopy](https://github.com/egdels/makeacopy/tree/01bebd394b9dd6f3a692f28aea7c0638085eb4da), commit `01bebd394b9dd6f3a692f28aea7c0638085eb4da` | Apache-2.0 | Exported inference weights explicitly Apache-2.0 in upstream README and NOTICE. Copyright Stefan Schliweb and contributors. Full LICENSE/NOTICE packaged in `scanner/licenses/`. |
| PP-OCRv5 mobile det + Latin/Arabic recognition | Same MakeACopy conversion commit; original [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) | Apache-2.0 | Explicit weight licensing and conversion provenance in packaged `PaddleOCR-PROVENANCE.txt`. Copyright PaddlePaddle authors. No training data. |
| ONNX Runtime Android | [Microsoft](https://github.com/microsoft/onnxruntime/tree/v1.24.1), Maven `1.24.1` | MIT | Runtime only; packaged MIT notice. |
| OpenCV Android | [OpenCV](https://github.com/opencv/opencv/tree/4.12.0), Maven `4.12.0` (existing) | Apache-2.0 and distribution third-party notices | No weights added by this dependency; packaged Apache license. |
| CameraX | [AndroidX](https://developer.android.com/jetpack/androidx/releases/camera), stable `1.6.2` | Apache-2.0 | Camera2/lifecycle/view/extensions, no bundled inference weights. Extensions use the device manufacturer's camera implementation only when available. Copyright Android Open Source Project. |

Model paths, source commit and SHA-256 are pinned in `prepareScannerModels` in `app/build.gradle.kts`. Downloads are verified, and runtime extraction verifies them again. The scanner adapter and native-resolution refinement are implemented for Masahati; no upstream app is copied wholesale.

## Provenance issue requiring review before a commercial launch

MakeACopy explicitly grants Apache-2.0 for its independently exported inference model. Its NOTICE lists UVDoc, SmartDoc, CORD and DTD training provenance. The DTD website describes research use, and upstream records its dataset license as unknown. We rely on the publisher's explicit model grant, distribute no training datasets, and do not claim to have established commercial rights to DTD itself. No new training with restricted/unknown datasets is authorized by this integration.

## Candidates — evaluation does not imply distribution permission

| Candidate | Source commit | Review status |
| --- | --- | --- |
| UVDoc | `4c9b82b537057aff2526e6dd118a847cdd072e82` | Included conditionally as geometry-only ONNX. Source/checkpoint/export hashes and numerical parity checked; Android effectiveness still under test. |
| FSENet / DocShadow-SD7K | `b9395ed333c916051d7ae58be212ac62119b61f5` | Archived MIT code. External release weights lack an explicit separate grant in the release description; do not infer permission from the conversion repository. |
| DocShadow ONNX conversion | `ec926bf36b4ac0778f836a2d34e27021447df27c` | MIT conversion code; no independent upstream weight grant. Published performance is NVIDIA-specific. |
| GCDRNet | `415c97a9a64d0a796dbbad43485ce2ab102f2a7d` | MIT. Author response in [issue 8](https://github.com/ZZZHANG-jx/GCDRNet/issues/8) addresses redistribution of converted pretrained weights. Benchmark/content preservation still required. |

Non-Commercial, Research-Only and unknown-license inference assets must not be distributed. License review and quality acceptance are independent gates.


## UVDoc integration
Source https://github.com/tanguymagne/UVDoc, commit 4c9b82b537057aff2526e6dd118a847cdd072e82.
Root MIT license covers the checkpoint tracked within the same repository (no separate restrictive override found).
Copyright Tanguy Magne 2023; full original MIT text packaged in scanner/licenses/UVDoc-MIT.txt.
Only the 2D sampling grid branch is converted; no texture reconstruction or generative image model is used.
Input [1,3,712,488], output [1,2,45,31], opset 17, align_corners=true.
Checkpoint SHA256 7e90861b8a516eb4bc51f84bd889cb77275743d2d1d3ca8091951ec9f2b7da23.
Geometry ONNX SHA256 7376bae030f4c5bd75c456fac44cd99e1d36d8b2fdf0d10f7cb4a626a2417cb4.
ONNX 31,602,475 bytes. Original source/checkpoint/export hashes and numerical grid parity were reviewed.
APK builds fetch the fixed [reviewed geometry asset](https://github.com/Hero9994/Smarters-pro/releases/tag/scanner-geometry-assets-v1)
and verify its SHA-256 plus its conversion receipt; they do not rerun host-dependent ONNX constant folding.
The original conversion recipe remains in tools/scanner/export_uvdoc.py for a future explicit model audit.
Android activation is conditional on curvature, available memory, a valid positive-Jacobian grid,
OCR/QR preservation and a measurable reduction in curvature. Adoption is not a claim of acceptance.
