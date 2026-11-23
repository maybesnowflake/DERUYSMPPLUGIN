import json, math, os
from PIL import Image

U = 8.0                      # 모델 단위/블록 (아이템 디스플레이 scale 2 → 실제 1블록 = 8단위)
Y_CENTER = 3.0             # 모델 중심(8)이 바닥에서 3블록 위 → 높이 0~6블록 사용 가능 (transport-model.yml position y=3)
def m(b, axis):              # 블록 좌표 → 모델 좌표
    if axis == 1: return round(8 + U * (b - Y_CENTER), 3)
    return round(8 + U * b, 3)

TEX = {
 "plank":"minecraft:block/oak_planks", "dplank":"minecraft:block/dark_oak_planks",
 "spruce":"minecraft:block/spruce_log", "spruce_top":"minecraft:block/spruce_log_top",
 "sp_plank":"minecraft:block/spruce_planks",
 "iron":"minecraft:block/iron_block", "stone":"minecraft:block/stone", "andesite":"minecraft:block/polished_andesite",
 "deep":"minecraft:block/polished_deepslate",
 "gold":"minecraft:block/gold_block", "dia_b":"minecraft:block/diamond_block", "em_b":"minecraft:block/emerald_block",
 "raw_gold":"minecraft:block/raw_gold_block",
 "black":"minecraft:block/black_concrete", "heart":"deruy:item/heart",
 "diamond":"minecraft:item/diamond", "emerald":"minecraft:item/emerald", "ingot":"minecraft:item/gold_ingot",
 "nether":"minecraft:item/netherite_ingot", "star":"minecraft:item/nether_star", "nugget":"minecraft:item/gold_nugget",
 "apple":"minecraft:item/golden_apple",
}
els = []
def face_uv(w, h):
    return [0, 0, round(min(16, 16*w), 3), round(min(16, 16*h), 3)]

def box(x0,y0,z0,x1,y1,z1, tex, top=None, rot=None, name=""):
    """블록 좌표 상자. 면마다 1블록=텍스처 1장 비율로 UV."""
    dx,dy,dz = x1-x0, y1-y0, z1-z0
    faces = {}
    for f,(w,h) in {"north":(dx,dy),"south":(dx,dy),"east":(dz,dy),"west":(dz,dy),"up":(dx,dz),"down":(dx,dz)}.items():
        t = top if (top and f in ("up","down")) else tex
        faces[f] = {"uv": face_uv(w,h), "texture": "#"+t}
    e = {"name":name or tex, "from":[m(x0,0),m(y0,1),m(z0,2)], "to":[m(x1,0),m(y1,1),m(z1,2)], "faces":faces}
    if rot: 
        ax, ang, org = rot
        e["rotation"] = {"axis":ax, "angle":ang, "origin":[m(org[0],0),m(org[1],1),m(org[2],2)]}
    els.append(e)

def split_box(x0,y0,z0,x1,y1,z1, tex, axis, top=None, name=""):
    """긴 판을 1블록 단위 여러 장으로(블록을 이어 붙인 느낌, 텍스처 늘어남 없음)."""
    a0,a1 = {"x":(x0,x1),"y":(y0,y1),"z":(z0,z1)}[axis]
    n = max(1, math.ceil(round((a1-a0)/1.0,3)))
    step=(a1-a0)/n
    for i in range(n):
        lo,hi=a0+i*step,a0+(i+1)*step
        c=[x0,y0,z0,x1,y1,z1]
        k={"x":0,"y":1,"z":2}[axis]; c[k]=lo; c[k+3]=hi
        box(*c, tex, top=top, name=name)

