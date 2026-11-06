#!/usr/bin/env python3
"""Copy a PMTiles file keeping only zoom levels up to --maxzoom.

Used for the online map: Cloudflare gets a lighter elevation file (the map
just overzooms it for hill shading), while downloads keep the full detail.

  pmtiles_maxzoom.py IN.pmtiles OUT.pmtiles --maxzoom 10
"""
import argparse

from pmtiles.reader import MmapSource, Reader, all_tiles
from pmtiles.tile import zxy_to_tileid
from pmtiles.writer import Writer

HEADER_KEEP = ("tile_type", "tile_compression", "min_lon_e7", "min_lat_e7", "max_lon_e7", "max_lat_e7",
               "center_zoom", "center_lon_e7", "center_lat_e7")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--maxzoom", type=int, required=True)
    a = ap.parse_args()
    with open(a.src, "rb") as f:
        src = MmapSource(f)
        reader = Reader(src)
        header = reader.header()
        metadata = reader.metadata()
        if header["max_zoom"] <= a.maxzoom:
            raise SystemExit(f"Already at zoom {header['max_zoom']} or below; nothing to drop")
        tiles = [(zxy_to_tileid(z, x, y), data) for (z, x, y), data in all_tiles(src) if z <= a.maxzoom]
    tiles.sort(key=lambda t: t[0])
    out_header = {k: header[k] for k in HEADER_KEEP}
    out_header["center_zoom"] = min(out_header["center_zoom"], a.maxzoom)
    with open(a.dst, "wb") as f:
        w = Writer(f)
        for tid, data in tiles:
            w.write_tile(tid, data)
        w.finalize(out_header, metadata)
    print(f"kept {len(tiles)} tiles up to zoom {a.maxzoom}")


if __name__ == "__main__":
    main()
