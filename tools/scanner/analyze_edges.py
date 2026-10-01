"""Desktop diagnostic of candidate source-edge strategies.
Uses recorded Android model polygons, real publisher images/GT. Not an Android
acceptance test; PNG/JPEG decoding and random sampling differ from Android.
No training, no user photographs, no model or OCR image reconstruction.
"""
import argparse, json, math
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import cv2
import numpy as np

cv2.setNumThreads(1)
def line(a,b):
    n=np.array([-(b-a)[1],(b-a)[0]])/np.linalg.norm(b-a)
    return np.r_[n,-n@a]
def fit(points,weights,reference,mid,radius):
    if len(points)<40:return None
    rng=np.random.default_rng(73071446);best=None;score=0
    for _ in range(96):
        a,b=points[rng.integers(0,len(points),2)]
        if np.linalg.norm(a-b)<12:continue
        l=line(a,b)
        if abs(l[:2]@reference[:2])<math.cos(math.radians(7.5)):continue
        inside=np.abs(points@l[:2]+l[2])<=2.5
        value=weights[inside].sum()
        if value>score:score=value;best=inside
    if best is None or best.sum()<max(8,len(points)*.55):return None
    pts=points[best];ww=weights[best];center=np.average(pts,axis=0,weights=ww)
    cov=((pts-center)*ww[:,None]).T@(pts-center)
    _,v=np.linalg.eigh(cov);n=v[:,0];l=np.r_[n,-n@center]
    residual=np.sqrt(np.mean((pts@n+l[2])**2))
    if best.mean()<.6 or residual>2.7 or abs(n@reference[:2])<math.cos(math.radians(7.5)) or abs(l[:2]@mid+l[2])>radius*1.15:return None
    return l,float(residual)
def intersections(lines):
    return np.array([np.linalg.solve(np.array([lines[(i+3)%4][:2],lines[i][:2]]),
        -np.array([lines[(i+3)%4][2],lines[i][2]])) for i in range(4)])
def area(p):
    return abs(sum(np.cross(p[i],p[(i+1)%4]) for i in range(4)))/2
def valid(p):
    return all(np.cross(p[(i+1)%4]-p[i],p[(i+2)%4]-p[(i+1)%4])>0 for i in range(4))
def refine(gray,initial,variant):
    h,w=gray.shape;gx=cv2.Sobel(gray,cv2.CV_32F,1,0,ksize=3);gy=cv2.Sobel(gray,cv2.CV_32F,0,1,ksize=3)
    radius=np.clip(min(w,h)*(.027 if variant=="baseline" else .045),20,180)
    lines=[];accepted=0;residuals=[]
    for e in range(4):
        a=initial[e];b=initial[(e+1)%4];n=line(a,b)[:2];pts=[];weights=[]
        positions=a+(b-a)*np.linspace(.025 if variant=="baseline" else .08,.975 if variant=="baseline" else .92,96)[:,None]
        def sample(p,d):
            xy=np.floor(p+n*d+.5).astype(int);x,y=xy
            if x<1 or x>=w-1 or y<1 or y>=h-1:return None
            return abs(gx[y,x]*n[0]+gy[y,x]*n[1]),int(gray[y,x])
        signed=[]
        for p in positions:
            before=sample(p,-18);after=sample(p,18)
            if before and after:signed.append(after[1]-before[1])
        polarity=np.median(signed) if signed else 0
        reliable=abs(polarity)>=5 and np.mean(np.sign(signed)==np.sign(polarity))>=.7
        for p in positions:
            r=round(radius);d=np.arange(-r+2,r-2)
            if variant=="safe":d=d[d<=max(8,min(w,h)*.01)]
            xy=np.floor(p[None,:]+d[:,None]*n+.5).astype(int)
            ok=(xy[:,0]>1)&(xy[:,0]<w-2)&(xy[:,1]>1)&(xy[:,1]<h-2)
            d=d[ok];xy=xy[ok]
            if len(d)<4:continue
            values=np.abs(gx[xy[:,1],xy[:,0]]*n[0]+gy[xy[:,1],xy[:,0]]*n[1])
            best=0;score=0
            for k in np.nonzero((values[1:-1]>=values[:-2])&(values[1:-1]>=values[2:])&(values[1:-1]>= (24 if variant=="baseline" else 12)))[0]+1:
                offset=float(d[k]);before=sample(p,offset-5);after=sample(p,offset+5)
                if not before or not after:continue
                step=after[1]-before[1]
                if abs(step)<(7 if variant=="baseline" else 3):continue
                candidate=math.sqrt(values[k])*math.sqrt(abs(step))*math.exp(-.5*(offset/(radius*.65))**2)
                if variant!="baseline" and reliable and step*polarity<0:candidate*=.08
                if candidate>score:score=candidate;best=k
            if score> (12 if variant=="baseline" else 7):
                left=values[max(0,best-1)];mid=values[best];right=values[min(best+1,len(values)-1)]
                denom=float(left-2*mid+right)
                delta=float(np.clip(.5*(left-right)/denom,-.75,.75)) if abs(denom)>1e-6 else 0
                pts.append(p+n*(d[best]+delta));weights.append(min(2,score/70))
        reference=line(a,b);fitted=fit(np.array(pts),np.array(weights),reference,(a+b)/2,radius)
        if fitted:lines.append(fitted[0]);accepted+=1;residuals.append(fitted[1])
        else:lines.append(reference);residuals.append(-1)
    chosen=intersections(lines)
    plausible=valid(chosen) and .75<area(chosen)/area(initial)<1.3 and max(np.linalg.norm((chosen-initial)/[w-1,h-1],axis=1))<.1
    if not plausible or (variant=="safe" and accepted<4):chosen=initial.copy()
    padding=3 if accepted==4 and plausible else 6
    center=chosen.mean(axis=0);lines=[line(chosen[e],chosen[(e+1)%4]) for e in range(4)]
    for l in lines:l[2]+=padding*(1 if l[:2]@center+l[2]>0 else -1)
    padded=np.clip(intersections(lines),[0,0],[w-1,h-1])
    return chosen,padded,accepted,residuals
