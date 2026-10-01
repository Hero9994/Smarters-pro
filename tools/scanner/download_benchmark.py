"""Fetch the original author's CC-BY-4.0 SmartDoc 2015 challenge-1 frames.
Evaluation only, never training or APK assets. The publisher's checksum is verified.
Contains real preview video frames; adjacent frames are not independent documents.
"""
import hashlib
from pathlib import Path
import shutil
import tarfile
import urllib.request

root=Path(__file__).resolve().parents[3]/"scanner-benchmark"
root.mkdir(exist_ok=True)
base="https://github.com/jchazalon/smartdoc15-ch1-dataset/releases/download/v2.0.0/"
checksums=urllib.request.urlopen(base+"sha256.chksum",timeout=40).read().decode()
(root/"sha256.chksum").write_text(checksums)
expected=next(line.split()[0] for line in checksums.splitlines() if "frames.tar.gz" in line)
target=root/"frames.tar.gz"
if not target.exists():
    with urllib.request.urlopen(base+target.name,timeout=120) as source,target.open("wb") as output:
        shutil.copyfileobj(source,output,1024*1024)
digest=hashlib.sha256()
with target.open("rb") as stream:
    for chunk in iter(lambda: stream.read(1024*1024),b""): digest.update(chunk)
assert digest.hexdigest()==expected,"Benchmark checksum mismatch"
print("SmartDoc archive verified",target.stat().st_size,flush=True)
with tarfile.open(target) as archive:
    names=archive.getnames()
    print("Entries",len(names),"first",names[:12],flush=True)
    # Source remains outside the repository; sample manifests are generated separately.
    destination=root/"smartdoc"
    destination.mkdir(exist_ok=True)
    archive.extractall(destination,filter="data")
print("Extracted",flush=True)
