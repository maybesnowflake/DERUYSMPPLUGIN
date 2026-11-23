"""DERUY 보물 수레 v2 — 바닐라 텍스처만 쓰는 고해상도 모델 + 손상 단계(100/75/50/25).

좌표: 블록 단위. x=폭, y=위, z=길이(앞 +z). 바닥 y=0.
모델은 절반 크기로 만들고 ItemDisplay scale 2로 표시 (모델 중심 8 = 바닥에서 3블록 위).
"""
import json, math, os, random, sys
from PIL import Image

VANM = os.environ.get("VANILLA_MODELS", "vanilla")   # 바닐라 모델 json (template_chain 등)
VAN = os.environ.get("VANILLA_TEX", VANM + "/t")    # 바닐라 텍스처 png (실루엣 계산용, 팩에는 포함 안 함)
OUT = sys.argv[1] if len(sys.argv) > 1 else "pack2"
U, Y_CENTER = 8.0, 3.0
ANG = (-45, -22.5, 0, 22.5, 45)

def m(b, axis):
    return round(8 + U * (b - (Y_CENTER if axis == 1 else 0)), 4)

def M(p): return [m(p[0], 0), m(p[1], 1), m(p[2], 2)]

# ── 텍스처 ──────────────────────────────────────
T = {}
def tex(path):
    key = path.split("/")[-1]
    T[key] = "minecraft:" + path
    return key

class Model:
    def __init__(self): self.els = []
    def add(self, e, tag=""):
        e["name"] = tag or e.get("name", "")
        self.els.append(e)

def uvbox(w, h, u0=0, v0=0):
    return [round(u0, 4), round(v0, 4), round(min(16, u0 + 16 * w), 4), round(min(16, v0 + 16 * h), 4)]

def box(md, a, b, t, top=None, side_uv=None, rot=None, tag="", light=None, skip=()):
    """a,b: 블록 좌표 모서리. 면마다 '1블록 = 텍스처 1장' 비율."""
    x0, y0, z0 = a; x1, y1, z1 = b
    dx, dy, dz = x1 - x0, y1 - y0, z1 - z0
    sizes = {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}
    faces = {}
    for f, (w, h) in sizes.items():
        if f in skip: continue
        tt = top if (top and f in ("up", "down")) else t
        faces[f] = {"uv": uvbox(w, h), "texture": "#" + tex(tt)}
    e = {"from": M(a), "to": M(b), "faces": faces}
    if rot:
        ax, ang, o = rot
        assert ang in ANG, ang
        if ang: e["rotation"] = {"axis": ax, "angle": ang, "origin": M(o)}
    if light: e["light_emission"] = light
    md.add(e, tag)

def split(md, a, b, t, axis, top=None, tag="", seg=1.0, keep=None):
    """긴 판을 1블록 이하 조각으로. keep(i)→False 인 조각은 빼서 부서진 느낌."""
    k = "xyz".index(axis)
    lo, hi = a[k], b[k]
    n = max(1, math.ceil(round((hi - lo) / seg, 3)))
    for i in range(n):
        if keep and not keep(i, n): continue
        p, q = list(a), list(b)
        p[k] = lo + (hi - lo) * i / n; q[k] = lo + (hi - lo) * (i + 1) / n
        box(md, p, q, t, top=top, tag=tag)

# ── 스프라이트 입체화(바닐라 아이템처럼 1픽셀 두께) ──────────
_sil = {}
def silhouette(path, frame=16):
    if path not in _sil:
        im = Image.open(os.path.join(VAN, path.replace("/", "_") + ".png")).convert("RGBA")
        w = im.size[0]
        _sil[path] = (w, [[im.getpixel((x, y))[3] > 0 for x in range(w)] for y in range(w)])
    return _sil[path]

AX = {  # 앞면 방향: (u축, v축, 두께축, u부호, v부호)
    "z": (0, 1, 2), "x": (2, 1, 0), "y": (0, 2, 1),
}
FACE_OF = {(2, -1): "north", (2, 1): "south", (0, -1): "west", (0, 1): "east", (1, -1): "down", (1, 1): "up"}