def plane(cx,cy,cz,size,tex,axis="x",angle=0,facing="z", name=""):
    """평면 아이템(두께 0). facing=z면 north/south 면, x면 east/west 면."""
    h=size/2
    if facing=="z":
        f=[cx-h,cy-h,cz]; t=[cx+h,cy+h,cz]; faces={"north":{"uv":[0,0,16,16],"texture":"#"+tex},"south":{"uv":[0,0,16,16],"texture":"#"+tex}}
    else:
        f=[cx,cy-h,cz-h]; t=[cx,cy+h,cz+h]; faces={"east":{"uv":[0,0,16,16],"texture":"#"+tex},"west":{"uv":[0,0,16,16],"texture":"#"+tex}}
    e={"name":name or tex,"from":[m(f[0],0),m(f[1],1),m(f[2],2)],"to":[m(t[0],0),m(t[1],1),m(t[2],2)],"faces":faces}
    if angle: e["rotation"]={"axis":axis,"angle":angle,"origin":[m(cx,0),m(cy,1),m(cz,2)]}
    els.append(e)

# 마크 체력 하트 (9x9). K=검정 테두리, R=빨강, D=어두운 빨강, W=하이라이트
HEART = [
 ".KK...KK.",
 "KRRK.KRRK",
 "KRWRKRRRK",
 "KRRRRRRRK",
 "KRRRRRRDK",
 ".KRRRRDK.",
 "..KRRDK..",
 "...KDK...",
 "....K....",
]
def heart_png(path):
    col={"K":(20,0,0,255),"R":(255,19,19,255),"D":(187,0,0,255),"W":(255,255,255,255)}
    im=Image.new("RGBA",(16,16),(0,0,0,0))
    for y,row in enumerate(HEART):
        for x,c in enumerate(row):
            if c!=".": im.putpixel((x,y),col[c])
    im.save(path)

def heart3d(cx,cy,cz,size,thick,rot=None):
    """픽셀 줄마다 상자 하나씩 돌출 → 하트 모양 그대로, 옆면은 검정."""
    px=size/9
    for r,row in enumerate(HEART):
        runs=[];start=None
        for i,c in enumerate(row+"."):
            if c!="." and start is None: start=i
            if c=="." and start is not None: runs.append((start,i)); start=None
        for a,b in runs:
            x0=cx-size/2+a*px; x1=cx-size/2+b*px
            y1=cy+size/2-r*px; y0=y1-px
            z0=cz-thick/2; z1=cz+thick/2
            e={"name":"heart","from":[m(x0,0),m(y0,1),m(z0,2)],"to":[m(x1,0),m(y1,1),m(z1,2)],
               "faces":{"north":{"uv":[b,r,a,r+1],"texture":"#heart"},
                        "south":{"uv":[a,r,b,r+1],"texture":"#heart"},
                        "up":{"uv":[0,0,1,1],"texture":"#black"},"down":{"uv":[0,0,1,1],"texture":"#black"},
                        "east":{"uv":[0,0,1,1],"texture":"#black"},"west":{"uv":[0,0,1,1],"texture":"#black"}}}
            if rot:
                ax,ang=rot; e["rotation"]={"axis":ax,"angle":ang,"origin":[m(cx,0),m(cy,1),m(cz,2)]}
            els.append(e)

# ── 바퀴·차대 ─────────────────────────────────
W=1.55   # 벽 바깥 x
for sx in (-1,1):
    for z in (-1.3,1.3):
        x0,x1 = (sx*1.36, sx*1.58) if sx>0 else (sx*1.58, sx*1.36)
        box(x0,0.04,z-0.46,x1,0.96,z+0.46,"stone",name="wheel")                         # 바퀴(정사각)
        box(x0+0.01*sx,0.04,z-0.46,x1+0.01*sx,0.96,z+0.46,"stone",rot=("x",45,(0,0.5,z)),name="wheel45")  # 45° → 팔각형
        hx0,hx1=(1.585,1.63) if sx>0 else (-1.63,-1.585)
        box(hx0,0.30,z-0.20,hx1,0.70,z+0.20,"andesite",name="hub")
        px0,px1=(1.63,1.68) if sx>0 else (-1.68,-1.63)
        box(px0,0.44,z-0.06,px1,0.56,z+0.06,"iron",name="pin")
for z in (-1.3,1.3):
    box(-1.36,0.42,z-0.08,1.36,0.58,z+0.08,"iron",name="axle")
