#!/usr/bin/env python3
"""Build TrailGuide GPS's OSM-derived layers for one region.

Reads an OSM extract that is already clipped to the region (and ideally
tag-filtered, see the workflow), plus an optional elevation GeoTIFF, and writes:

  water.geojson          springs, drinking water, water points and wells
  trails.geojson         trails, paths and tracks with names, for offline search
  pois.geojson           peaks, trailheads, campsites, lakes, towns and more, for search
  routing.bin.gz         compact trail network the phone routes on (see routing_bin.py)

Lengths are in miles and elevations in feet, matching the app.
"""
import argparse
import gzip
import json
import math
import os
import re
import sys
from collections import Counter, defaultdict

import osmium

import routing_bin

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
# Statewide builds leave out town streets and paved through-roads to keep the
# routing graph small; forest roads (tracks, unclassified, service) stay in.
TOWN_ROADS = {"living_street", "residential", "tertiary", "secondary"}

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
    ("waterway", "waterfall", "waterfall"),
    ("natural", "hot_spring", "hot_spring"),
    ("natural", "saddle", "saddle"),
    ("mountain_pass", "yes", "pass"),
    ("tourism", "alpine_hut", "shelter"),
    ("amenity", "shelter", "shelter"),
    ("place", "city", "town"),
    ("place", "town", "town"),
    ("place", "village", "town"),
    ("place", "hamlet", "town"),
]


# Many trailheads are only mapped as a parking lot named "... Trailhead".
TRAILHEAD_NAME = re.compile(r"trail\s*head|\bTH\b", re.I)
# Kinds that are also mapped as areas (a campground outline, a restroom building).
AREA_POI_KINDS = {"trailhead", "campsite", "toilets", "shelter", "viewpoint", "waterfall", "hot_spring"}


def poi_kind(tags):
    """The app's place kind for these tags, or None."""
    kind = match_tag(tags, POI_TAGS)
    if kind == "shelter" and tags.get("shelter_type") in ("public_transport", "parking"):
        return None  # bus stops and carports, not trail shelters
    if kind:
        return kind
    if tags.get("amenity") == "parking" and TRAILHEAD_NAME.search(tags.get("name", "")):
        return "trailhead"
    return None


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


def simplify(coords, tol=0.00003):
    """Ramer-Douglas-Peucker on (lon, lat) pairs; tol in degrees (about 3 m)."""
    if len(coords) < 3:
        return coords
    keep = [False] * len(coords)
    keep[0] = keep[-1] = True
    stack = [(0, len(coords) - 1)]
    while stack:
        a, b = stack.pop()
        (x1, y1), (x2, y2) = coords[a], coords[b]
        dx, dy = x2 - x1, y2 - y1
        norm = math.hypot(dx, dy) or 1e-12
        best, idx = 0.0, None
        for i in range(a + 1, b):
            x0, y0 = coords[i]
            d = abs(dy * x0 - dx * y0 + x2 * y1 - y2 * x1) / norm
            if d > best:
                best, idx = d, i
        if idx is not None and best > tol:
            keep[idx] = True
            stack.append((a, idx))
            stack.append((idx, b))
    return [c for c, k in zip(coords, keep) if k]


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
    """Samples a DEM GeoTIFF in feet. Returns None everywhere if no DEM.

    The whole raster is read into memory when it fits (a statewide 1 arc-second
    DEM is about 2 GB as float32), which is far faster than per-point reads.
    """

    MAX_IN_MEMORY = 3_500_000_000

    def __init__(self, path):
        self.ds = None
        self.arr = None
        if path and os.path.exists(path):
            import rasterio
            from rasterio.warp import transform

            self.ds = rasterio.open(path)
            self._transform = transform
            self.nodata = self.ds.nodata
            self.is_geographic = self.ds.crs is None or self.ds.crs.is_geographic
            if self.ds.width * self.ds.height * 4 <= self.MAX_IN_MEMORY:
                self.arr = self.ds.read(1, out_dtype="float32")
                self.inv = ~self.ds.transform

    def _valid(self, val):
        if val != val:  # NaN
            return False
        if self.nodata is not None and val == self.nodata:
            return False
        return val > -1000

    def sample(self, coords):
        if self.ds is None or not coords:
            return [None] * len(coords)
        xs, ys = [c[0] for c in coords], [c[1] for c in coords]
        if not self.is_geographic:
            xs, ys = self._transform("EPSG:4326", self.ds.crs, xs, ys)
        out = []
        if self.arr is not None:
            h, w = self.arr.shape
            for x, y in zip(xs, ys):
                col, row = self.inv * (x, y)
                r, c = int(row), int(col)
                if 0 <= r < h and 0 <= c < w:
                    val = float(self.arr[r, c])
                    out.append(val * FT_PER_M if self._valid(val) else None)
                else:
                    out.append(None)
            return out
        for v in self.ds.sample(list(zip(xs, ys)), indexes=1):
            val = float(v[0])
            out.append(val * FT_PER_M if self._valid(val) else None)
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


