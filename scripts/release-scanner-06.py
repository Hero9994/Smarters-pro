"""Release the exact accepted scanner CI artifact. No credentials in source or argv."""
import base64, hashlib, json, os, shutil, subprocess, tempfile, time, urllib.error, urllib.parse, urllib.request, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
os.umask(0o077)
REPO="Hero9994/Smarters-pro"
SOURCE="3dfd719f42d78a9aae50a38f95ba230a17384c7f"
RUN=37154157389
TAG="masahati-preview-0.6"
CERT="134b86f90a4d739167f9890139fefb665979c410fa2b011e3b82e7bd1bb0cd9a"
EXCHANGE="https://hxrvlvqlkfylbjicdfzs.supabase.co/functions/v1/masahati-preview-signing-06"
API="https://api.github.com/repos/"+REPO+"/"
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None
def github(path):
    request=urllib.request.Request(API+path,headers={"Authorization":"Bearer "+os.environ["GH_TOKEN"],"Accept":"application/vnd.github+json","X-GitHub-Api-Version":"2022-11-28","User-Agent":"Masahati-preview-release"})
    with urllib.request.urlopen(request,timeout=30) as response: return json.load(response)
def digest(path):
    with path.open("rb") as stream: return hashlib.file_digest(stream,"sha256").hexdigest()
def download(url,path,headers=None,authenticated=False):
    request=urllib.request.Request(url,headers=headers or {})
    opener=urllib.request.build_opener(NoRedirect()) if authenticated else urllib.request.build_opener()
    try: response=opener.open(request,timeout=120)
    except urllib.error.HTTPError as error:
        if not authenticated or error.code not in (301,302,303,307,308): raise RuntimeError("Download rejected") from None
        location=error.headers.get("Location","");parsed=urllib.parse.urlparse(location)
        if parsed.scheme!="https" or parsed.hostname in (None,"api.github.com"): raise RuntimeError("Invalid artifact redirect")
        response=urllib.request.urlopen(urllib.request.Request(location),timeout=120)  # no Authorization
    with response,path.open("wb") as output: shutil.copyfileobj(response,output,1024*1024)
def artifact(item,folder):
    if item["expired"] or item["workflow_run"]["head_sha"]!=SOURCE: raise RuntimeError("Artifact source mismatch")
    archive=folder.with_suffix(".zip")
    download(item["archive_download_url"],archive,{"Authorization":"Bearer "+os.environ["GH_TOKEN"],"Accept":"application/vnd.github+json"},True)
    if "sha256:"+digest(archive)!=item["digest"]: raise RuntimeError("Artifact archive checksum mismatch")
    folder.mkdir()
    with zipfile.ZipFile(archive) as zipped:
        total=0
        for entry in zipped.infolist():
            name=entry.filename;destination=(folder/name).resolve()
            if "\\" in name or not destination.is_relative_to(folder.resolve()) or (entry.external_attr>>16)&0o170000==0o120000: raise RuntimeError("Unsafe artifact entry")
            total+=entry.file_size
            if total>2_000_000_000: raise RuntimeError("Oversized artifact")
        if zipped.testzip() is not None: raise RuntimeError("Artifact CRC failure")
        zipped.extractall(folder)
    return folder
def summary(folder,name):
    candidates=list(folder.rglob(name))
    if len(candidates)!=1: raise RuntimeError("Missing/duplicate diagnostic "+name)
    return json.loads(candidates[0].read_text())
def counts(folder):
    result={"tests":0,"failures":0,"errors":0,"skipped":0};found=False
    for file in folder.rglob("TEST-*.xml"):
        root=ET.parse(file).getroot()
        suites=[root] if root.tag=="testsuite" else list(root.findall("testsuite"))
        for suite in suites:
            found=True
            for key in result: result[key]+=int(suite.attrib.get(key,"0"))
    if not found or result["tests"]<70 or result["failures"] or result["errors"]: raise RuntimeError("Native report gate rejected")
    return result