def sprite(md, path, c, size, facing="z", rot=None, tag="", thick=1.0, flip=False, pivot=None):
    """path 텍스처를 실루엣대로 돌출. c=중심, size=한 변 길이(블록)."""
    n, mask = silhouette(path)
    px = size / n
    s = 16 / n                         # uv 단위/픽셀
    ui, vi, wi = AX[facing]
    t = px * thick
    for r in range(n):
        runs, st = [], None
        for x in range(n + 1):
            on = x < n and mask[r][x]
            if on and st is None: st = x
            if not on and st is not None: runs.append((st, x)); st = None
        for a, b in runs:
            lo, hi = [0, 0, 0], [0, 0, 0]
            ua, ub = (-size / 2 + a * px, -size / 2 + b * px)
            if flip: ua, ub = -ub, -ua
            va, vb = (size / 2 - (r + 1) * px, size / 2 - r * px)
            if facing == "y": va, vb = -vb, -va      # 눕힌 아이템: 그림 위쪽이 -z(뒤)
            lo[ui], hi[ui] = c[ui] + ua, c[ui] + ub
            lo[vi], hi[vi] = c[vi] + va, c[vi] + vb
            lo[wi], hi[wi] = c[wi] - t / 2, c[wi] + t / 2
            front = [a * s, r * s, b * s, (r + 1) * s]
            mirror = [b * s, r * s, a * s, (r + 1) * s]
            strip = [a * s, r * s, b * s, (r + 1) * s]
            endA = [a * s, r * s, (a + 1) * s, (r + 1) * s]
            endB = [(b - 1) * s, r * s, b * s, (r + 1) * s]
            key = "#" + tex(path)
            faces = {}
            if facing == "z":
                faces = {"south": front, "north": mirror, "up": strip, "down": strip, "west": endA, "east": endB}
                if flip: faces = {"north": front, "south": mirror, "up": strip, "down": strip, "east": endA, "west": endB}
            elif facing == "x":
                faces = {"west": front, "east": mirror, "up": strip, "down": strip, "north": endA, "south": endB}
                if flip: faces = {"east": front, "west": mirror, "up": strip, "down": strip, "south": endA, "north": endB}
            else:  # y: 위를 보고 누움
                faces = {"up": front, "down": front, "south": strip, "north": strip, "west": endA, "east": endB}
            e = {"from": M(lo), "to": M(hi), "faces": {f: {"uv": [round(v, 4) for v in uv], "texture": key} for f, uv in faces.items()}}
            if rot:
                ax, ang = rot
                assert ang in ANG
                if ang: e["rotation"] = {"axis": ax, "angle": ang, "origin": M(pivot or c)}
            md.add(e, tag)

def hardcore_heart(md, c, size, facing="z", rot=None, tag="heart"):
    """하드코어 하트: 검은 테두리(container_hardcore)를 두께 있게 돌출 + 앞뒤에 hardcore_full 덮개."""
    sprite(md, "gui/sprites/hud/heart/container_hardcore", c, size, facing, rot, tag, thick=1.6)
    n = 9; px = size / n
    off = px * 0.81
    ui, vi, wi = AX[facing]
    for sgn in (1, -1):
        cc = list(c); cc[wi] += sgn * off
        sprite(md, "gui/sprites/hud/heart/hardcore_full", cc, size, facing, rot, tag, thick=0.02, flip=(sgn < 0), pivot=c)

def vanilla_embed(md, path, textures, c, size, rot=None, tag="", light=None):
    """바닐라 모델 요소를 그대로 축소·이동해 넣는다(랜턴·사슬·무거운 코어)."""
    d = json.load(open(path))
    for e in d["elements"]:
        f = [c[i] + (e["from"][i] - 8) / 16 * size for i in range(3)]
        t = [c[i] + (e["to"][i] - 8) / 16 * size for i in range(3)]
        faces = {}
        for k, v in e["faces"].items():
            ref = v["texture"].lstrip("#")
            faces[k] = {"uv": v["uv"], "texture": "#" + tex(textures.get(ref, next(iter(textures.values()))))}
        ne = {"from": M(f), "to": M(t), "faces": faces}
        r = e.get("rotation")
        if r and rot: raise ValueError("회전 중복")
        if r:
            o = [c[i] + (r["origin"][i] - 8) / 16 * size for i in range(3)]
            ne["rotation"] = {"axis": r["axis"], "angle": r["angle"], "origin": M(o)}
        elif rot and rot[1]:
            ne["rotation"] = {"axis": rot[0], "angle": rot[1], "origin": M(c)}
        if light: ne["light_emission"] = light
        md.add(ne, tag)

