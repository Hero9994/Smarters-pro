"""Reproducible local Android test toolchain, kept outside the source tree.
Downloads from original publishers and verifies their published checksums.
"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import tarfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[3] / "toolchain"
ROOT.mkdir(exist_ok=True)

def download(url, path, algorithm, expected):
    if not path.exists():
        with urllib.request.urlopen(url, timeout=90) as source, path.open("wb") as target:
            shutil.copyfileobj(source, target)
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != expected.lower():
        path.unlink()
        raise ValueError(f"Checksum mismatch: {path.name}")
    return path

def unzip(path, destination):
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            target = (destination / entry.filename).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise ValueError("Unsafe archive path")
            archive.extract(entry, destination)
            mode = entry.external_attr >> 16
            if mode and target.exists():
                target.chmod(mode & 0o777)

def jdk():
    request=urllib.request.Request("https://api.github.com/repos/adoptium/temurin17-binaries/releases/latest",headers={"User-Agent":"Masahati-toolchain"})
    metadata=json.load(urllib.request.urlopen(request,timeout=40))
    package=next(a for a in metadata["assets"] if a["name"].startswith("OpenJDK17U-jdk_x64_linux_hotspot_") and a["name"].endswith(".tar.gz"))
    url=package["browser_download_url"]
    checksum=urllib.request.urlopen(url+".sha256.txt",timeout=40).read().decode().split()[0]
    path=download(url,ROOT/"jdk.tar.gz","sha256",checksum)
    staging=ROOT/"jdk-unpack"; staging.mkdir(exist_ok=True)
    with tarfile.open(path) as archive: archive.extractall(staging,filter="data")
    if not (ROOT/"jdk").exists(): shutil.move(next(staging.iterdir()),ROOT/"jdk")
    print("JDK verified",flush=True)

def gradle():
    url="https://services.gradle.org/distributions/gradle-9.5.0-bin.zip"
    checksum=urllib.request.urlopen(url+".sha256",timeout=40).read().decode().strip()
    path=download(url,ROOT/"gradle.zip","sha256",checksum)
    unzip(path,ROOT)
    print("Gradle verified",flush=True)

def sdk():
    base="https://dl.google.com/android/repository/"
    repository=ET.fromstring(urllib.request.urlopen(base+"repository2-3.xml",timeout=40).read())
    destination=ROOT/"android-sdk"; destination.mkdir(exist_ok=True)
    for name in ("platforms;android-36","build-tools;36.0.0","platform-tools"):
        package=next(p for p in repository.findall("remotePackage") if p.attrib["path"]==name)
        archives=package.find("archives").findall("archive")
        archive=next(a for a in archives if a.findtext("host-os") in (None,"linux"))
        complete=archive.find("complete")
        url=complete.findtext("url")
        path=download(base+url,ROOT/(name.replace(";","-")+".zip"),"sha1",complete.findtext("checksum"))
        staging=ROOT/(name.replace(";","-")+"-unpack"); staging.mkdir(exist_ok=True)
        unzip(path,staging)
        target=destination.joinpath(*name.split(";"))
        target.parent.mkdir(parents=True,exist_ok=True)
        source=next(p for p in staging.iterdir() if p.is_dir())
        if not target.exists(): shutil.move(source,target)
        print(f"SDK {name} verified",flush=True)
    licenses=destination/"licenses"; licenses.mkdir(exist_ok=True)
    for license in repository.findall("license"):
        text=license.text or ""
        (licenses/license.attrib["id"]).write_text(hashlib.sha1(text.encode()).hexdigest()+"\n")
    project=Path(__file__).resolve().parents[2]
    (project/"local.properties").write_text(f"sdk.dir={destination}\n")

if __name__=="__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        jobs=[executor.submit(task) for task in (jdk,gradle,sdk)]
        for job in jobs: job.result()
