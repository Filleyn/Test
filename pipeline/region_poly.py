#!/usr/bin/env python3
"""The state's outline, for clipping, from Geofabrik's .poly file (next to its .osm.pbf).

Neighbouring states' map files would otherwise overlap: each is built for a rectangle,
so with two states installed, hillshade and contours near the border would draw twice.
Writes a GeoJSON polygon, grown by --buffer-km so the edges meet without a gap.
"""
import argparse
import json
import urllib.request

from shapely.geometry import Polygon, MultiPolygon, mapping
from shapely.ops import unary_union


def parse_poly(text):
    """Osmosis .poly format: a name, then rings of 'lon lat' lines ending in END; '!' rings are holes."""
    lines = [l.strip() for l in text.splitlines()]
    i = 1
    outers, holes = [], []
    while i < len(lines):
        name = lines[i]
        if name == "END" or not name:
            i += 1
            continue
        ring = []
        i += 1
        while i < len(lines) and lines[i] != "END":
            parts = lines[i].split()
            if len(parts) >= 2:
                ring.append((float(parts[0]), float(parts[1])))
            i += 1
        i += 1
        if len(ring) >= 3:
            (holes if name.startswith("!") else outers).append(ring)
    shape = unary_union([Polygon(r) for r in outers])
    for h in holes:
        shape = shape.difference(Polygon(h))
    return shape


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--osm-url", required=True, help="Geofabrik .osm.pbf URL; the .poly next to it is used")
    ap.add_argument("--out", required=True)
    ap.add_argument("--buffer-km", type=float, default=1.5)
    a = ap.parse_args()
    url = a.osm_url.replace("-latest.osm.pbf", ".poly")
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "TrailGuideGPS map build"}), timeout=60) as r:
        shape = parse_poly(r.read().decode())
    # About 1 km is 0.009 degrees of latitude; good enough for a margin.
    grown = shape.buffer(a.buffer_km * 0.009).simplify(0.002)
    if isinstance(grown, Polygon):
        grown = MultiPolygon([grown])
    json.dump({"type": "Feature", "properties": {"source": url}, "geometry": mapping(grown)}, open(a.out, "w"))
    print(f"{url}: area {shape.area:.2f} sq deg, written to {a.out}")


if __name__ == "__main__":
    main()