run=github("actions/runs/"+str(RUN));jobs=github("actions/runs/"+str(RUN)+"/jobs?per_page=100")["jobs"]
required={"verify","backend-regression","instrumented (26)","instrumented (36)"}
if run["head_sha"]!=SOURCE or run["status"]!="completed" or run["conclusion"]!="success" or run["path"]!=".github/workflows/android-ci.yml": raise SystemExit("Complete exact-source CI must pass")
if not required.issubset({j["name"] for j in jobs if j["status"]=="completed" and j["conclusion"]=="success"}): raise SystemExit("Required CI jobs must all pass")
items=github("actions/runs/"+str(RUN)+"/artifacts?per_page=100")["artifacts"]
with tempfile.TemporaryDirectory(prefix="masahati-release-") as private:
    task_dir=Path(private);fetched={}
    for name in ["masahati-preview-unsigned","masahati-alpha-instrumented-reports-api-26","masahati-alpha-instrumented-reports-api-36"]:
        candidates=[a for a in items if a["name"]==name]
        if len(candidates)!=1: raise RuntimeError("Missing/duplicate CI artifact")
        fetched[name]=artifact(candidates[0],task_dir/name)
    bundle=fetched["masahati-preview-unsigned"]
    native26=fetched["masahati-alpha-instrumented-reports-api-26"];native36=fetched["masahati-alpha-instrumented-reports-api-36"]
    tests26=counts(native26);tests36=counts(native36)
    geometry=summary(native36,"summary.json");processing=summary(native36,"processing-summary.json")
    if geometry["real_frames"]!=300 or geometry["unsafe_auto_crops"]!=0 or processing["real_frames"]!=300: raise RuntimeError("Dataset acceptance gate rejected")
    metadata=json.loads((bundle/"output-metadata.json").read_text())
    if metadata["elements"][0]["versionCode"]!=13 or metadata["elements"][0]["versionName"]!="alpha-0.6-preview": raise RuntimeError("Unexpected APK version")
    query=os.environ["ACTIONS_ID_TOKEN_REQUEST_URL"]+"&audience=masahati-preview-signing-0.6"
    request=urllib.request.Request(query,headers={"Authorization":"Bearer "+os.environ["ACTIONS_ID_TOKEN_REQUEST_TOKEN"]})
    with urllib.request.urlopen(request,timeout=30) as response: identity=json.load(response)["value"]
    material=None
    for attempt in range(30):
        try:
            request=urllib.request.Request(EXCHANGE,data=b"{}",method="POST",headers={"Authorization":"Bearer "+identity,"Content-Type":"application/json"})
            with urllib.request.urlopen(request,timeout=40) as response: material=json.load(response)
            break
        except (urllib.error.HTTPError,urllib.error.URLError,TimeoutError):
            if attempt==29: raise RuntimeError("Protected signing exchange unavailable") from None
            time.sleep(2)
    if material["alias"]!="masahati-preview" or material["package"]!="app.masahati.mobile.preview" or material["certificate_sha256"]!=CERT: raise RuntimeError("Wrong signing identity")
    keystore=task_dir/"preview.keystore";password=task_dir/"preview-password"
    keystore.write_bytes(base64.b64decode(material["keystore_base64"],validate=True));password.write_text(material["password"])
    del material,identity
    delivery=Path("release-0.6");delivery.mkdir()
    apk=delivery/"Masahati-Preview-0.6.apk"
    try: subprocess.run(["python3","scripts/sign-preview.py",str(bundle),SOURCE,str(keystore),str(password),str(apk)],check=True)
    finally: keystore.unlink(missing_ok=True);password.unlink(missing_ok=True)
    receipt=apk.with_suffix(".verification.json");signed=json.loads(receipt.read_text())
    if signed["commit"]!=SOURCE or str(signed["ci_run"])!=str(RUN): raise RuntimeError("Signed provenance mismatch")
    evidence=delivery/"scanner-0.6-evidence.zip"
    with zipfile.ZipFile(evidence,"w",zipfile.ZIP_DEFLATED) as zipped:
        for label,folder in [("api26",native26),("api36",native36)]:
            for file in folder.rglob("*"):
                if file.is_file() and file.suffix.lower() in (".png",".json",".xml"): zipped.write(file,label+"/"+str(file.relative_to(folder)))
        zipped.writestr("PROVENANCE.json",json.dumps({"source":SOURCE,"ci_run":RUN,"artifacts":items,"public_images":"SmartDoc 2015 (CC BY 4.0) and first-party synthetic fixtures only"},indent=2))
        zipped.writestr("ATTRIBUTION.txt","Real images: Burie, Chazalon, Coustaty, Eskenazi, Luqman, Mehri, Nayef, Ogier, Un and Rusinol; ICDAR 2015 SmartDoc competition. Dataset v2.0.0, https://zenodo.org/records/1309725, CC BY 4.0: https://creativecommons.org/licenses/by/4.0/ . Original/overlay/processed derivatives identify stages by filename. Synthetic fixtures: Masahati project. No private user letter is included.")
    memory={}
    for api_level in (26,36):
        job=next(j for j in jobs if j["name"]==f"instrumented ({api_level})")
        log=task_dir/f"api{api_level}-job.log"
        download(API+"actions/jobs/"+str(job["id"])+"/logs",log,{"Authorization":"Bearer "+os.environ["GH_TOKEN"],"Accept":"application/vnd.github+json"},True)
        entries=[line.split("ScannerMemory: ",1)[1] for line in log.read_text(errors="replace").splitlines() if "ScannerMemory: " in line]
        if len(entries)!=1: raise RuntimeError("Missing/duplicate 50MP memory diagnostic")
        memory[str(api_level)]=json.loads(entries[0])
        log.unlink()
    statistics={"source":SOURCE,"ci_run":RUN,"api26":tests26,"api36":tests36,"geometry":geometry,"processing":processing,"memory":memory,"signing":signed,"apk_delta_from_05_bytes":signed["apk_size_bytes"]-448064255,"evidence_sha256":digest(evidence)}
    (delivery/"scanner-0.6-results.json").write_text(json.dumps(statistics,ensure_ascii=False,indent=2)+"\n")
    report=Path("docs/scanner/DELIVERY_0.6_AR.md").read_text()+"\n\n## نتائج الإصدار الذي اجتاز الفحص\n\nالمصدر: "+SOURCE+"؛ تشغيل CI: "+str(RUN)+".\n\n"
    report+="| الفحص | إجمالي الحالات | فشل | تخطي متعمد |\n|---|---:|---:|---:|\n"
    for label,result in [("Android 8",tests26),("Android 16",tests36)]: report+=f'| {label} | {result["tests"]} | {result["failures"]+result["errors"]} | {result["skipped"]} |\n'
    edge=geometry["boundary_edge_px"];corner=geometry["boundary_corner_px"]
    report+=f'\nالكشف {geometry["detected"]}/300؛ المراجعة اليدوية {geometry["manual_review"]}؛ حالات قص تلقائي غير آمن {geometry["unsafe_auto_crops"]}.\n'
    report+=f'\nمتوسط خطأ الحافة {edge["mean"]:.3f} بكسل مصدر؛ P95={edge["p95"]:.3f}. متوسط خطأ الزوايا {corner["mean"]:.3f}؛ P95={corner["p95"]:.3f}.\n'
    report+=f'\nالتنظيف: {processing["ocr_validated_lines"]} سطرًا و{processing["readable_codes_checked"]} رموز؛ تخفيف {processing["reduced_strength"]}/300؛ رجوع بلا فلتر {processing["original_fallback"]}/300؛ متوسط المعالجة والتحقق {processing["mean_validation_and_processing_ms"]:.2f}ms عند ≤1.4MP.\n'
    for api_level,m in memory.items(): report+=f'\nمحاكي API {api_level}، 50MP: Pipeline {m["pipeline"]["total_ms"]}ms؛ الاختبار {m["elapsed_ms"]:.1f}ms؛ عينة PSS قصوى {m["sampled_peak_pss_bytes"]:,} بايت. عينات 25ms؛ ليست Profile لهاتف حقيقي.\n'
    report+="\nقِيَم الحواف والتنظيف الكاملة في scanner-0.6-results.json. الصور المرحلية والزوايا وتقارير الاختبارات في scanner-0.6-evidence.zip.\n"
    report+=f'\nحجم APK الموقّع: {signed["apk_size_bytes"]:,} بايت؛ فرق الحجم عن 0.5: {statistics["apk_delta_from_05_bytes"]:+,} بايت. لا توجد أوزان أو Runtime إضافية.\n'
    report+="\nSHA-256 للـAPK: "+signed["apk_sha256"]+"\n"
    report_path=delivery/"Scanner-0.6-Report-Arabic.md";report_path.write_text(report)
    check=subprocess.run(["gh","release","view",TAG,"--repo",REPO],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if check.returncode==0: raise RuntimeError("Refusing to replace an existing release")
    notes=delivery/"release-notes.md"
    notes.write_text("نسخة اختبار مساحتي 0.6: حماية الحبر الباهت والأرقام المتكررة والأختام، فحص تسطيح UVDoc، والتقاط إضافي تكيفي مع حفظ الأصل. اجتازت CI على Android 8 و16 و300 صورة اختبار. التقرير والصور والأرقام مرفقة. مراجعة الزوايا ضرورية في الحالات منخفضة الثقة؛ FSENet/GCDRNet غير مدمجين للأسباب الموثقة.")
    subprocess.run(["gh","release","create",TAG,"--repo",REPO,"--target",SOURCE,"--prerelease","--draft","--title","Masahati Preview 0.6","--notes-file",str(notes)],check=True)
    subprocess.run(["gh","release","upload",TAG,str(apk),str(receipt),str(report_path),str(evidence),str(delivery/"scanner-0.6-results.json"),"--repo",REPO],check=True)
    subprocess.run(["gh","release","edit",TAG,"--draft=false","--repo",REPO],check=True)
    published=github("releases/tags/"+TAG);matches=[a for a in published["assets"] if a["name"]==apk.name]
    if len(matches)!=1 or matches[0]["size"]!=signed["apk_size_bytes"]: raise RuntimeError("Public asset mismatch")
    downloaded=task_dir/"public-download.apk";download(matches[0]["browser_download_url"],downloaded)  # no Authorization
    verification=subprocess.run(["python3","scripts/verify-preview-download.py",str(downloaded),str(receipt),str(bundle/"apksigner.jar")],check=True,capture_output=True,text=True)
    proof=json.loads(verification.stdout.strip())
    proof.update({"commit":SOURCE,"ci_run":RUN,"public_url":matches[0]["browser_download_url"],"http_status":200,"scope":"Complete public download verified against the local signing receipt"})
    (delivery/"public-download-verification.json").write_text(json.dumps(proof,indent=2)+"\n")
    subprocess.run(["gh","release","upload",TAG,str(delivery/"public-download-verification.json"),"--repo",REPO],check=True)
    print("MASAHATI_RELEASE_RESULT="+json.dumps({"release":published["html_url"],"apk_url":matches[0]["browser_download_url"],"report":next(a["browser_download_url"] for a in published["assets"] if a["name"]==report_path.name),"api26":tests26,"api36":tests36,"geometry":geometry,"processing":processing,"verified_public_download":proof},ensure_ascii=False))