for sx in (-1,1):
    x0,x1=(sx*1.18,sx*0.92) if sx<0 else (0.92,1.18)
    x0,x1=min(x0,x1),max(x0,x1)
    split_box(x0,0.58,-2.2,x1,0.86,2.2,"deep","z",name="chassis")
# ── 바닥 ─────────────────────────────────────
split_box(-1.40,0.86,-2.0,1.40,1.06,2.0,"dplank","z",name="floor")
# ── 옆벽(판자 3단, 1블록씩 이어 붙임) ─────────────
WALL_TOP=2.30   # 기존 1.0 높이보다 높게
rows=[(1.06,1.47,"plank"),(1.47,1.88,"sp_plank"),(1.88,WALL_TOP,"plank")]
for sx in (-1,1):
    x0,x1=(1.40,W) if sx>0 else (-W,-1.40)
    for y0,y1,t in rows:
        split_box(x0,y0,-1.88,x1,y1,1.88,t,"z",name="side")
    # 위 테두리(살짝 튀어나옴)
    cx0,cx1=(1.36,W+0.05) if sx>0 else (-W-0.05,-1.36)
    split_box(cx0,WALL_TOP,-1.88,cx1,WALL_TOP+0.10,1.88,"dplank","z",name="side-cap")
    # 철 띠
    bx0,bx1=(W,W+0.03) if sx>0 else (-W-0.03,-W)
    for z in (-1.0,0.0,1.0):
        box(bx0,1.06,z-0.06,bx1,WALL_TOP,z+0.06,"iron",name="side-band")
# ── 앞뒤 벽 ───────────────────────────────────
for sz in (-1,1):
    z0,z1=(1.88,2.03) if sz>0 else (-2.03,-1.88)
    for y0,y1,t in rows:
        split_box(-1.40,y0,z0,1.40,y1,z1,t,"x",name="end")
    cz0,cz1=(1.84,2.08) if sz>0 else (-2.08,-1.84)
    split_box(-1.40,WALL_TOP,cz0,1.40,WALL_TOP+0.10,cz1,"dplank","x",name="end-cap")
    # 연결 고리
    k0,k1=(2.03,2.40) if sz>0 else (-2.40,-2.03)
    box(-0.12,0.66,k0,0.12,0.84,k1,"iron",name="coupler")
# ── 모서리 기둥(벽과 겹치지 않게 바깥으로) ─────────
for sx in (-1,1):
    for sz in (-1,1):
        x0,x1=(1.40,1.62) if sx>0 else (-1.62,-1.40)
        z0,z1=(1.88,2.10) if sz>0 else (-2.10,-1.88)
        box(x0,0.86,z0,x1,WALL_TOP+0.16,z1,"spruce",top="spruce_top",name="corner")
        # 기둥 머리 철 장식
        box(x0-0.01,WALL_TOP+0.16,z0-0.01,x1+0.01,WALL_TOP+0.22,z1+0.01,"iron",name="corner-cap")
# ── 보물 더미 ─────────────────────────────────
# 바닥을 꽉 채운 금 더미(벽 안쪽, 벽 위로 살짝 솟음)
split_box(-1.38,1.06,-1.86,1.38,1.95,1.86,"raw_gold","z",name="pile-base")
# 언덕 모양으로 블록을 엇갈려 쌓음 (각 블록은 아래 블록에 박혀 있음 → 공중에 뜬 것 없음)
mound=[
 # 1단: 바닥 더미 위
 (-0.75,1.85,-1.05,0.62,"gold",0),(0.70,1.85,-0.95,0.58,"em_b",22.5),(0.0,1.85,0.0,0.80,"gold",-22.5),
 (-0.70,1.85,0.95,0.60,"dia_b",22.5),(0.72,1.85,1.05,0.56,"gold",0),(-0.05,1.85,-1.30,0.52,"dia_b",0),
 (0.10,1.85,1.30,0.54,"em_b",0),(-1.0,1.85,0.10,0.45,"raw_gold",22.5),(1.0,1.85,0.05,0.45,"gold",-22.5),
 # 2단
 (-0.25,2.45,-0.45,0.60,"gold",22.5),(0.35,2.45,0.40,0.55,"raw_gold",0),(0.45,2.30,-0.95,0.45,"gold",0),
 (-0.45,2.30,1.00,0.45,"em_b",-22.5),
 # 꼭대기
 (0.0,2.95,0.0,0.45,"dia_b",22.5),
]
for cx,yb,cz,s,t,ang in mound:
    box(cx-s/2,yb,cz-s/2,cx+s/2,yb+s,cz+s/2,t,rot=("y",ang,(cx,yb,cz)) if ang else None,name="mound")
