"""Checksum-verified MIT UVDoc geometry-only Android asset.
Conversion dependencies run only on the build host, never inside Android.
"""
import argparse,hashlib,os,subprocess,sys,urllib.request
from pathlib import Path
REVISION="4c9b82b537057aff2526e6dd118a847cdd072e82"
EXPECTED="7376bae030f4c5bd75c456fac44cd99e1d36d8b2fdf0d10f7cb4a626a2417cb4"
SOURCES={"model.py":"320f460edc54cf830dfdd9788dfbc1b96a836429b64ed6078a30582be6f59e42",
"LICENSE":"cf80bdbde76b47756c7fa62b56854ae54902979016bb08a6ba2edbebf93e9b90",
"model/best_model.pkl":"7e90861b8a516eb4bc51f84bd889cb77275743d2d1d3ca8091951ec9f2b7da23"}
def sha(path):
    with path.open("rb") as stream: return hashlib.file_digest(stream,"sha256").hexdigest()
p=argparse.ArgumentParser()
p.add_argument("--output",type=Path,required=True);p.add_argument("--cache",type=Path,required=True)
args=p.parse_args()
if args.output.is_file() and sha(args.output)==EXPECTED:
    print("UVDoc asset verified");sys.exit(0)
source=args.cache/"source"
for name,expected in SOURCES.items():
    path=source/name
    if not path.is_file() or sha(path)!=expected:
        path.parent.mkdir(parents=True,exist_ok=True);temporary=path.with_suffix(path.suffix+".download")
        try:
            request=urllib.request.Request(f"https://raw.githubusercontent.com/tanguymagne/UVDoc/{REVISION}/{name}",headers={"User-Agent":"Masahati-scanner-build"})
            with urllib.request.urlopen(request,timeout=90) as response,temporary.open("wb") as output:
                while chunk:=response.read(1024*1024):output.write(chunk)
            if sha(temporary)!=expected:raise RuntimeError("UVDoc source checksum mismatch: "+name)
            temporary.replace(path)
        finally:temporary.unlink(missing_ok=True)
dependencies=Path(os.environ.get("MASAHATI_CONVERTER_DEPS",str(args.cache/"dependencies")))
if not (dependencies/"torch").is_dir():
    subprocess.run([sys.executable,"-m","pip","install","--target",str(dependencies),"--index-url","https://download.pytorch.org/whl/cpu","torch==2.10.0+cpu"],check=True)
    subprocess.run([sys.executable,"-m","pip","install","--target",str(dependencies),"onnx==1.20.1","onnxruntime==1.24.1","numpy==2.2.6"],check=True)
subprocess.run([sys.executable,str(Path(__file__).with_name("export_uvdoc.py")),"--source",str(source),"--output",str(args.output)],env=dict(os.environ,PYTHONPATH=str(dependencies)),check=True)
if sha(args.output)!=EXPECTED:
    args.output.unlink(missing_ok=True);raise RuntimeError("UVDoc conversion differs from reviewed geometry-only asset")