def read_lakes(pbf, bbox, area_pois=None):
    """Named lakes and reservoirs as label points, from water areas (ways and multipolygons).

    Also collects places mapped as areas (campgrounds, trailhead parking, restroom
    buildings) into [area_pois] as points at their centre.
    """
    lakes = []
    seen = set()
    for obj in osmium.FileProcessor(pbf).with_areas():
        if not obj.is_area():
            continue
        tags = obj.tags
        if area_pois is not None:
            kind = poi_kind(tags)
            if kind in AREA_POI_KINDS:
                ring = max(obj.outer_rings(), key=lambda r: len(r), default=None)
                pts = [(n.lon, n.lat) for n in ring if n.location.valid()] if ring is not None else []
                if pts:
                    lon = sum(p[0] for p in pts) / len(pts)
                    lat = sum(p[1] for p in pts) / len(pts)
                    if in_bbox(lon, lat, bbox):
                        area_pois.append(feature("Point", [round(lon, 6), round(lat, 6)], {
                            "kind": kind, "name": tags.get("name"), "ele_ft": None,
                            "osm_id": f"{'w' if obj.from_way() else 'r'}{obj.orig_id()}",
                        }))
        if tags.get("natural") != "water" and tags.get("landuse") != "reservoir":
            continue
        name = tags.get("name")
        if not name:
            continue
        best, best_area = None, 0.0
        for ring in obj.outer_rings():
            pts = [(n.lon, n.lat) for n in ring if n.location.valid()]
            if len(pts) < 3:
                continue
            area = abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(pts, pts[1:] + pts[:1]))) / 2
            if area >= best_area:
                best, best_area = pts, area
        if not best:
            continue
        lon = sum(p[0] for p in best) / len(best)
        lat = sum(p[1] for p in best) / len(best)
        key = (name, round(lon, 3), round(lat, 3))
        if key in seen or not in_bbox(lon, lat, bbox):
            continue
        seen.add(key)
        water_kind = tags.get("water") or ("reservoir" if tags.get("landuse") == "reservoir" else "lake")
        acres = best_area * (111320 * math.cos(math.radians(lat))) * 110540 / 4046.86
        lakes.append(feature("Point", [round(lon, 6), round(lat, 6)], {
            "kind": "lake", "name": name, "water": water_kind, "acres": round(acres, 1),
            "osm_id": f"{'w' if obj.from_way() else 'r'}{obj.orig_id()}",
        }))
    return lakes


FOOT_TRAILS = {"path", "footway", "bridleway"}


def derive_trailheads(ways, pois, radius_m=400):
    """Adds a trailhead where a named footpath ends at a road, when none is mapped
    within [radius_m]. Many trailheads exist on the ground but not in OpenStreetMap.
    Returns how many were added."""
    # Ends of named foot trails.
    ends = {}
    for wi, (_, refs, coords, info) in enumerate(ways):
        if info["kind"] == "trail" and info["highway"] in FOOT_TRAILS and info["name"]:
            for ref, xy in ((refs[0], coords[0]), (refs[-1], coords[-1])):
                ends.setdefault(ref, (xy, info["name"]))
    # Which ways touch each of those ends.
    touching = defaultdict(list)
    for wi, (_, refs, _, _) in enumerate(ways):
        for r in refs:
            if r in ends:
                touching[r].append(wi)
    cell = 0.005
    grid = defaultdict(list)
    for p in pois:
        if p["properties"]["kind"] == "trailhead":
            lon, lat = p["geometry"]["coordinates"]
            grid[(int(lon / cell), int(lat / cell))].append((lon, lat))

    def near_existing(lon, lat):
        cx, cy = int(lon / cell), int(lat / cell)
        return any(haversine_m(lon, lat, x, y) < radius_m
                   for dx in (-1, 0, 1) for dy in (-1, 0, 1) for x, y in grid.get((cx + dx, cy + dy), ()))

    added = 0
    for ref in sorted(ends):
        (lon, lat), name = ends[ref]
        infos = [ways[wi][3] for wi in touching[ref]]
        continues = sum(1 for i in infos if i["name"] == name and i["kind"] == "trail") > 1
        meets_road = any(i["kind"] in ("track", "road") for i in infos)
        if continues or not meets_road or near_existing(lon, lat):
            continue
        pois.append(feature("Point", [round(lon, 6), round(lat, 6)], {
            "kind": "trailhead", "name": f"{name} trailhead", "ele_ft": None, "derived": True,
            "osm_id": f"n{ref}",
        }))
        grid[(int(lon / cell), int(lat / cell))].append((lon, lat))
        added += 1
    return added