# 큰 하트 2개(아래쪽이 더미에 박힘)
heart3d(-0.40,3.00,0.65,1.00,0.16,rot=("y",22.5))
heart3d(0.60,2.75,-0.70,0.80,0.14,rot=("y",-22.5))
# 아이템(평면) — 모두 아래쪽이 더미에 박히게
items=[
 (-1.0,2.25,-0.30,0.55,"diamond","z",-22.5,"x"),(1.0,2.20,0.30,0.55,"emerald","z",22.5,"x"),
 (0.95,2.25,-1.55,0.50,"ingot","x",22.5,"z"),(-0.95,2.25,1.55,0.50,"ingot","x",-22.5,"z"),
 (0.15,3.50,0.05,0.55,"star","z",0,"y"),(-0.30,2.40,-1.40,0.50,"nether","x",-22.5,"z"),
 (0.65,3.00,0.75,0.45,"diamond","z",-22.5,"y"),(-0.85,2.15,-1.55,0.45,"emerald","x",22.5,"z"),
 (1.05,2.25,1.45,0.45,"nether","z",22.5,"y"),(-1.05,2.60,0.35,0.45,"apple","x",-22.5,"z"),
 (0.30,3.05,-0.35,0.40,"ingot","z",45,"y"),(-0.60,2.95,-0.80,0.40,"nugget","x",22.5,"z"),
 (0.95,2.60,0.95,0.40,"ingot","x",-22.5,"z"),(-0.20,2.40,1.55,0.45,"diamond","x",22.5,"z"),
]
for cx,cy,cz,s,t,face,ang,ax in items:
    plane(cx,cy,cz,s,t,axis=ax,angle=ang,facing=face,name="item-"+t)

# 범위 검사(-16..32)
for e in els:
    for v in e["from"]+e["to"]:
        assert -16 <= v <= 32, (e["name"], v)
    r=e.get("rotation")
    if r: assert r["angle"] in (-45,-22.5,0,22.5,45), r

textures={k:v for k,v in TEX.items()}
textures["particle"]=TEX["plank"]
model={"credit":"DERUY treasure wagon (Claude)","texture_size":[16,16],"textures":textures,"elements":els,
       "display":{"gui":{"rotation":[30,225,0],"scale":[0.28,0.28,0.28],"translation":[0,-2,0]},
                  "ground":{"scale":[0.2,0.2,0.2]},"fixed":{"scale":[0.3,0.3,0.3]},
                  "thirdperson_righthand":{"scale":[0.15,0.15,0.15]},"firstperson_righthand":{"scale":[0.15,0.15,0.15]}}}
base="DERUY_TreasureWagon/assets/deruy"
os.makedirs(base+"/models/item",exist_ok=True); os.makedirs(base+"/items",exist_ok=True); os.makedirs(base+"/textures/item",exist_ok=True)
json.dump(model,open(base+"/models/item/treasure_wagon.json","w"),ensure_ascii=False,indent=1)
json.dump({"model":{"type":"minecraft:model","model":"deruy:item/treasure_wagon"}},open(base+"/items/treasure_wagon.json","w"),indent=2)
heart_png(base+"/textures/item/heart.png")
json.dump({"pack":{"description":"DERUY 보물 수레 (1.21.11)","min_format":[75,0],"max_format":[75,0]}},open("DERUY_TreasureWagon/pack.mcmeta","w"),ensure_ascii=False,indent=2)
print("elements",len(els))
