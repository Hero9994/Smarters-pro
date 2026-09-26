"""Sign a verified CI preview without placing any credential in argv or source control.

Usage: python3 scripts/sign-preview.py BUNDLE_DIR EXPECTED_COMMIT KEYSTORE PASSWORD_FILE OUTPUT_APK
Run only after the complete CI run for EXPECTED_COMMIT has passed.
"""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

bundle, commit, keystore, password, output = sys.argv[1:]
bundle, output = Path(bundle), Path(output)
if output.exists():
    raise SystemExit("Refusing to overwrite an existing signed APK")
manifest = json.loads((bundle / "provenance.json").read_text())
if not re.fullmatch(r"[a-f0-9]{40}", commit) or manifest["commit"] != commit:
    raise SystemExit("Unexpected CI source commit")
for name in ["app-preview-unsigned.apk", "output-metadata.json", "apksigner.jar"]:
    with (bundle / name).open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != manifest["sha256"][name]:
        raise SystemExit("CI checksum mismatch: " + name)
metadata = json.loads((bundle / "output-metadata.json").read_text())
if metadata["applicationId"] != "app.masahati.mobile.preview":
    raise SystemExit("Refusing to sign the original app's package as a preview")
expected = (Path(__file__).resolve().parent.parent / "signing/preview-certificate.sha256").read_text().strip()
command = ["java", "-jar", str(bundle / "apksigner.jar")]
cert = subprocess.run(["keytool", "-exportcert", "-alias", "masahati-preview", "-keystore", keystore,
                       "-storepass:file", password], check=True, capture_output=True).stdout
if hashlib.sha256(cert).hexdigest() != expected:
    raise SystemExit("Wrong preview signing key")
subprocess.run(command + ["sign", "--ks", keystore, "--ks-key-alias", "masahati-preview", "--ks-pass", "file:" + password,
                         "--v4-signing-enabled", "false", "--out", str(output), str(bundle / "app-preview-unsigned.apk")], check=True)
verification = subprocess.run(command + ["verify", "--verbose", "--print-certs", str(output)], check=True, capture_output=True, text=True)
if "Signer #1 certificate SHA-256 digest: " + expected not in verification.stdout:
    output.unlink()
    raise SystemExit("Signed APK certificate mismatch")
print(verification.stdout)
with output.open("rb") as stream:
    digest = hashlib.file_digest(stream, "sha256").hexdigest()
receipt = {"commit": commit, "ci_run": manifest["run_id"], "application_id": metadata["applicationId"],
           "version": metadata["elements"][0]["versionName"], "signer_sha256": expected, "apk_sha256": digest}
output.with_suffix(".verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
print(json.dumps(receipt, indent=2))
