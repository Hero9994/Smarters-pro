"""Record CI provenance/checksums for the unsigned preview and official signing tool."""
import hashlib
import json
import os
import sys
from pathlib import Path

directory = Path(sys.argv[1])
files = ["app-preview-unsigned.apk", "output-metadata.json", "apksigner.jar"]
manifest = {
    "commit": os.environ["GITHUB_SHA"],
    "run_id": os.environ["GITHUB_RUN_ID"],
    "sha256": {name: hashlib.file_digest((directory / name).open("rb"), "sha256").hexdigest() for name in files},
}
(directory / "provenance.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(json.dumps(manifest, indent=2))
