#!/usr/bin/env python3
"""Build TrailGuide GPS's OSM-derived layers for one region.

Reads an OSM extract that is already clipped to the region (and ideally
tag-filtered, see the workflow), plus an optional elevation GeoTIFF, and writes:

  water.geojson          springs, drinking water, water points and wells
  trails.geojson         trails, paths and tracks with names, for offline search
  pois.geojson           peaks, trailheads, campsites, viewpoints, for search
  routing-graph.json.gz  network of junctions and segments for route planning

Lengths are in miles and elevations in feet, matching the app.
"""
import argparse
import gzip
import json
import math
import os
import sys
from collections import defaultdict

import osmium

MI_PER_M = 1 / 1609.344
FT_PER_M = 3.28084

# Ways a hiker can follow. Big highways are left out on purpose.
ROUTABLE = {
    "path": "trail", "footway": "trail", "bridleway": "trail", "steps": "trail",
    "pedestrian": "trail", "cycleway": "trail",
    "track": "track",
    "living_street": "road", "residential": "road", "unclassified": "road",
    "service": "road", "tertiary": "road", "secondary": "road",
}
TRAIL_KINDS = {"trail", "track"}

WATER_TAGS = [
    ("natural", "spring", "spring"),
    ("amenity", "drinking_water", "drinking_water"),
    ("amenity", "water_point", "water_point"),
    ("man_made", "water_well", "well"),
    ("natural", "water_point", "water_point"),
]
POI_TAGS = [
    ("natural", "peak", "peak"),
    ("highway", "trailhead", "trailhead"),
    ("tourism", "camp_site", "campsite"),
    ("tourism", "viewpoint", "viewpoint"),
    ("amenity", "toilets", "toilets"),
]


def haversine_m(lon1, lat1, lon2, lat2):
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def encode_polyline(coords, precision=5):
    """Google encoded polyline of (lon, lat) pairs, stored lat first."""
    factor = 10 ** precision
    out, prev_lat, prev_lon = [], 0, 0
    for lon, lat in coords:
        ilat, ilon = round(lat * factor), round(lon * factor)
        for d in (ilat - prev_lat, ilon - prev_lon):
            v = ~(d << 1) if d < 0 else d << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        prev_lat, prev_lon = ilat, ilon
    return "".join(out)


def is_foot_allowed(tags):
    foot = tags.get("foot")
    if foot in ("no", "private"):
        return False
    if foot in ("yes", "designated", "permissive"):
        return True
    return tags.get("access") not in ("no", "private")


def seasonal_flag(tags):
    for key in ("seasonal", "intermittent"):
        if tags.get(key) not in (None, "no"):
            return True
    return False


class Elevation:
    """Samples a DEM GeoTIFF in feet. Returns None everywhere if no DEM."""

    def __init__(self, path):
        self.ds = None
        if path and os.path.exists(path):
            import rasterio
            from rasterio.warp import transform

            self.ds = rasterio.open(path)
            self._transform = transform
            self.nodata = self.ds.nodata
            self.is_geographic = self.ds.crs is None or self.ds.crs.is_geographic

    def sample(self, coords):
        if self.ds is None or not coords:
            return [None] * len(coords)
        xs, ys = [c[0] for c in coords], [c[1] for c in coords]
        if not self.is_geographic:
            xs, ys = self._transform("EPSG:4326", self.ds.crs, xs, ys)
        out = []
        for v in self.ds.sample(list(zip(xs, ys)), indexes=1):
            val = float(v[0])
            if self.nodata is not None and val == self.nodata or val < -1000:
                out.append(None)
            else:
                out.append(val * FT_PER_M)
        return out


