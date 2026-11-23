"""모델 JSON 미리보기: 바닐라 텍스처 + z버퍼 (정사영)."""
import json, math, sys, os
import numpy as np
from PIL import Image

VAN = os.environ.get("VANILLA_TEX", "vanilla/t")
_tex = {}
def texture(tid):
    if tid not in _tex:
        p = tid.split(":", 1)[1].replace("/", "_")
        im = Image.open(os.path.join(VAN, p + ".png")).convert("RGBA")
        w = im.size[0]
        im = im.crop((0, 0, w, w))          # 애니메이션 텍스처는 첫 프레임
        _tex[tid] = np.asarray(im).astype(np.float32)
    return _tex[tid]

FACE = {  # (s,t) → 단위 상자 좌표, 법선
    "north": (lambda s, t: (1 - s, 1 - t, 0 * s), (0, 0, -1)),
    "south": (lambda s, t: (s, 1 - t, 0 * s + 1), (0, 0, 1)),
    "west": (lambda s, t: (0 * s, 1 - t, s), (-1, 0, 0)),
    "east": (lambda s, t: (0 * s + 1, 1 - t, 1 - s), (1, 0, 0)),
    "up": (lambda s, t: (s, 0 * s + 1, t), (0, 1, 0)),
    "down": (lambda s, t: (s, 0 * s, 1 - t), (0, -1, 0)),
}

def rotmat(axis, ang):
    a = math.radians(ang); c, s = math.cos(a), math.sin(a)
    if axis == "x": return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == "y": return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])

def render(model_path, yaw, pitch, size=900, span=56, center=(8, 0, 8)):
    md = json.load(open(model_path))
    texmap = md["textures"]
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    V = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]]) @ np.array([[cy, 0, -sy], [0, 1, 0], [sy, 0, cy]])
    img = np.zeros((size, size, 3), np.float32); img[:] = (150, 190, 235)
    zb = np.full((size, size), np.inf, np.float32)
    sc = size / span
    light = np.array([0.35, 0.85, -0.4]); light /= np.linalg.norm(light)
    C = np.array(center, float)
    for e in md["elements"]:
        f = np.array(e["from"], float); t = np.array(e["to"], float)
        R = np.eye(3); o = np.zeros(3)
        if "rotation" in e:
            R = rotmat(e["rotation"]["axis"], e["rotation"]["angle"]); o = np.array(e["rotation"]["origin"], float)
        glow = e.get("light_emission", 0)
        for fname, fd in e["faces"].items():
            fn, n = FACE[fname]
            ref = fd["texture"].lstrip("#")
            tid = texmap.get(ref, ref)
            tx = texture(tid); th, tw = tx.shape[:2]
            u0, v0, u1, v1 = fd["uv"]
            def P(s, tt):
                u = np.array(fn(s, tt), float)
                w = f + (t - f) * u
                w = R @ (w - o) + o
                v = V @ (w - C)
                return np.array([size / 2 + v[0] * sc, size / 2 - v[1] * sc, v[2]])
            p0, p1, p3 = P(0, 0), P(1, 0), P(0, 1)
            nw = R @ np.array(n, float)
            shade = 0.55 + 0.45 * max(0.0, float(nw @ light)) + 0.12 * abs(nw[1])
            if glow: shade = 1.15
            e1 = p1[:2] - p0[:2]; e2 = p3[:2] - p0[:2]
            det = e1[0] * e2[1] - e1[1] * e2[0]
            if abs(det) < 1e-6: continue
            pts = np.array([p0[:2], p1[:2], p3[:2], p1[:2] + e2])
            x0, y0 = np.floor(pts.min(0)).astype(int); x1, y1 = np.ceil(pts.max(0)).astype(int)
            x0, y0 = max(x0, 0), max(y0, 0); x1, y1 = min(x1, size - 1), min(y1, size - 1)
            if x1 < x0 or y1 < y0: continue
            ys, xs = np.mgrid[y0:y1 + 1, x0:x1 + 1].astype(np.float32)
            dx = xs + .5 - p0[0]; dy = ys + .5 - p0[1]
            s = (dx * e2[1] - dy * e2[0]) / det
            tt = (e1[0] * dy - e1[1] * dx) / det
            inside = (s >= 0) & (s <= 1) & (tt >= 0) & (tt <= 1)
            if not inside.any(): continue
            z = p0[2] + s * (p1[2] - p0[2]) + tt * (p3[2] - p0[2])
            u = u0 + s * (u1 - u0); v = v0 + tt * (v1 - v0)
            ti = np.clip((u / 16 * tw).astype(int), 0, tw - 1); tj = np.clip((v / 16 * th).astype(int), 0, th - 1)
            col = tx[tj, ti]
            ok = inside & (col[..., 3] > 10)
            reg = zb[y0:y1 + 1, x0:x1 + 1]
            ok &= z < reg
            reg[ok] = z[ok]
            img[y0:y1 + 1, x0:x1 + 1][ok] = np.clip(col[..., :3][ok] * shade, 0, 255)
    return Image.fromarray(img.astype(np.uint8))

if __name__ == "__main__":
    base = sys.argv[1]
    out = sys.argv[2]
    names = ["treasure_wagon", "treasure_wagon_75", "treasure_wagon_50", "treasure_wagon_25"]
    views = [(35, -28), (-145, -22)]
    cell = 700
    sheet = Image.new("RGB", (cell * 4, cell * len(views)), (255, 255, 255))
    for i, n in enumerate(names):
        for j, (yw, pt) in enumerate(views):
            im = render(f"{base}/assets/deruy/models/item/{n}.json", yw, pt, size=cell, span=50, center=(8, -4, 8))
            sheet.paste(im, (i * cell, j * cell))
    sheet.save(out)
    print("saved", out)
