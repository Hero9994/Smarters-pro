"""300 real frames from 150 SmartDoc sequences, publisher corner labels.
Two distant frames per document/background, not 300 independent documents.
SmartDoc appears in DocQuad training provenance; unseen-test status is UNKNOWN.
Generated images are androidTest-only, never application assets.
"""
import argparse,csv,gzip,hashlib,json,shutil
from pathlib import Path
from collections import defaultdict
p=argparse.ArgumentParser();p.add_argument("--dataset",type=Path,required=True);p.add_argument("--output",type=Path,required=True)
args=p.parse_args();groups=defaultdict(list)
with gzip.open(args.dataset/"metadata.csv.gz","rt") as stream:
    for row in csv.DictReader(stream):groups[(row["bg_name"],row["model_name"])].append(row)
assert len(groups)==150,len(groups)
args.output.mkdir(parents=True,exist_ok=True);samples=[]
for key in sorted(groups):
    rows=sorted(groups[key],key=lambda r:int(r["frame_index"]))
    for fraction in (.25,.75):
        row=rows[round((len(rows)-1)*fraction)];source=args.dataset/row["image_path"]
        name="_".join([*key,source.name]);shutil.copyfile(source,args.output/name)
        with source.open("rb") as stream:digest=hashlib.file_digest(stream,"sha256").hexdigest()
        samples.append({"file":name,"source_path":row["image_path"],"sequence":"/".join(key),"background":row["bg_name"],"category":row["modeltype_name"],"sha256":digest,
            "corners":[[float(row[c+"_x"]),float(row[c+"_y"])] for c in ("tl","tr","br","bl")]})
manifest={"dataset":"SmartDoc 2015 challenge 1 v2.0.0","source":"https://github.com/jchazalon/smartdoc15-ch1-dataset","license":"CC-BY-4.0",
"attribution":"Burie, Chazalon, Coustaty, Eskenazi, Luqman, Mehri, Nayef, Ogier, Un and Rusinol; ICDAR 2015 SmartDoc competition",
"real_frames":len(samples),"independent_documents":30,"sequences":150,"training_overlap":"unknown; SmartDoc appears in DocQuad training provenance",
"selection":"25% and 75% frames of each sequence; fixed before benchmark","samples":samples}
(args.output/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
print(json.dumps({"real_frames":len(samples),"sequences":150,"bytes":sum(p.stat().st_size for p in args.output.glob("*.jpeg"))}))