# ── 부품 ────────────────────────────────────────
LOG, LOG_TOP = "block/dark_oak_log", "block/dark_oak_log_top"
RAIL = "block/stripped_dark_oak_log"
BOARD, BOARD2 = "block/spruce_planks", "block/oak_planks"
DECK = "block/dark_oak_planks"
IRON = "block/iron_block"
HUB = "block/polished_deepslate"

def wheel(md, cx, cz, broken=0, wobble=0, tag="wheel"):
    """살 달린 바퀴. broken: 0 멀쩡, 1 살 하나 빠짐, 2 테 일부·살 빠짐."""
    cy, R, th, w = 0.52, 0.50, 0.10, 0.07
    side = 1 if cx > 0 else -1
    x0, x1 = (cx - w, cx + w)
    o = (cx, cy, cz)
    rim = [((x0, cy + R - th, cz - 0.21), (x1, cy + R, cz + 0.21), 0, "top"),
           ((x0, cy - R, cz - 0.21), (x1, cy - R + th, cz + 0.21), 0, "bot"),
           ((x0, cy - 0.21, cz + R - th), (x1, cy + 0.21, cz + R), 0, "front"),
           ((x0, cy - 0.21, cz - R), (x1, cy + 0.21, cz - R + th), 0, "back")]
    rim += [(a, b, ang, n + str(ang)) for a, b, _, n in rim[:2] for ang in (45, -45)]
    for i, (a, b, ang, n) in enumerate(rim):
        if broken >= 2 and n in ("top45", "front"): continue
        box(md, a, b, RAIL, rot=("x", ang, o) if ang else None, tag=tag)
        # 쇠 테(바깥으로 얇게)
        ia, ib = list(a), list(b)
        ia[0], ib[0] = x0 - 0.012, x1 + 0.012
        if n.startswith("top"): ia[1] = ib[1] - 0.03
        elif n.startswith("bot"): ib[1] = ia[1] + 0.03
        elif n == "front": ia[2] = ib[2] - 0.03
        else: ib[2] = ia[2] + 0.03
        box(md, ia, ib, "block/iron_block", rot=("x", ang, o) if ang else None, tag=tag)
    spokes = [((x0 + .01, cy - R + th, cz - .035), (x1 - .01, cy + R - th, cz + .035), 0),
              ((x0 + .01, cy - .035, cz - R + th), (x1 - .01, cy + .035, cz + R - th), 0),
              ((x0 + .01, cy - R + th, cz - .035), (x1 - .01, cy + R - th, cz + .035), 45),
              ((x0 + .01, cy - R + th, cz - .035), (x1 - .01, cy + R - th, cz + .035), -45)]
    for i, (a, b, ang) in enumerate(spokes):
        if broken >= 1 and i == 2: continue
        if broken >= 2 and i == 1: continue
        box(md, a, b, LOG, rot=("x", ang, o) if ang else None, tag=tag)
    box(md, (cx - .11, cy - .12, cz - .12), (cx + .11, cy + .12, cz + .12), HUB, tag=tag)
    box(md, (cx - .10, cy - .12, cz - .12), (cx + .10, cy + .12, cz + .12), HUB, rot=("x", 45, o), tag=tag)
    pa = cx + side * 0.11
    box(md, (min(pa, pa + side * .05), cy - .05, cz - .05), (max(pa, pa + side * .05), cy + .05, cz + .05), IRON, tag=tag)

