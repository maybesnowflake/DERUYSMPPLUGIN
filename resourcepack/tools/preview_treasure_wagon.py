import json, math, sys
import numpy as np
from PIL import Image, ImageDraw
M=json.load(open("DERUY_TreasureWagon/assets/deruy/models/item/treasure_wagon.json"))
heart=Image.open("DERUY_TreasureWagon/assets/deruy/textures/item/heart.png").convert("RGBA")
COL={"plank":(184,148,95),"dplank":(66,43,20),"spruce":(58,37,16),"spruce_top":(110,80,45),"sp_plank":(114,84,48),
 "iron":(220,220,220),"stone":(125,125,125),"andesite":(132,134,133),"deep":(72,72,73),"gold":(246,208,61),
 "dia_b":(98,237,228),"em_b":(42,203,87),"raw_gold":(221,169,46),"black":(8,10,15),
 "diamond":(80,220,230),"emerald":(30,200,90),"ingot":(250,220,80),"nether":(70,60,65),"star":(240,240,255),"nugget":(250,230,120),"apple":(240,200,60)}
def rot(p,r):
    if not r: return p
    o=np.array(r["origin"]);a=math.radians(r["angle"]);c,s=math.cos(a),math.sin(a);q=p-o
    x,y,z=q
    if r["axis"]=="x": q=np.array([x,y*c-z*s,y*s+z*c])
    elif r["axis"]=="y": q=np.array([x*c+z*s,y,-x*s+z*c])
    else: q=np.array([x*c-y*s,x*s+y*c,z])
    return q+o
FACES={"north":([0,0,0],[1,0,0],[1,1,0],[0,1,0],(0,0,-1)),"south":([0,0,1],[1,0,1],[1,1,1],[0,1,1],(0,0,1)),
 "west":([0,0,0],[0,0,1],[0,1,1],[0,1,0],(-1,0,0)),"east":([1,0,0],[1,0,1],[1,1,1],[1,1,0],(1,0,0)),
 "down":([0,0,0],[1,0,0],[1,0,1],[0,0,1],(0,-1,0)),"up":([0,1,0],[1,1,0],[1,1,1],[0,1,1],(0,1,0))}
def render(yaw,pitch,out,size=900):
    cy,sy=math.cos(math.radians(yaw)),math.sin(math.radians(yaw));cp,sp=math.cos(math.radians(pitch)),math.sin(math.radians(pitch))
    def view(p):
        x,y,z=p-np.array([8,-4,8]); x,z=x*cy-z*sy,x*sy+z*cy; y,z=y*cp-z*sp,y*sp+z*cp
        return np.array([x,y,z])
    light=np.array([0.4,0.8,-0.45]);light/=np.linalg.norm(light)
    polys=[]
    for e in M["elements"]:
        f=np.array(e["from"]);t=np.array(e["to"]);r=e.get("rotation")
        for name,fd in e["faces"].items():
            a,b,c_,d,n=FACES[name]
            pts=[rot(f+(t-f)*np.array(v),r) for v in (a,b,c_,d)]
            nn=rot(np.array(n,float)+np.array(r["origin"]) if r else np.array(n,float),r)-(np.array(r["origin"]) if r else 0)
            tex=fd["texture"][1:]
            if tex=="heart":
                u0,v0,u1,v1=fd["uv"];xs=range(int(min(u0,u1)),int(max(u0,u1)));cols=[heart.getpixel((x,int(v0)))[:3] for x in xs]
                col=tuple(int(sum(c[i] for c in cols)/len(cols)) for i in range(3))
            else: col=COL.get(tex,(255,0,255))
            vp=[view(p) for p in pts]; vn=view(nn+np.array([8,-4,8]))-view(np.array([8,-4,8]))
            two=(t-f).min()==0
            if vn[2]>0 and not two: continue
            sh=0.55+0.45*abs(float(np.dot(nn,light)))
            polys.append((sum(p[2] for p in vp)/4,[(p[0],p[1]) for p in vp],tuple(int(c*sh) for c in col)))
    polys.sort(key=lambda x:-x[0])
    im=Image.new("RGB",(size,size),(135,180,230));dr=ImageDraw.Draw(im);sc=size/52
    for _,pts,col in polys:
        dr.polygon([(size/2+x*sc,size/2-y*sc) for x,y in pts],fill=col,outline=tuple(max(0,c-35) for c in col))
    im.save(out)
render(35,-25,"view_a.png");render(-140,-30,"view_b.png");render(90,-10,"view_side.png");render(0,-70,"view_top.png")
ims=[Image.open(f) for f in ("view_a.png","view_b.png","view_side.png","view_top.png")]
sheet=Image.new("RGB",(1800,1800));[sheet.paste(im,((i%2)*900,(i//2)*900)) for i,im in enumerate(ims)];sheet.save("preview.png")
