"""Convert the original MIT-licensed UVDoc geometry model, never an image renderer.
Run with isolated CPU torch/onnx/onnxruntime dependencies. Checkpoints are loaded with
weights_only=True; no unrestricted pickle deserialization is permitted.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import time
import numpy as np
import onnx
import onnxruntime as ort
import torch

parser=argparse.ArgumentParser()
parser.add_argument("--source",type=Path,required=True)
parser.add_argument("--output",type=Path,required=True)
parser.add_argument("--width",type=int,default=488)
parser.add_argument("--height",type=int,default=712)
args=parser.parse_args()
torch.set_num_threads(2)
spec=importlib.util.spec_from_file_location("uvdoc_model",args.source/"model.py")
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
model=module.UVDocnet(num_filter=32,kernel_size=5)
checkpoint=torch.load(args.source/"model/best_model.pkl",map_location="cpu",weights_only=True)
model.load_state_dict(checkpoint["model_state"]);model.eval()

class Geometry(torch.nn.Module):
    def __init__(self,model): super().__init__();self.model=model
    def forward(self,image): return self.model(image)[0]

engine=Geometry(model).eval()
sample=torch.rand(1,3,args.height,args.width)
args.output.parent.mkdir(parents=True,exist_ok=True)
with torch.no_grad():
    expected=engine(sample).numpy()
    torch.onnx.export(engine,sample,str(args.output),input_names=["image"],output_names=["grid"],opset_version=17,dynamo=False)
onnx.checker.check_model(onnx.load(args.output))
options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
runner=ort.InferenceSession(str(args.output),sess_options=options,providers=["CPUExecutionProvider"])
times=[]
for _ in range(4):
    started=time.perf_counter();actual=runner.run(None,{"image":sample.numpy()})[0];times.append((time.perf_counter()-started)*1000)
error=float(np.max(np.abs(expected-actual)))
assert error<0.0005,("Conversion mismatch",error)
receipt={"source":"https://github.com/tanguymagne/UVDoc","commit":"4c9b82b537057aff2526e6dd118a847cdd072e82","license":"MIT (root license, checkpoint included in repository)",
    "checkpoint_sha256":hashlib.sha256((args.source/"model/best_model.pkl").read_bytes()).hexdigest(),
    "input":[1,3,args.height,args.width],"output":list(actual.shape),"max_grid_difference":error,
    "model_bytes":args.output.stat().st_size,"model_sha256":hashlib.sha256(args.output.read_bytes()).hexdigest(),
    "desktop_cpu_ms":times,"phone_latency_measured":False}
args.output.with_suffix(".json").write_text(json.dumps(receipt,indent=2))
print(json.dumps(receipt,indent=2))
