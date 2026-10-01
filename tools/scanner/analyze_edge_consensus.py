"""Compare candidate edge strategies on recorded Android polygons and publisher GT.

Desktop diagnostics, not Android acceptance numbers. Multi-peak hypotheses count
each profile at most once, so strong printing cannot win merely by producing
many peaks. This is first-party experimental code; the nearest-peak comparison
also studies MakeACopy's pinned Apache-2.0 EdgeSnapCornerRefiner idea.
"""
import argparse
import json
import math
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import cv2
import numpy as np
from analyze_edges import line, intersections, metrics, valid, area

cv2.setNumThreads(1)

def fit_profiles(points, profiles, strengths, reference, midpoint, radius):
    if len(points)<40:return None
    rng=np.random.default_rng(73071446)
    best=None;score=-1
    for _ in range(192):
        a,b=points[rng.integers(0,len(points),2)]
        if np.linalg.norm(a-b)<40:continue
        ll=line(a,b)
        if abs(ll[:2]@reference[:2])<math.cos(math.radians(7.5)):continue
        residual=np.abs(points@ll[:2]+ll[2])
        candidates=np.nonzero(residual<=2.5)[0]
        order=np.lexsort((residual[candidates],profiles[candidates]))
        ordered=candidates[order]
        starts=np.unique(profiles[ordered],return_index=True)[1]
        choices=ordered[starts]
        if len(choices)<40:continue
        offset=abs(ll[:2]@midpoint+ll[2])
        value=len(choices)*math.exp(-.5*(offset/(radius*.65))**2)
        if value>score:score=value;best=np.array(choices)
    if best is None or len(best)<96*.65:return None
    pts=points[best];weights=np.clip(strengths[best]/70,.4,1.5)
    center=np.average(pts,axis=0,weights=weights)
    cov=((pts-center)*weights[:,None]).T@(pts-center)
    _,vectors=np.linalg.eigh(cov);n=vectors[:,0]
    ll=np.r_[n,-n@center];residual=np.sqrt(np.mean((pts@n+ll[2])**2))
    if residual>2.7 or abs(n@reference[:2])<math.cos(math.radians(7.5)):return None
    return ll,float(residual),len(best)/96

