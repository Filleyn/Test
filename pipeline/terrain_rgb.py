#!/usr/bin/env python3
"""Turn an elevation GeoTIFF (or VRT mosaic) into terrain-RGB PMTiles.

The tiles use the Mapbox terrain-RGB encoding, which MapLibre reads directly
for hillshading (a raster-dem source with encoding "mapbox"), and which the
app decodes to look up the elevation of any point:

    elevation_m = -10000 + (R * 65536 + G * 256 + B) * 0.1

Elevations stay in metres inside the tiles, as MapLibre expects; the app shows
feet.
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


def encode_terrain_rgb(elev_m):
    v = np.clip(np.round((np.nan_to_num(elev_m, nan=0.0) + 10000.0) * 10.0), 0, 2 ** 24 - 1).astype(np.uint32)
    rgb = np.stack([(v >> 16) & 255, (v >> 8) & 255, v & 255], axis=-1).astype(np.uint8)
    return rgb


def render_tile(src, tile):
    b = mercantile.xy_bounds(tile)
    dst = np.full((TILE, TILE), np.nan, dtype=np.float32)
    reproject(
        source=rasterio.band(src, 1),
        destination=dst,
        src_nodata=src.nodata,
        dst_transform=from_bounds(b.left, b.bottom, b.right, b.top, TILE, TILE),
        dst_crs="EPSG:3857",
        dst_nodata=np.nan,
        resampling=Resampling.bilinear,
    )
    if np.all(np.isnan(dst)):
        return None
    buf = io.BytesIO()
    Image.fromarray(encode_terrain_rgb(dst), "RGB").save(buf, format="PNG", optimize=True)
    return buf.getvalue()


_src = None


def _init_worker(dem):
    global _src
    _src = rasterio.open(dem)


def _render(t):
    data = render_tile(_src, mercantile.Tile(*t))
    return (zxy_to_tileid(t[2], t[0], t[1]), data) if data is not None else None


def build(dem, bbox, minzoom, maxzoom, out, name, workers=None, clip=None):
    import multiprocessing as mp

    import region_clip
    keep = region_clip.tile_keeper(clip)
    todo = [(t.x, t.y, t.z) for z in range(minzoom, maxzoom + 1) for t in mercantile.tiles(*bbox, zooms=z) if keep(t.z, t.x, t.y)]
    print(f"rendering {len(todo)} terrain tiles")
    tiles = []
    with mp.Pool(workers or mp.cpu_count(), initializer=_init_worker, initargs=(dem,)) as pool:
        for i, result in enumerate(pool.imap_unordered(_render, todo, chunksize=16)):
            if result is not None:
                tiles.append(result)
            if i and i % 2000 == 0:
                print(f"  {i}/{len(todo)}", flush=True)
    if not tiles:
        raise SystemExit("No elevation data inside the bounding box")
    tiles.sort(key=lambda p: p[0])
    with open(out, "wb") as f:
        w = Writer(f)
        for tid, data in tiles:
            w.write_tile(tid, data)
        w.finalize(
            {
                "tile_type": TileType.PNG,
                "tile_compression": Compression.NONE,
                "min_lon_e7": int(bbox[0] * 1e7), "min_lat_e7": int(bbox[1] * 1e7),
                "max_lon_e7": int(bbox[2] * 1e7), "max_lat_e7": int(bbox[3] * 1e7),
                "center_zoom": max(minzoom, maxzoom - 2),
                "center_lon_e7": int((bbox[0] + bbox[2]) / 2 * 1e7),
                "center_lat_e7": int((bbox[1] + bbox[3]) / 2 * 1e7),
            },
            {
                "name": name,
                "type": "baselayer",
                "format": "png",
                "encoding": "mapbox",
                "attribution": "Elevation: USGS 3DEP (public domain)",
                "description": "Terrain-RGB elevation tiles, metres",
            },
        )
    return len(tiles)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dem", required=True)
    ap.add_argument("--bbox", required=True, help="west,south,east,north")
    ap.add_argument("--minzoom", type=int, default=6)
    ap.add_argument("--maxzoom", type=int, default=14)
    ap.add_argument("--out", required=True)
    ap.add_argument("--name", default="TrailGuide terrain")
    ap.add_argument("--clip", help="GeoJSON outline; tiles outside it are left out")
    a = ap.parse_args()
    bbox = [float(v) for v in a.bbox.split(",")]
    n = build(a.dem, bbox, a.minzoom, a.maxzoom, a.out, a.name, clip=a.clip)
    print(f"wrote {n} terrain tiles to {a.out}")


if __name__ == "__main__":
    main()