def lantern(md, x, z, side_z, tag="lantern"):
    """기둥에서 뻗은 쇠 팔 + 사슬 + 매달린 랜턴(빛남)."""
    y_arm = 2.42
    za, zb = sorted((z, z + side_z * 0.42))
    box(md, (x - .03, y_arm, za), (x + .03, y_arm + .06, zb), IRON, tag=tag)
    lz = z + side_z * 0.36
    vanilla_embed(md, VANM + "/template_chain.json", {"texture": "block/iron_chain"}, (x, y_arm - .13, lz), 0.26, tag=tag)
    vanilla_embed(md, VANM + "/template_hanging_lantern.json", {"lantern": "block/lantern"}, (x, y_arm - .52, lz), 0.62, tag=tag, light=15)

def heavy_core(md, c, size, rot=None, tag="loot"):
    vanilla_embed(md, VANM + "/heavy_core.json", {"all": "block/heavy_core"}, (c[0], c[1] + size / 4, c[2]), size, rot=rot, tag=tag)

# ── 조립 ────────────────────────────────────────
def build(stage):
    """stage: 100, 75, 50, 25 (남은 체력 %)"""
    md = Model()
    rnd = random.Random(7)
    dmg = {100: 0, 75: 1, 50: 2, 25: 3}[stage]

    # 바퀴: 25%는 앞왼쪽 바퀴가 빠짐
    for cx in (-1.48, 1.48):
        for cz in (-1.3, 1.3):
            if dmg >= 3 and cx < 0 and cz > 0: continue
            br = 0
            if dmg >= 1 and cx > 0 and cz < 0: br = 1
            if dmg >= 2 and cx < 0 and cz < 0: br = 2
            if dmg >= 3 and cx > 0 and cz > 0: br = 2
            wheel(md, cx, cz, br)
    # 차축·베어링
    for cz in (-1.3, 1.3):
        lo = -1.40 if not (dmg >= 3 and cz > 0) else -0.9
        box(md, (lo, .47, cz - .06), (1.40, .57, cz + .06), IRON, tag="axle")
        for sx in (-1, 1):
            if dmg >= 3 and sx < 0 and cz > 0: continue
            box(md, (sx * 1.18 - .09, .55, cz - .10), (sx * 1.18 + .09, .80, cz + .10), IRON, tag="axle")
    # 차대
    for sx in (-1, 1):
        split(md, (sx * .95 - .11, .80, -2.25), (sx * .95 + .11, .98, 2.25), RAIL, "z", tag="chassis")
    for cz in (-2.0, 0.0, 2.0):
        box(md, (-1.30, .80, cz - .08), (1.30, .96, cz + .08), LOG, top=LOG, tag="chassis")
    # 바닥 판자 6장 (손상 시 몇 장 빠짐)
    boards = 6
    for i in range(boards):
        x0 = -1.38 + 2.76 * i / boards; x1 = -1.38 + 2.76 * (i + 1) / boards - 0.015
        def keep(j, n, i=i):
            if dmg >= 2 and (i, j) in ((0, 3), (1, 3)): return False
            if dmg >= 3 and (i, j) in ((0, 2), (0, 4), (1, 4), (2, 4), (5, 0)): return False
            return True
        split(md, (x0, .98, -2.10), (x1, 1.10, 2.10), DECK, "z", tag="deck", keep=keep)

    # 옆벽: 판자 3단, 사이에 틈
    rows = [(1.10, 1.47, BOARD), (1.50, 1.87, BOARD2), (1.90, 2.27, BOARD)]
    def wall_keep(side, row, j, n):
        # side: -1 왼쪽, 1 오른쪽 / j: 조각 번호
        if dmg >= 1 and side > 0 and row == 2 and j in (1, 2): return False
        if dmg >= 1 and side > 0 and row == 1 and j == 2: return False
        if dmg >= 1 and side < 0 and row == 1 and j == 0: return False
        if dmg >= 2 and side < 0 and row >= 1: return j == 3 and row == 1
        if dmg >= 2 and side > 0 and row == 2 and j in (1, 2): return False
        if dmg >= 3 and side > 0 and row >= 1: return j == 0 and row == 1
        if dmg >= 3 and side < 0: return row == 0 and j in (1, 3)
        return True
    for sx in (-1, 1):
        xa, xb = sorted((sx * 1.38, sx * 1.50))
        for ri, (y0, y1, t) in enumerate(rows):
            split(md, (xa, y0, -2.0), (xb, y1, 2.0), t, "z", tag="side", keep=lambda j, n, ri=ri, sx=sx: wall_keep(sx, ri, j, n))
        # 위 난간
        if dmg < 2 or sx > 0:
            ra, rb = sorted((sx * 1.33, sx * 1.55))
            split(md, (ra, 2.27, -2.0), (rb, 2.37, 2.0), RAIL, "z", tag="rail",
                  keep=(lambda j, n: not (dmg >= 1 and j == 2) and not (dmg >= 3 and j != 0)))
        # 바깥 말뚝 2개 + 쇠 받침
        for zi, sz in enumerate((-0.67, 0.67)):
            sa, sb = sorted((sx * 1.50, sx * 1.61))
            top = 2.45
            if dmg >= 1 and sx > 0 and zi == 1: top = 1.75
            if dmg >= 1 and sx < 0 and zi == 0: top = 2.0
            if dmg >= 2 and sx < 0: top = 1.45 if zi == 0 else 1.2
            if dmg >= 3: top = min(top, 1.35)
            box(md, (sa, 0.92, sz - .07), (sb, top, sz + .07), LOG, top=LOG_TOP, tag="stake")
            ia, ib = sorted((sx * 1.61, sx * 1.635))
            box(md, (ia, 1.02, sz - .10), (ib, 1.30, sz + .10), IRON, tag="bracket")
        # 가운데 쇠 띠
        if dmg < 2 or sx > 0:
            ba, bb = sorted((sx * 1.50, sx * 1.52))
            split(md, (ba, 1.64, -2.0), (bb, 1.72, 2.0), IRON, "z", tag="strap",
                  keep=(lambda j, n: not (dmg >= 1 and sx > 0 and j == 3) and not (dmg >= 3 and j > 0)))
    # 앞뒤 벽
    for sz in (-1, 1):
        za, zb = sorted((sz * 2.0, sz * 2.12))
        for ri, (y0, y1, t) in enumerate(rows):
            def ek(j, n, ri=ri, sz=sz):
                if dmg == 1 and sz > 0 and ri == 2 and j == 1: return False
                if dmg >= 2 and sz > 0 and ri == 2: return j == 0
                if dmg >= 3 and sz > 0: return ri == 0
                if dmg >= 3 and sz < 0 and ri == 2: return j == 2
                return True
            split(md, (-1.38, y0, za), (1.38, y1, zb), t, "x", tag="end", keep=ek)
        ra, rb = sorted((sz * 1.96, sz * 2.16))
        if dmg < 3:
            split(md, (-1.38, 2.27, ra), (1.38, 2.37, rb), RAIL, "x", tag="rail", keep=(lambda j, n, sz=sz: not (dmg >= 2 and sz > 0 and j > 0)))
        # 연결 고리
        ka, kb = sorted((sz * 2.12, sz * 2.62))
        box(md, (-.10, .78, ka), (.10, .92, kb), IRON, tag="coupler")
        kk = sz * 2.58
        box(md, (-.14, .70, kk - .05), (.14, 1.0, kk + .05), IRON, tag="coupler")
    # 모서리 기둥 + 쇠 모자
    for sx in (-1, 1):
        for sz in (-1, 1):
            xa, xb = sorted((sx * 1.38, sx * 1.62)); za, zb = sorted((sz * 2.0, sz * 2.24))
            top = 2.52
            if dmg >= 2 and sx < 0 and sz > 0: top = 1.55
            if dmg >= 3 and not (sx > 0 and sz < 0): top = 1.4 if sx > 0 else 1.15
            box(md, (xa, .86, za), (xb, top, zb), LOG, top=LOG_TOP, tag="corner")
            if top > 2.4:
                box(md, (xa - .015, top, za - .015), (xb + .015, top + .05, zb + .015), IRON, tag="corner")
            # 아래쪽 ㄱ자 쇠 받침
            box(md, (xa - .012, .95, za - .012), (xb + .012, 1.22, zb + .012), IRON, tag="corner", skip=("up", "down"))
    # 랜턴: 앞 왼쪽, 뒤 오른쪽 (손상 시 뒤 랜턴 떨어짐)
    if dmg < 2: lantern(md, -1.50, 2.12, 1)      # 앞 기둥이 부러지는 50%부터 없음
    if dmg < 1: lantern(md, 1.50, -2.12, -1)

    # 부서진 판자 조각(갑판 위·옆에 기울어진 판)
    debris = []
    if dmg >= 1: debris += [((1.05, 1.10, -0.2), (1.35, 1.16, 0.75), BOARD, ("x", 22.5)),
                            ((1.51, 1.45, -0.35), (1.57, 1.82, 0.62), BOARD, ("x", -22.5))]   # 옆에 덜렁거리는 판
    if dmg >= 2: debris += [((-1.35, 1.10, -1.6), (-1.05, 1.16, -0.4), BOARD2, ("z", 22.5)),
                            ((-0.4, 1.10, 1.55), (0.6, 1.16, 1.85), BOARD, ("y", 22.5))]
    if dmg >= 3: debris += [((-1.2, 1.10, 0.2), (-0.2, 1.16, 0.5), BOARD, ("y", -22.5)),
                            ((0.3, 1.10, -1.1), (0.6, 1.16, 0.1), BOARD2, ("x", -22.5)),
                            ((1.62, 0.1, -0.5), (1.68, 0.9, 0.0), BOARD, ("x", 22.5))]
    for a, b, t, (ax, ang) in debris:
        c = [(a[i] + b[i]) / 2 for i in range(3)]
        box(md, a, b, t, rot=(ax, ang, c), tag="debris")

    # ── 보물 ──
    fill_top = {0: 2.10, 1: 2.0, 2: 1.70, 3: 1.25}[dmg]
    # 바닥을 채운 금 더미(블록 조각 무늬가 보이게 금·원석금 섞음)
    if dmg < 3:
        for i in range(4):
            for j in range(3):
                t = ["block/gold_block", "block/raw_gold_block"][(i + j) % 2]
                x0 = -1.36 + 2.72 * j / 3; x1 = -1.36 + 2.72 * (j + 1) / 3
                z0 = -1.98 + 3.96 * i / 4; z1 = -1.98 + 3.96 * (i + 1) / 4
                h = fill_top - (0.06 * ((i * 3 + j) % 3))
                if dmg >= 2 and j == 0 and i in (2, 3): h = 1.35
                box(md, (x0, 1.10, z0), (x1, h, z1), t, tag="fill")
    # 언덕 블록: (x, z, 크기, 텍스처, y회전, 최소 남는 단계)
    mound = [
        (-0.70, -1.05, .62, "block/gold_block", 0, 2), (0.72, -0.95, .58, "block/emerald_block", 22.5, 1),
        (0.00, 0.05, .86, "block/gold_block", -22.5, 2), (-0.70, 0.95, .60, "block/diamond_block", 22.5, 1),
        (0.75, 1.05, .55, "block/gold_block", 0, 1), (-0.05, -1.40, .50, "block/netherite_block", 0, 0),
        (0.15, 1.35, .52, "block/emerald_block", 0, 0), (-1.0, 0.05, .44, "block/raw_gold_block", 22.5, 1),
        (1.0, 0.10, .46, "block/diamond_block", -22.5, 0), (0.5, -0.30, .40, "block/ancient_debris_side", 22.5, 0),
    ]
    alive = {0: 3, 1: 2, 2: 1, 3: 0}[dmg]   # 숫자가 클수록 많이 남음
    for x, z, s, t, ang, need in mound:
        if alive < 3 - need and need < 3:     # 손상될수록 쌓인 게 줄어듦
            if dmg >= 3 or (dmg == 2 and need == 0) or (dmg == 1 and need == 0 and rnd.random() < .5):
                continue
        yb = fill_top - 0.12
        top = "block/ancient_debris_top" if "ancient" in t else None
        box(md, (x - s / 2, yb, z - s / 2), (x + s / 2, yb + s, z + s / 2), t, top=top, rot=("y", ang, (x, yb, z)) if ang else None, tag="mound")
    if dmg <= 1:
        box(md, (-0.25 - .3, fill_top + .55, -0.40 - .3), (-0.25 + .3, fill_top + 1.15, -0.40 + .3), "block/gold_block", rot=("y", 22.5, (-0.25, fill_top + .55, -0.40)), tag="mound")
        box(md, (0.35 - .27, fill_top + .55, 0.45 - .27), (0.35 + .27, fill_top + 1.09, 0.45 + .27), "block/raw_gold_block", tag="mound")

    ft = fill_top
    # 하드코어 하트
    hearts = [((-0.35, ft + 1.25, 0.62), 1.05, "z", ("y", 22.5)), ((0.62, ft + 0.95, -0.72), 0.80, "x", ("y", -22.5)),
              ((0.95, ft + 0.55, 1.35), 0.55, "z", ("x", -22.5))]
    keep_h = {0: 3, 1: 3, 2: 2, 3: 1}[dmg]
    for (c, s, f, r) in hearts[:keep_h]:
        if dmg >= 3: c = (c[0], 1.25 + s * 0.38, c[2])
        hardcore_heart(md, c, s, f, r)
    # 비싼 아이템: (텍스처, 중심, 크기, 앞방향, 회전, 남는 최대 손상)
    items = [
        ("item/nether_star", (0.10, ft + 1.95, 0.05), 0.80, "z", ("y", 22.5), 1),
        ("item/totem_of_undying", (-0.95, ft + 0.62, -0.30), 0.66, "x", ("x", -22.5), 2),
        ("item/totem_of_undying", (0.98, ft + 0.55, 0.40), 0.52, "x", ("x", 22.5), 1),
        ("item/netherite_ingot", (0.30, ft + 1.24, -0.45), 0.58, "y", ("y", 22.5), 3),
        ("item/netherite_ingot", (-0.55, ft + 0.70, 1.45), 0.48, "z", ("x", -45), 2),
        ("item/netherite_ingot", (0.75, ft + 0.30, -1.55), 0.42, "y", ("y", -22.5), 1),
        ("item/diamond", (-0.95, ft + 0.40, 1.60), 0.40, "z", ("y", 45), 3),
        ("item/diamond", (0.70, ft + 0.95, 0.85), 0.50, "z", ("x", -22.5), 1),
        ("item/diamond", (-0.40, ft + 0.85, -1.35), 0.36, "x", ("z", 22.5), 2),
        ("item/emerald", (1.0, ft + 0.30, -0.40), 0.42, "x", ("z", -22.5), 2),
        ("item/emerald", (-1.0, ft + 0.95, 0.40), 0.38, "z", ("y", -22.5), 1),
        ("item/gold_ingot", (0.95, ft + 0.30, 1.55), 0.46, "y", ("y", 45), 3),
        ("item/gold_ingot", (-0.75, ft + 0.30, -1.62), 0.40, "y", ("y", -22.5), 3),
        ("item/gold_ingot", (0.25, ft + 0.60, 1.55), 0.36, "z", ("x", 22.5), 2),
        ("item/golden_apple", (-0.10, ft + 1.32, 1.15), 0.42, "z", ("y", -22.5), 1),
        ("item/heart_of_the_sea", (-1.05, ft + 0.35, -1.05), 0.38, "x", ("x", 22.5), 2),
        ("item/echo_shard", (0.55, ft + 1.30, 0.25), 0.34, "z", ("y", 45), 1),
        ("item/enchanted_book", (1.0, ft + 0.85, -1.20), 0.46, "x", ("x", -22.5), 1),
        ("item/ominous_trial_key", (-0.30, ft + 0.60, 0.10), 0.36, "y", ("y", 22.5), 2),
        ("item/mace", (-0.55, ft + 1.05, -0.75), 0.80, "z", ("z", 45), 1),
        ("item/netherite_sword", (0.55, ft + 0.90, 1.25), 0.85, "x", ("x", 45), 2),
    ]
    for path, c, s, f, r, maxd in items:
        if dmg > maxd: continue
        if dmg >= 3: c = (c[0], max(1.18, 1.25 + s * 0.25), c[2])
        elif dmg == 2: c = (c[0], c[1] - 0.25, c[2])
        sprite(md, path, c, s, f, r, tag="loot")
    if dmg <= 1:
        heavy_core(md, (-0.80, ft + 0.02, 1.20), 0.70, rot=("y", 22.5))
        heavy_core(md, (0.55, ft + 0.50, -0.05), 0.42, rot=("y", -22.5))
    elif dmg == 2:
        heavy_core(md, (-0.80, ft - 0.08, 1.20), 0.62, rot=("y", 22.5))
    # 25%: 떨어진 금괴 몇 개가 바닥 판 위에
    if dmg >= 3:
        sprite(md, "item/gold_ingot", (0.6, 1.13, 0.9), 0.42, "y", ("y", 22.5), tag="loot")
        sprite(md, "item/gold_ingot", (-0.3, 1.13, -0.4), 0.38, "y", ("y", -22.5), tag="loot")
        box(md, (0.1 - .25, 1.10, 1.2 - .25), (0.1 + .25, 1.60, 1.2 + .25), "block/gold_block", rot=("y", 22.5, (0.1, 1.1, 1.2)), tag="mound")
        heavy_core(md, (-0.6, 1.10, 0.9), 0.5, rot=("y", 22.5))
    return md

