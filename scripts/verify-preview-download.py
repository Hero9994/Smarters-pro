"""Verify the actual downloaded preview against a trusted local signing receipt.

Usage: python3 scripts/verify-preview-download.py APK RECEIPT APKSIGNER_JAR
Run AFTER delivery storage/materialization, not only before uploading. Do not use
a receipt supplied by an untrusted download as the reference. This delivery gate
does not replace Android installation or instrumentation tests.
"""
import hashlib
import json
import struct
import subprocess
import sys
import zipfile
from pathlib import Path


def verify_apk(apk, receipt, signer):
    expected_cert = (Path(__file__).resolve().parent.parent / "signing/preview-certificate.sha256").read_text().strip()
    if receipt["application_id"] != "app.masahati.mobile.preview" or receipt["signer_sha256"] != expected_cert:
        raise ValueError("Unexpected preview signing receipt")
    if apk.stat().st_size != receipt["apk_size_bytes"]:
        raise ValueError("APK size mismatch: incomplete or changed download")
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != receipt["apk_sha256"]:
        raise ValueError("APK SHA-256 mismatch: changed download")
    with zipfile.ZipFile(apk) as archive, apk.open("rb") as stream:
        if archive.testzip() is not None:
            raise ValueError("APK entry CRC mismatch")
        libraries = [entry for entry in archive.infolist() if entry.filename.endswith(".so")]
        if not libraries:
            raise ValueError("Preview native libraries missing")
        for entry in libraries:
            stream.seek(entry.header_offset + 26)
            name_length, extra_length = struct.unpack("<HH", stream.read(4))
            data_offset = entry.header_offset + 30 + name_length + extra_length
            if entry.compress_type != zipfile.ZIP_STORED or data_offset % 16384:
                raise ValueError("Native library is not stored with 16 KiB alignment: " + entry.filename)
    result = subprocess.run(["java", "-jar", str(signer), "verify", "--verbose", "--print-certs", str(apk)],
                            check=True, capture_output=True, text=True)
    if "Signer #1 certificate SHA-256 digest: " + expected_cert not in result.stdout:
        raise ValueError("APK signer mismatch")
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in result.stdout:
        raise ValueError("APK v2 signature missing")
    print(json.dumps({"verified": True, "apk_size_bytes": apk.stat().st_size,
                      "apk_sha256": digest, "signer_sha256": expected_cert,
                      "native_libraries_aligned_16kb": len(libraries)}))


def main():
    source, receipt_path, signer = map(Path, sys.argv[1:])
    receipt = json.loads(receipt_path.read_text())
    if source.suffix.lower() != ".apk":
        raise ValueError("Expected an APK")
    verify_apk(source, receipt, signer)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        raise SystemExit("Preview delivery rejected: " + str(error)) from error
