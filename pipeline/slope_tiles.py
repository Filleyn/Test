#!/usr/bin/env python3
"""Slope-angle shading tiles from the elevation model (slope.pmtiles).

Each tile is a small palette PNG that colors ground by how steep it is, the way
avalanche and scrambling maps do, and leaves gentler ground transparent:

    25–29°  yellow     30–34°  orange     35–44°  red
    45–59°  purple     60°+    dark purple

Built at zoom 10–12 (about 27 m per pixel at zoom 12 in Oregon, close to the
30 m elevation data); the map stretches zoom 12 when you zoom in further.
"""
import argparse
import io
import math

import mercantile
import numpy as np
import rasterio
from PIL import Image
from rasterio.transform import from_bounds
from rasterio.warp import Resampling, reproject
from pmtiles.tile import Compression, TileType, zxy_to_tileid
from pmtiles.writer import Writer

TILE = 256
CLASSES = [  # (lowest degrees, RGB)
    (25, (242, 226, 5)),
    (30, (245, 155, 0)),
    (35, (227, 36, 27)),
    (45, (156, 39, 176)),
    (60, (59, 11, 89)),
]


def palette_png(classes):
    img = Image.fromarray(classes.astype(np.uint8), "P")
    pal = [0, 0, 0]
    for _, rgb in CLASSES:
        pal += list(rgb)
    img.putpalette(pal + [0, 0, 0] * (256 - len(pal) // 3))
    img.info["transparency"] = bytes([0] + [255] * len(CLASSES))
    buf = io.BytesIO()
    img.save(buf, format="PNG", optimize=True, transparency=bytes([0] + [255] * len(CLASSES)))
    return buf.getvalue()


def render_tile(src, tile):
    b = mercantile.xy_bounds(tile)
    px = (b.right - b.left) / TILE
    # One extra pixel each side so the edges get a proper slope.
    dst = np.full((TILE + 2, TILE + 2), np.nan, dtype=np.float32)
    reproject(
        source=rasterio.band(src, 1), destination=dst, src_nodata=src.nodata,
        dst_transform=from_bounds(b.left - px, b.bottom - px, b.right + px, b.top + px, TILE + 2, TILE + 2),
        dst_crs="EPSG:3857", dst_nodata=np.nan, resampling=Resampling.bilinear,
    )
    if np.all(np.isnan(dst)):
        return None
    # Web Mercator stretches distances by 1/cos(latitude); undo that to get true metres per pixel.
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (tile.y + 0.5) / 2 ** tile.z))))
    ground = px * math.cos(math.radians(lat))
    gy, gx = np.gradient(np.nan_to_num(dst, nan=0.0), ground)
    slope = np.degrees(np.arctan(np.hypot(gx, gy)))[1:-1, 1:-1]
    # No slope where the elevation data stops (it would read as a cliff).
    v = ~np.isnan(dst)
    slope[~(v[1:-1, 1:-1] & v[:-2, 1:-1] & v[2:, 1:-1] & v[1:-1, :-2] & v[1:-1, 2:])] = 0
    classes = np.zeros(slope.shape, dtype=np.uint8)
    for i, (lo, _) in enumerate(CLASSES):
        classes[slope >= lo] = i + 1
    if not classes.any():
        return None
    return palette_png(classes)


_src = None


def _init_worker(dem):
    global _src
    _src = rasterio.open(dem)


def _render(t):
    data = render_tile(_src, mercantile.Tile(*t))
    return (zxy_to_tileid(t[2], t[0], t[1]), data) if data is not None else None


def build(dem, bbox, minzoom, maxzoom, out, workers=None):
    import multiprocessing as mp
    todo = [(t.x, t.y, t.z) for z in range(minzoom, maxzoom + 1) for t in mercantile.tiles(*bbox, zooms=z)]
    print(f"rendering {len(todo)} slope tiles")
    tiles = []
    with mp.Pool(workers or mp.cpu_count(), initializer=_init_worker, initargs=(dem,)) as pool:
        for i, r in enumerate(pool.imap_unordered(_render, todo, chunksize=16)):
            if r is not None:
                tiles.append(r)
            if i and i % 2000 == 0:
                print(f"  {i}/{len(todo)}", flush=True)
    tiles.sort(key=lambda p: p[0])
    with open(out, "wb") as f:
        w = Writer(f)
        for tid, data in tiles:
            w.write_tile(tid, data)
        w.finalize(
            {
                "tile_type": TileType.PNG, "tile_compression": Compression.NONE,
                "min_lon_e7": int(bbox[0] * 1e7), "min_lat_e7": int(bbox[1] * 1e7),
                "max_lon_e7": int(bbox[2] * 1e7), "max_lat_e7": int(bbox[3] * 1e7),
                "center_zoom": maxzoom, "center_lon_e7": int((bbox[0] + bbox[2]) / 2 * 1e7),
                "center_lat_e7": int((bbox[1] + bbox[3]) / 2 * 1e7),
            },
            {"name": "TrailGuide slope", "type": "overlay", "format": "png",
             "attribution": "Slope from USGS 3DEP elevation", "description": "Slope angle classes 25/30/35/45/60 degrees"},
        )
    return len(tiles)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dem", required=True)
    ap.add_argument("--bbox", required=True)
    ap.add_argument("--minzoom", type=int, default=10)
    ap.add_argument("--maxzoom", type=int, default=12)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    n = build(a.dem, [float(v) for v in a.bbox.split(",")], a.minzoom, a.maxzoom, a.out)
    print(f"wrote {n} slope tiles to {a.out}")


if __name__ == "__main__":
    main()