def densify(coords, step_m=30.0):
    """Add points so no gap is longer than step_m, for elevation sampling."""
    out = [coords[0]]
    for (x1, y1), (x2, y2) in zip(coords, coords[1:]):
        d = haversine_m(x1, y1, x2, y2)
        n = max(1, int(d // step_m))
        for i in range(1, n + 1):
            t = i / n
            out.append((x1 + (x2 - x1) * t, y1 + (y2 - y1) * t))
    return out


def gain_loss_ft(elevs, window=5):
    """Climb and descent in feet, smoothed so DEM noise doesn't inflate gain."""
    vals = [e for e in elevs if e is not None]
    if len(vals) < 2:
        return 0.0, 0.0
    half = window // 2
    smooth = []
    for i in range(len(vals)):
        seg = vals[max(0, i - half): i + half + 1]
        smooth.append(sum(seg) / len(seg))
    gain = loss = 0.0
    for a, b in zip(smooth, smooth[1:]):
        if b > a:
            gain += b - a
        else:
            loss += a - b
    return gain, loss


def feature(geom_type, coords, props):
    return {"type": "Feature", "geometry": {"type": geom_type, "coordinates": coords}, "properties": props}


def match_tag(tags, table):
    for key, value, kind in table:
        if tags.get(key) == value:
            return kind
    return None


def read_route_relations(pbf):
    """Map way id -> list of hiking route names it belongs to."""
    way_routes = defaultdict(list)
    for obj in osmium.FileProcessor(pbf, osmium.osm.RELATION):
        tags = obj.tags
        if tags.get("type") != "route" or tags.get("route") not in ("hiking", "foot"):
            continue
        name = tags.get("name") or tags.get("ref")
        if not name:
            continue
        for m in obj.members:
            if m.type == "w":
                way_routes[m.ref].append(name)
    return way_routes


def in_bbox(lon, lat, bbox):
    return bbox[0] <= lon <= bbox[2] and bbox[1] <= lat <= bbox[3]


def build(pbf, bbox, dem_path, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    elev = Elevation(dem_path)
    way_routes = read_route_relations(pbf)

    water, pois, trails = [], [], []
    ways = []  # (way_id, [node ids], [(lon, lat)], tags-subset)
    node_use = defaultdict(int)

    for obj in osmium.FileProcessor(pbf).with_locations():
        tags = obj.tags
        if obj.is_node():
            if not obj.location.valid():
                continue
            lon, lat = obj.location.lon, obj.location.lat
            if not in_bbox(lon, lat, bbox):
                continue
            wkind = match_tag(tags, WATER_TAGS)
            if wkind:
                if wkind == "well" and tags.get("drinking_water") in ("no",):
                    continue
                water.append(feature("Point", [round(lon, 6), round(lat, 6)], {
                    "kind": wkind,
                    "name": tags.get("name"),
                    "seasonal": seasonal_flag(tags),
                    "drinking_water": tags.get("drinking_water"),
                    "osm_id": f"n{obj.id}",
                    "last_edit": obj.timestamp.strftime("%Y-%m-%d"),
                }))
            pkind = match_tag(tags, POI_TAGS)
            if pkind:
                ele = tags.get("ele")
                ele_ft = None
                try:
                    ele_ft = round(float(ele.split()[0]) * FT_PER_M) if ele else None
                except ValueError:
                    ele_ft = None
                if ele_ft is None and pkind == "peak":
                    ele_ft = elev.sample([(lon, lat)])[0]
                    ele_ft = round(ele_ft) if ele_ft is not None else None
                pois.append(feature("Point", [round(lon, 6), round(lat, 6)], {
                    "kind": pkind, "name": tags.get("name"), "ele_ft": ele_ft,
                    "osm_id": f"n{obj.id}",
                }))
        elif obj.is_way():
            hw = tags.get("highway")
            kind = ROUTABLE.get(hw)
            if not kind or not is_foot_allowed(tags):
                continue
            refs, coords = [], []
            for n in obj.nodes:
                if n.location.valid():
                    refs.append(n.ref)
                    coords.append((n.lon, n.lat))
            if len(coords) < 2:
                continue
            if not any(in_bbox(x, y, bbox) for x, y in coords):
                continue
            routes = way_routes.get(obj.id, [])
            name = tags.get("name") or tags.get("ref") or (routes[0] if routes else None)
            info = {"kind": kind, "highway": hw, "name": name, "routes": routes,
                    "surface": tags.get("surface"), "sac_scale": tags.get("sac_scale")}
            ways.append((obj.id, refs, coords, info))
            for i, r in enumerate(refs):
                # Endpoints always count as junctions; shared nodes count twice.
                node_use[r] += 2 if i in (0, len(refs) - 1) else 1
            if kind in TRAIL_KINDS:
                trails.append(feature("LineString", [[round(x, 6), round(y, 6)] for x, y in coords], {
                    "kind": kind, "highway": hw, "name": name, "routes": routes,
                    "osm_id": f"w{obj.id}",
                }))

    # Routing graph: split ways at junctions (nodes used more than once).
    vertex_index, vertices, edges = {}, [], []
    names, kinds = [], ["trail", "track", "road"]
    name_index = {}

    def vid(ref, lonlat):
        if ref not in vertex_index:
            vertex_index[ref] = len(vertices)
            vertices.append([round(lonlat[0], 6), round(lonlat[1], 6)])
        return vertex_index[ref]

    for way_id, refs, coords, info in ways:
        start = 0
        for i in range(1, len(refs)):
            if node_use[refs[i]] >= 2 or i == len(refs) - 1:
                seg = coords[start: i + 1]
                if len(seg) >= 2:
                    length_m = sum(haversine_m(*a, *b) for a, b in zip(seg, seg[1:]))
                    gain, loss = gain_loss_ft(elev.sample(densify(seg)))
                    nm = info["name"]
                    if nm is not None and nm not in name_index:
                        name_index[nm] = len(names)
                        names.append(nm)
                    edges.append([
                        vid(refs[start], seg[0]), vid(refs[i], seg[-1]),
                        round(length_m * MI_PER_M, 4), round(gain, 1), round(loss, 1),
                        name_index.get(nm, -1), kinds.index(info["kind"]),
                        encode_polyline(seg),
                    ])
                start = i

    graph = {
        "version": 1,
        "units": {"length": "mi", "elevation": "ft"},
        "bbox": bbox,
        "has_elevation": elev.ds is not None,
        "vertices": vertices,
        "edge_fields": ["from", "to", "length_mi", "gain_ft", "loss_ft",
                        "name", "kind", "polyline5"],
        "names": names,
        "kinds": kinds,
        "edges": edges,
        "attribution": "© OpenStreetMap contributors (ODbL); elevation USGS 3DEP",
    }

    def write_geojson(name, feats):
        with open(os.path.join(out_dir, name), "w") as f:
            json.dump({"type": "FeatureCollection", "features": feats}, f, separators=(",", ":"))

    write_geojson("water.geojson", water)
    write_geojson("trails.geojson", trails)
    write_geojson("pois.geojson", pois)
    with gzip.open(os.path.join(out_dir, "routing-graph.json.gz"), "wt") as f:
        json.dump(graph, f, separators=(",", ":"))

    named = sorted({t["properties"]["name"] for t in trails if t["properties"]["name"]})
    stats = {
        "water_sources": len(water),
        "water_seasonal": sum(1 for w in water if w["properties"]["seasonal"]),
        "trail_ways": len(trails),
        "named_trails": len(named),
        "pois": len(pois),
        "graph_vertices": len(vertices),
        "graph_edges": len(edges),
        "graph_miles": round(sum(e[2] for e in edges), 1),
        "has_elevation": elev.ds is not None,
        "trail_names_sample": named[:25],
    }
    with open(os.path.join(out_dir, "osm-stats.json"), "w") as f:
        json.dump(stats, f, indent=2)
    return stats


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pbf", required=True, help="OSM extract clipped to the region")
    ap.add_argument("--bbox", required=True, help="west,south,east,north")
    ap.add_argument("--dem", help="Elevation GeoTIFF (metres) for climb and descent")
    ap.add_argument("--out", required=True, help="Output folder")
    args = ap.parse_args()
    bbox = [float(v) for v in args.bbox.split(",")]
    stats = build(args.pbf, bbox, args.dem, args.out)
    json.dump(stats, sys.stdout, indent=2)
    print()


if __name__ == "__main__":
    main()
