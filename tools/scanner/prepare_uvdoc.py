"""Fetch the reviewed MIT geometry-only asset; never retrace it during an APK build.

Host-dependent ONNX constant folding can produce different binary hashes even
when numerical parity passes. The exported, reviewed asset is immutable by hash.
The original export recipe remains available for an explicit future model audit.
"""
import argparse, hashlib, json, urllib.request
from pathlib import Path

REVISION = "4c9b82b537057aff2526e6dd118a847cdd072e82"
EXPECTED = "7376bae030f4c5bd75c456fac44cd99e1d36d8b2fdf0d10f7cb4a626a2417cb4"
RECEIPT_SHA = "7bae518460e6bd2c60e5dd947bfd42d612aabf14152f5d05af0dc71e42833ac8"
CHECKPOINT_SHA = "7e90861b8a516eb4bc51f84bd889cb77275743d2d1d3ca8091951ec9f2b7da23"
BASE = "https://github.com/Hero9994/Smarters-pro/releases/download/scanner-geometry-assets-v1/"

def sha(path):
    with path.open("rb") as stream: return hashlib.file_digest(stream, "sha256").hexdigest()

def verified_asset(name, target, expected, max_bytes):
    if target.is_file() and sha(target) == expected: return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".download")
    try:
        request = urllib.request.Request(BASE + name, headers={"User-Agent": "Masahati-scanner-build"})
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as output:
            count = 0
            while chunk := response.read(1024 * 1024):
                count += len(chunk)
                if count > max_bytes: raise RuntimeError("Geometry asset exceeds reviewed size")
                output.write(chunk)
        if sha(temporary) != expected: raise RuntimeError("Reviewed geometry asset checksum mismatch: " + name)
        temporary.replace(target)
    finally: temporary.unlink(missing_ok=True)

p = argparse.ArgumentParser()
p.add_argument("--output", type=Path, required=True)
p.add_argument("--cache", type=Path, required=True)
args = p.parse_args()
verified_asset("uvdoc-grid.onnx", args.output, EXPECTED, 31_602_475)
receipt_path = args.output.with_suffix(".json")
verified_asset("uvdoc-grid.json", receipt_path, RECEIPT_SHA, 16_384)
receipt = json.loads(receipt_path.read_text())
assert receipt["commit"] == REVISION and receipt["checkpoint_sha256"] == CHECKPOINT_SHA
assert receipt["model_sha256"] == EXPECTED and receipt["model_bytes"] == args.output.stat().st_size
assert receipt["input"] == [1, 3, 712, 488] and receipt["output"] == [1, 2, 45, 31]
assert 0 <= receipt["max_grid_difference"] < 0.0005
print("Reviewed geometry-only UVDoc asset and conversion provenance verified")