def write(md, name):
    for e in md.els:
        for v in e["from"] + e["to"]:
            assert -16 <= v <= 32, (e["name"], e["from"], e["to"])
    textures = dict(sorted(T.items()))
    textures["particle"] = "minecraft:block/spruce_planks"
    model = {"credit": "DERUY treasure wagon v2", "texture_size": [16, 16], "textures": textures, "elements": md.els,
             "display": {"gui": {"rotation": [30, 225, 0], "scale": [0.28, 0.28, 0.28], "translation": [0, -3, 0]},
                         "ground": {"scale": [0.2, 0.2, 0.2]}, "fixed": {"scale": [0.3, 0.3, 0.3]},
                         "thirdperson_righthand": {"scale": [0.15, 0.15, 0.15]}, "firstperson_righthand": {"scale": [0.15, 0.15, 0.15]}}}
    base = f"{OUT}/assets/deruy"
    os.makedirs(base + "/models/item", exist_ok=True); os.makedirs(base + "/items", exist_ok=True)
    json.dump(model, open(f"{base}/models/item/{name}.json", "w"), separators=(",", ":"))
    json.dump({"model": {"type": "minecraft:model", "model": f"deruy:item/{name}"}}, open(f"{base}/items/{name}.json", "w"), indent=2)
    return len(md.els)

if __name__ == "__main__":
    counts = {}
    for st, name in ((100, "treasure_wagon"), (75, "treasure_wagon_75"), (50, "treasure_wagon_50"), (25, "treasure_wagon_25")):
        counts[name] = write(build(st), name)
    # 모든 텍스처를 블록·아이템 아틀라스 양쪽에 등록 (1.21.11은 item/·gui/ 텍스처가 블록 아틀라스에 없음)
    sources = [{"type": "minecraft:single", "resource": v} for v in sorted(set(T.values()))]
    os.makedirs(f"{OUT}/assets/minecraft/atlases", exist_ok=True)
    for atlas in ("blocks", "items"):
        json.dump({"sources": sources}, open(f"{OUT}/assets/minecraft/atlases/{atlas}.json", "w"), indent=1)
    json.dump({"pack": {"description": "DERUY 보물 수레 v2 (1.21.11)", "min_format": [75, 0], "max_format": [75, 0]}},
              open(f"{OUT}/pack.mcmeta", "w"), ensure_ascii=False, indent=2)
    print(counts, "textures", len(T))