def metrics(p,gt):
    corner=np.linalg.norm(p-gt,axis=1).mean()
    edge=np.mean([np.abs((gt[e]+(gt[(e+1)%4]-gt[e])*np.linspace(0,1,21)[:,None])@line(p[e],p[(e+1)%4])[:2]+line(p[e],p[(e+1)%4])[2]).mean() for e in range(4)])
    inward=max(-np.cross(p[(e+1)%4]-p[e],g-p[e])/np.linalg.norm(p[(e+1)%4]-p[e]) for g in gt for e in range(4))
    return dict(corner=float(corner),edge=float(edge),inward=float(inward))
def main():
    parser=argparse.ArgumentParser();parser.add_argument("--dataset",type=Path,required=True);parser.add_argument("--records",type=Path,required=True);parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args();records=json.loads(args.records.read_text())
    def process(record):
        seq=record["sequence"];frame="frame_"+record["file"].split("_frame_")[1]
        im=cv2.imread(str(args.dataset/seq/frame));assert im is not None
        gray=cv2.GaussianBlur(cv2.cvtColor(im,cv2.COLOR_BGR2GRAY),(3,3),.6)
        initial=np.array(record["model_polygon"]);gt=np.array(record["ground_truth_polygon"])
        out={"file":record["file"],"sequence":seq}
        for variant in ["baseline","soft","safe"]:
            before,after,count,res=refine(gray,initial,variant)
            out[variant]={**metrics(after,gt),"before_padding":metrics(before,gt),"accepted":count,"residual":res,"polygon":after.tolist()}
        return out
    with ThreadPoolExecutor(max_workers=4) as pool:
        output=[]
        for i,r in enumerate(pool.map(process,records)):
            output.append(r)
            if i%30==0:print("Processed",i,flush=True)
    args.output.write_text(json.dumps(output,indent=2))
    for variant in ["baseline","soft","safe"]:
        rows=[r[variant] for r in output]
        print(variant,{k:float(np.mean([r[k] for r in rows])) for k in ["corner","edge"]},
            "inward>2",sum(r["inward"]>2 for r in rows),"accepted4",sum(r["accepted"]==4 for r in rows),
            "bad4",sum(r["accepted"]==4 and r["inward"]>2 for r in rows),flush=True)
if __name__=="__main__":main()
