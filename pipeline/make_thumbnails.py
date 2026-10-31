#!/usr/bin/env python3
"""Pictures for the base map buttons in the app's layers screen (Topo, Satellite, Hybrid).

Draws one sample spot (Green Lakes and Broken Top, in the Three Sisters) three ways, from
the published map files and USGS imagery, so the app can show them without a connection:

  topo       landcover, hillshade, contours, water, roads, tracks, trails, peaks (map colors)
  satellite  USGS imagery
  hybrid     imagery with water, roads, trails and peak names on top

Writes base_topo.jpg, base_satellite.jpg and base_hybrid.jpg (480 x 384) to --out.
"""
import argparse
import gzip
import io
import math
import os

import mapbox_vector_tile
import numpy as np
import requests
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from pmtiles.reader import Reader

W, H = 480, 384       # final size
SS = 2                # drawn at twice the size, then shrunk, for smooth lines
Z = 13                # map zoom of the picture (256-px tiles)
CENTER = (44.081, -121.735)
USGS = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}"
UA = {"User-Agent": "TrailGuideGPS thumbnail build"}


def gpx(lat, lon, z):
    """Global pixel at zoom z (256-px tiles)."""
    n = 256 * 2 ** z
    x = (lon + 180) / 360 * n
    r = math.radians(lat)
    y = (1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * n
    return x, y


CX, CY = gpx(*CENTER, Z)
OX, OY = CX - W / 2, CY - H / 2          # top-left of the picture, zoom-13 pixels


TOKEN = os.environ.get("GH_TOKEN")


def remote(url):
    """Range reads of a release file. Works for private repositories through the API with a token."""
    s = requests.Session()
    auth = {"Authorization": f"Bearer {TOKEN}", "Accept": "application/octet-stream"} if TOKEN and "api.github.com" in url else {}

    def get(offset, length):
        r = s.get(url, headers={**UA, **auth, "Range": f"bytes={offset}-{offset + length - 1}"}, timeout=60)
        r.raise_for_status()
        return r.content
    return Reader(get)


def tile(reader, z, x, y):
    b = reader.get(z, x, y)
    if b and b[:2] == b"\x1f\x8b":
        b = gzip.decompress(b)
    return b


def to_canvas(tx, ty, ext, pt):
    """Tile-local MVT coordinates to canvas pixels."""
    gx = (tx + pt[0] / ext) * 256
    gy = (ty + pt[1] / ext) * 256
    return ((gx - OX) * SS, (gy - OY) * SS)


def vector_features(reader, z=Z):
    """Every feature in the picture area as (layer, props, type, list of rings/lines in canvas px)."""
    out = []
    t0x, t0y = int(OX // 256), int(OY // 256)
    t1x, t1y = int((OX + W) // 256), int((OY + H) // 256)
    for tx in range(t0x, t1x + 1):
        for ty in range(t0y, t1y + 1):
            data = tile(reader, z, tx, ty)
            if not data:
                continue
            dec = mapbox_vector_tile.decode(data, default_options={"y_coord_down": True})
            for lname, layer in dec.items():
                ext = layer.get("extent", 4096)
                for f in layer["features"]:
                    g = f["geometry"]
                    t, c = g["type"], g["coordinates"]
                    if t == "LineString":
                        parts = [c]
                    elif t == "MultiLineString":
                        parts = c
                    elif t == "Polygon":
                        parts = c
                    elif t == "MultiPolygon":
                        parts = [ring for poly in c for ring in poly]
                    elif t == "Point":
                        parts = [[c]]
                    else:
                        continue
                    out.append((lname, f.get("properties", {}),
                                "poly" if "Polygon" in t else "point" if t == "Point" else "line",
                                [[to_canvas(tx, ty, ext, p) for p in part] for part in parts]))
    return out


def hillshade(reader):
    """Hillshade (0..1) over the picture, from the terrain-RGB tiles at their own zoom."""
    tz = reader.header()["max_zoom"]
    k = 2 ** (Z - tz)                    # zoom-13 px per terrain-zoom px (tile 256)
    ox, oy = OX / k, OY / k
    t0x, t0y = int(ox // 256), int(oy // 256)
    t1x, t1y = int((ox + W / k) // 256), int((oy + H / k) // 256)
    rows = []
    size = None
    for ty in range(t0y, t1y + 1):
        row = []
        for tx in range(t0x, t1x + 1):
            im = Image.open(io.BytesIO(tile(reader, tz, tx, ty))).convert("RGB")
            size = im.size[0]
            a = np.asarray(im).astype(np.float64)
            row.append(-10000 + (a[..., 0] * 65536 + a[..., 1] * 256 + a[..., 2]) * 0.1)
        rows.append(np.hstack(row))
    dem = np.vstack(rows)
    scale = size / 256                   # tiles may be 512 px
    px_m = 40075016.7 * math.cos(math.radians(CENTER[0])) / (256 * 2 ** tz * scale)
    gy, gx = np.gradient(dem, px_m)
    slope = np.arctan(np.hypot(gx, gy) * 1.6)
    aspect = np.arctan2(-gx, gy)
    az, alt = math.radians(315), math.radians(45)
    shade = np.sin(alt) * np.cos(slope) + np.cos(alt) * np.sin(slope) * np.cos(az - aspect)
    shade = np.clip(shade, 0, 1)
    img = Image.fromarray((shade * 255).astype(np.uint8))
    # Crop to the picture and stretch to the canvas.
    left = (ox - t0x * 256) * scale
    top = (oy - t0y * 256) * scale
    img = img.crop((int(left), int(top), int(left + W / k * scale), int(top + H / k * scale)))
    return np.asarray(img.resize((W * SS, H * SS), Image.BICUBIC)).astype(np.float64) / 255


def satellite():
    """USGS imagery mosaic at zoom 14 (twice the detail of the picture, matching the canvas)."""
    z = Z + 1
    ox, oy = OX * 2, OY * 2
    t0x, t0y = int(ox // 256), int(oy // 256)
    t1x, t1y = int((ox + W * 2) // 256), int((oy + H * 2) // 256)
    canvas = Image.new("RGB", ((t1x - t0x + 1) * 256, (t1y - t0y + 1) * 256))
    for tx in range(t0x, t1x + 1):
        for ty in range(t0y, t1y + 1):
            r = requests.get(USGS.format(z=z, x=tx, y=ty), headers=UA, timeout=60)
            r.raise_for_status()
            canvas.paste(Image.open(io.BytesIO(r.content)).convert("RGB"), ((tx - t0x) * 256, (ty - t0y) * 256))
    left, top = ox - t0x * 256, oy - t0y * 256
    return canvas.crop((int(left), int(top), int(left) + W * SS, int(top) + H * SS))


def hexc(h, a=255):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (a,)


def dashed(draw, pts, color, width, dash, gap):
    """A dashed polyline."""
    on, rem = True, dash
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        seg = math.hypot(x1 - x0, y1 - y0)
        pos = 0.0
        while pos < seg:
            step = min(rem, seg - pos)
            if on:
                a, b = pos / seg, (pos + step) / seg
                draw.line([(x0 + (x1 - x0) * a, y0 + (y1 - y0) * a), (x0 + (x1 - x0) * b, y0 + (y1 - y0) * b)], fill=color, width=width)
            pos += step
            rem -= step
            if rem <= 0:
                on = not on
                rem = dash if on else gap


def font(size):
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def overlay_lines(img, feats, hybrid):
    """Water, roads, tracks and trails on top of a base."""
    d = ImageDraw.Draw(img, "RGBA")
    for lname, p, t, parts in feats:
        if lname == "waterway" and t == "line":
            w = 5 if p.get("class") == "river" else 2
            for pts in parts:
                d.line(pts, fill=hexc("#6FB2E8" if hybrid else "#4A8FC7"), width=w)
    roads = {"motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service"}
    for lname, p, t, parts in feats:
        if lname != "transportation" or t != "line":
            continue
        cls = p.get("class")
        for pts in parts:
            if cls in roads:
                d.line(pts, fill=hexc("#FFFFFF"), width=8 if not hybrid else 6)
                d.line(pts, fill=hexc("#E8C46A" if hybrid else "#C9B48F"), width=5 if not hybrid else 3)
            elif cls == "track":
                if hybrid:
                    d.line(pts, fill=hexc("#FFFFFF", 200), width=6)
                d.line(pts, fill=hexc("#8A6A43"), width=3)
            elif cls == "path":
                if hybrid:
                    d.line(pts, fill=hexc("#FFFFFF", 210), width=7)
                dashed(d, pts, hexc("#B5462A"), 4, 12, 6)


def peaks(img, feats, hybrid):
    d = ImageDraw.Draw(img, "RGBA")
    f = font(22)
    seen = []
    pk = [(p, parts[0][0]) for lname, p, t, parts in feats if lname == "mountain_peak" and t == "point" and p.get("name")]
    pk.sort(key=lambda a: -(a[0].get("ele") or 0))
    for p, (x, y) in pk[:6]:
        if not (20 < x < W * SS - 20 and 20 < y < H * SS - 20) or any(abs(x - a) < 140 and abs(y - b) < 50 for a, b in seen):
            continue
        seen.append((x, y))
        d.polygon([(x, y - 9), (x - 9, y + 7), (x + 9, y + 7)], fill=hexc("#5A4636" if not hybrid else "#FFFFFF"))
        name = p["name"]
        tw = d.textlength(name, font=f)
        d.text((min(max(x - tw / 2, 10), W * SS - tw - 10), y + 10), name, font=f, fill=hexc("#FFFFFF" if hybrid else "#3D2E22"),
               stroke_width=3, stroke_fill=hexc("#000000", 170) if hybrid else hexc("#FFFFFF"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    rel = requests.get(f"https://api.github.com/repos/{a.repo}/releases/tags/{a.tag}",
                       headers={**UA, **({"Authorization": f"Bearer {TOKEN}"} if TOKEN else {})}, timeout=60).json()
    assets = {x["name"]: x["url"] for x in rel["assets"]}
    topo = remote(assets["topo.pmtiles"])
    contours = remote(assets["contours.pmtiles"])
    terrain = remote(assets["terrain.pmtiles"])
    feats = vector_features(topo)
    cfeats = vector_features(contours)
    print(f"{len(feats)} topo features, {len(cfeats)} contour features")

    # Topo.
    base = Image.new("RGBA", (W * SS, H * SS), hexc("#EFEEE2"))
    d = ImageDraw.Draw(base, "RGBA")
    cover = {"wood": "#D5E3C3", "forest": "#D5E3C3", "grass": "#E7EBD6", "farmland": "#E7EBD6", "scrub": "#E7EBD6",
             "rock": "#E6E0D2", "sand": "#E6E0D2", "ice": "#F4F8FB", "glacier": "#F4F8FB"}
    for lname, p, t, parts in feats:
        if lname == "landcover" and t == "poly" and p.get("class") in cover:
            for ring in parts:
                if len(ring) > 2:
                    d.polygon(ring, fill=hexc(cover[p["class"]]))
    shade = hillshade(terrain)
    arr = np.asarray(base).astype(np.float64)
    # Multiply-style hillshade, softened so colors still read.
    mult = 0.62 + 0.45 * shade
    arr[..., :3] = np.clip(arr[..., :3] * mult[..., None], 0, 255)
    base = Image.fromarray(arr.astype(np.uint8), "RGBA")
    d = ImageDraw.Draw(base, "RGBA")
    for lname, p, t, parts in cfeats:
        if t != "line":
            continue
        major = p.get("major") == 1
        for pts in parts:
            d.line(pts, fill=hexc("#A47448", 220) if major else hexc("#B88A5B", 75), width=3 if major else 1)
    for lname, p, t, parts in feats:
        if lname == "water" and t == "poly":
            for ring in parts:
                if len(ring) > 2:
                    d.polygon(ring, fill=hexc("#A9CFEA"))
    overlay_lines(base, feats, hybrid=False)
    peaks(base, feats, hybrid=False)
    base.convert("RGB").resize((W, H), Image.LANCZOS).save(f"{a.out}/base_topo.jpg", quality=86)

    # Satellite and hybrid.
    sat = satellite().convert("RGBA")
    sat.convert("RGB").resize((W, H), Image.LANCZOS).save(f"{a.out}/base_satellite.jpg", quality=86)
    hyb = sat.copy()
    overlay_lines(hyb, feats, hybrid=True)
    peaks(hyb, feats, hybrid=True)
    hyb.convert("RGB").resize((W, H), Image.LANCZOS).save(f"{a.out}/base_hybrid.jpg", quality=86)
    for n in ("topo", "satellite", "hybrid"):
        print(n, os.path.getsize(f"{a.out}/base_{n}.jpg"), "bytes")


if __name__ == "__main__":
    main()
