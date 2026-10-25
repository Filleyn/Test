#!/usr/bin/env python3
"""Print the USGS 3DEP elevation tile URLs that cover a bounding box.

3DEP tiles are 1 x 1 degree, named by their north-west corner (n46w121 covers
45-46 N, 120-121 W). Product "13" is 1/3 arc-second (about 10 m), product "1"
is 1 arc-second (about 30 m, used for the statewide build to save disk).
"""
import argparse
import math

BASE = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/{p}/TIFF/current/{name}/USGS_{p}_{name}.tif"


def tile_names(bbox):
    west, south, east, north = bbox
    names = []
    for top in range(math.floor(south) + 1, math.ceil(north) + 1):
        for left in range(math.floor(west), math.ceil(east)):
            ns = f"n{top:02d}" if top >= 0 else f"s{-top:02d}"
            ew = f"w{-left:03d}" if left < 0 else f"e{left:03d}"
            names.append(ns + ew)
    return names


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--bbox", required=True, help="west,south,east,north")
    ap.add_argument("--product", default="13", choices=["13", "1"])
    a = ap.parse_args()
    bbox = [float(v) for v in a.bbox.split(",")]
    for name in tile_names(bbox):
        print(BASE.format(p=a.product, name=name))


if __name__ == "__main__":
    main()