def merge_area_pois(pois, area_pois, radius_m=150):
    """Adds area places unless the same kind is already mapped as a point close by
    (a trailhead sign next to its parking lot, say). Returns how many were added per kind."""
    cell = 0.002
    grid = defaultdict(list)
    for p in pois:
        lon, lat = p["geometry"]["coordinates"]
        grid[(p["properties"]["kind"], int(lon / cell), int(lat / cell))].append((lon, lat))
    added = defaultdict(int)
    for a in area_pois:
        lon, lat = a["geometry"]["coordinates"]
        kind = a["properties"]["kind"]
        cx, cy = int(lon / cell), int(lat / cell)
        near = any(
            haversine_m(lon, lat, x, y) < radius_m
            for dx in (-1, 0, 1) for dy in (-1, 0, 1)
            for x, y in grid.get((kind, cx + dx, cy + dy), ())
        )
        if near:
            continue
        pois.append(a)
        grid[(kind, cx, cy)].append((lon, lat))
        added[kind] += 1
    return dict(added)


def in_bbox(lon, lat, bbox):
    return bbox[0] <= lon <= bbox[2] and bbox[1] <= lat <= bbox[3]


def build(pbf, bbox, dem_path, out_dir, roads="all"):
    os.makedirs(out_dir, exist_ok=True)
    elev = Elevation(dem_path)
    way_routes = read_route_relations(pbf)

    water, pois, trails = [], [], []
    trail_ways = 0
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
            pkind = poi_kind(tags)
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
                props = {"kind": pkind, "name": tags.get("name"), "ele_ft": ele_ft, "osm_id": f"n{obj.id}"}
                if pkind == "town":
                    # Keep the size so the app can say "near Sisters" rather than a tiny hamlet.
                    props["place"] = tags.get("place")
                    try:
                        props["population"] = int(str(tags.get("population", "")).replace(",", "").split(";")[0])
                    except ValueError:
                        props["population"] = None
                pois.append(feature("Point", [round(lon, 6), round(lat, 6)], props))
        elif obj.is_way():
            hw = tags.get("highway")
            kind = ROUTABLE.get(hw)
            if not kind or not is_foot_allowed(tags):
                continue
            if roads == "minor" and hw in TOWN_ROADS:
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
                trail_ways += 1
            # The search index keeps named trails only; unnamed ones are still on the map tiles.
            if kind in TRAIL_KINDS and name:
                trails.append(feature("LineString", [[round(x, 6), round(y, 6)] for x, y in simplify(coords)], {
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
                    edges.append((
                        vid(refs[start], seg[0]), vid(refs[i], seg[-1]),
                        length_m, gain / FT_PER_M, loss / FT_PER_M,
                        name_index.get(nm, -1), kinds.index(info["kind"]),
                        simplify(seg),
                    ))
                start = i

    def write_geojson(name, feats):
        with open(os.path.join(out_dir, name), "w") as f:
            json.dump({"type": "FeatureCollection", "features": feats}, f, separators=(",", ":"))

    write_geojson("water.geojson", water)
    write_geojson("trails.geojson", trails)
    area_pois = []
    lakes = read_lakes(pbf, bbox, area_pois)
    added_areas = merge_area_pois(pois, area_pois)
    added_areas["trailhead (trail meets road)"] = derive_trailheads(ways, pois)
    pois.extend(lakes)
    write_geojson("pois.geojson", pois)
    graph_bytes = routing_bin.write(os.path.join(out_dir, "routing.bin.gz"), vertices, edges, names, bbox)

    named = sorted({t["properties"]["name"] for t in trails if t["properties"]["name"]})
    stats = {
        "water_sources": len(water),
        "water_seasonal": sum(1 for w in water if w["properties"]["seasonal"]),
        "trail_ways": trail_ways,
        "named_trail_ways": len(trails),
        "named_trails": len(named),
        "pois": len(pois),
        "lakes": len(lakes),
        "places_by_kind": dict(sorted(Counter(p["properties"]["kind"] for p in pois).items())),
        "places_added_from_areas": added_areas,
        "graph_vertices": len(vertices),
        "graph_edges": len(edges),
        "graph_miles": round(sum(e[2] for e in edges) * MI_PER_M, 1),
        "graph_mb_unpacked": round(graph_bytes / 1e6, 1),
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
    ap.add_argument("--roads", choices=["all", "minor"], default="all",
                    help="minor leaves town streets out of the routing graph")
    args = ap.parse_args()
    bbox = [float(v) for v in args.bbox.split(",")]
    stats = build(args.pbf, bbox, args.dem, args.out, args.roads)
    json.dump(stats, sys.stdout, indent=2)
    print()


if __name__ == "__main__":
    main()