def refine(image,initial,variant):
    h,w=image.shape[:2]
    source=cv2.cvtColor(image,cv2.COLOR_BGR2LAB) if variant.startswith('color') else cv2.cvtColor(image,cv2.COLOR_BGR2GRAY)[:,:,None]
    source=cv2.GaussianBlur(source,(3,3),.6)
    if source.ndim==2:source=source[:,:,None]
    gx=cv2.Sobel(source,cv2.CV_32F,1,0,ksize=3)
    gy=cv2.Sobel(source,cv2.CV_32F,0,1,ksize=3)
    if gx.ndim==2:gx=gx[:,:,None];gy=gy[:,:,None]
    radius=float(np.clip(min(w,h)*.027,20,180));r=round(radius)
    lines=[];accepted=0;residuals=[];supports=[]
    for edge in range(4):
        a,b=initial[edge],initial[(edge+1)%4]
        ref=line(a,b);n=ref[:2]
        positions=a+(b-a)*np.linspace(.025,.975,96)[:,None]
        points=[];groups=[];strengths=[]
        for group,p in enumerate(positions):
            d=np.arange(-r+6,r-6)
            coords=np.floor(p+d[:,None]*n+.5).astype(int)
            before=np.floor(p+(d-5)[:,None]*n+.5).astype(int)
            after=np.floor(p+(d+5)[:,None]*n+.5).astype(int)
            ok=(coords[:,0]>1)&(coords[:,0]<w-2)&(coords[:,1]>1)&(coords[:,1]<h-2)&(before[:,0]>=0)&(before[:,0]<w)&(before[:,1]>=0)&(before[:,1]<h)&(after[:,0]>=0)&(after[:,0]<w)&(after[:,1]>=0)&(after[:,1]<h)
            d,coords,before,after=d[ok],coords[ok],before[ok],after[ok]
            if len(d)<4:continue
            response=np.linalg.norm(gx[coords[:,1],coords[:,0]]*n[0]+gy[coords[:,1],coords[:,0]]*n[1],axis=1)
            step=np.linalg.norm(source[after[:,1],after[:,0]].astype(float)-source[before[:,1],before[:,0]].astype(float),axis=1)
            peaks=np.nonzero((response[1:-1]>=response[:-2])&(response[1:-1]>=response[2:]))[0]+1
            minimum=16 if variant.startswith('color') else 24
            peaks=peaks[(response[peaks]>=minimum)&(step[peaks]>=4)]
            if not len(peaks):continue
            scores=np.sqrt(response[peaks]*step[peaks])*np.exp(-.5*(d[peaks]/(radius*.65))**2)
            if variant.endswith('nearest'):
                # Ignore very weak peaks relative to the strongest valid step,
                # then prefer the nearest credible transition to the model.
                credible=peaks[response[peaks]>=max(minimum,response[peaks].max()*.35)]
                selected=[credible[np.argmin(abs(d[credible]))]]
            else:
                selected=[]
                for k in peaks[np.argsort(-scores)]:
                    if all(abs(d[k]-d[j])>=3 for j in selected):selected.append(k)
                    if len(selected)==6:break
            for k in selected:
                left,mid,right=response[max(0,k-1):k+2]
                denominator=left-2*mid+right
                delta=float(np.clip(.5*(left-right)/denominator,-.75,.75)) if abs(denominator)>1e-6 else 0
                points.append(p+n*(d[k]+delta));groups.append(group);strengths.append(math.sqrt(response[k]*step[k]))
        fitted=fit_profiles(np.array(points),np.array(groups),np.array(strengths),ref,(a+b)/2,radius)
        if fitted:lines.append(fitted[0]);residuals.append(fitted[1]);supports.append(fitted[2]);accepted+=1
        else:lines.append(ref);residuals.append(-1);supports.append(0)
    chosen=intersections(lines)
    plausible=valid(chosen/[w-1,h-1]) and .75<area(chosen)/area(initial)<1.3 and max(np.linalg.norm((chosen-initial)/[w-1,h-1],axis=1))<.1
    if not plausible or accepted<4:chosen=initial.copy()
    padding=3 if accepted==4 and plausible else 6
    center=chosen.mean(axis=0);lines=[line(chosen[e],chosen[(e+1)%4]) for e in range(4)]
    for ll in lines:ll[2]+=padding*(1 if ll[:2]@center+ll[2]>0 else -1)
    padded=np.clip(intersections(lines),[0,0],[w-1,h-1])
    return chosen,padded,accepted,residuals,supports

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--dataset',type=Path,required=True)
    parser.add_argument('--records',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();records=json.loads(args.records.read_text())
    variants=['gray_nearest','gray_consensus','color_nearest','color_consensus']
    def process(record):
        frame='frame_'+record['file'].split('_frame_')[1]
        image=cv2.imread(str(args.dataset/record['sequence']/frame));assert image is not None
        initial=np.array(record['model_polygon']);gt=np.array(record['ground_truth_polygon'])
        out={'file':record['file'],'sequence':record['sequence']}
        for variant in variants:
            before,after,count,res,support=refine(image,initial,variant)
            out[variant]={**metrics(after,gt),'before_padding':metrics(before,gt),'accepted':count,'residual':res,'support':support,'polygon':after.tolist()}
        return out
    with ThreadPoolExecutor(max_workers=4) as pool:
        rows=[]
        for i,r in enumerate(pool.map(process,records)):
            rows.append(r)
            if i%30==0:print('Processed',i,flush=True)
    args.output.write_text(json.dumps(rows,indent=2))
    for variant in variants:
        rs=[r[variant] for r in rows]
        print(variant,{k:float(np.mean([r[k] for r in rs])) for k in ['corner','edge']},'inward>2',sum(r['inward']>2 for r in rs),'accepted4',sum(r['accepted']==4 for r in rs),'bad4',sum(r['accepted']==4 and r['inward']>2 for r in rs),flush=True)

if __name__=='__main__':main()
