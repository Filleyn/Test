#!/usr/bin/env python3
"""Small files for states you haven't downloaded, made from a build's GeoJSON:

  places.pmtiles   map pieces with trailheads, campsites, water and other place icons
                   (layers "pois" and "water"), plus an overview of named trails for
                   zoom 9-12 (layer "trails"), where the topo map doesn't draw footpaths yet.
                   The app streams these from the map server, a piece at a time.
  search.json.gz   every named place and trail with a few points each, so the app can
                   search a state without downloading it (fetched once, then kept).

  build_online.py --dir OUT --work WORK
"""
import argparse
import gzip
import json
import math
import os
import subprocess

# Place kinds the app draws as icons (MapStyle.PLACE_GROUPS); towns and peaks come from the topo map.
ICON_KINDS = {"campsite", "trailhead", "toilets", "waterfall", "lookout", "historic", "shelter",
              "viewpoint", "hot_spring", "pass", "saddle"}
# Properties the app reads when you tap a place.
KEEP = ("kind", "name", "seasonal", "derived", "osm_id", "last_edit", "ele_ft", "website", "acres", "place",
        "drinking_water")
# Extra details kept in the search file (the rest of a search result comes from name, kind and position).
SEARCH_EXTRA = ("osm_id", "seasonal", "place", "population", "acres", "ele_ft", "website", "last_edit")


def load(path):
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return json.load(f).get("features", [])


def haversine_m(lon1, lat1, lon2, lat2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371008.8 * math.asin(math.sqrt(a))


def point_feature(f, minzoom):
    props = {k: v for k, v in f["properties"].items() if k in KEEP and v not in (None, "", False)}
    return {"type": "Feature", "geometry": f["geometry"], "properties": props,
            "tippecanoe": {"minzoom": minzoom}}


def write_lines(path, feats):
    with open(path, "w") as out:
        for f in feats:
            out.write(json.dumps(f, separators=(",", ":")) + "\n")


def build_tiles(water, pois, trails, work, out_path):
    os.makedirs(work, exist_ok=True)
    w = []
    for f in water:
        if f["geometry"]["type"] != "Point":
            continue
        w.append(point_feature(f, 13 if f["properties"].get("kind") == "stream" else 10))
    p = []
    for f in pois:
        props = f["properties"]
        if f["geometry"]["type"] != "Point" or props.get("kind") not in ICON_KINDS:
            continue
        z = 13 if props["kind"] == "toilets" else 11 if props.get("derived") else 9
        p.append(point_feature(f, z))
    t = []
    for f in trails:
        props = f["properties"]
        if f["geometry"]["type"] != "LineString" or props.get("kind") != "trail" or not props.get("name"):
            continue
        # Long-distance and named hiking routes first, the rest a zoom later.
        z = 9 if props.get("routes") else 10
        t.append({"type": "Feature", "geometry": f["geometry"], "properties": {"name": props["name"]},
                  "tippecanoe": {"minzoom": z, "maxzoom": 12}})
    layers = []
    for name, feats in (("water", w), ("pois", p), ("trails", t)):
        path = os.path.join(work, f"{name}.geojsonl")
        write_lines(path, feats)
        layers.append((name, path))
    cmd = ["tippecanoe", "-o", out_path, "-Z9", "-z14", "--force", "-q", "-r1", "--no-feature-limit",
           "--no-tile-size-limit", "--simplification=6", "-P",
           "--attribution=© OpenStreetMap contributors", "--name=TrailGuide places"]
    for name, path in layers:
        cmd += ["-L", json.dumps({"file": path, "layer": name})]
    subprocess.run(cmd, check=True)
    return {"water": len(w), "pois": len(p), "trail_segments": len(t)}


def sample_line(coords, step_m=800.0):
    """A few points along a line: its ends and one about every [step_m]."""
    out = [coords[0]]
    run = 0.0
    for a, b in zip(coords, coords[1:]):
        run += haversine_m(a[0], a[1], b[0], b[1])
        if run >= step_m:
            out.append(b)
            run = 0.0
    if out[-1] is not coords[-1]:
        out.append(coords[-1])
    return out


def build_search(water, pois, trails, out_path):
    points = []
    for is_water, f in [(False, f) for f in pois] + [(True, f) for f in water]:
        props = f["properties"]
        name = props.get("name")
        if not name or f["geometry"]["type"] != "Point":
            continue
        lon, lat = f["geometry"]["coordinates"][:2]
        extra = {k: props[k] for k in SEARCH_EXTRA if props.get(k) not in (None, "", False)}
        if is_water:
            extra["water"] = True
        points.append([name, props.get("kind", ""), round(lat, 5), round(lon, 5)] + ([extra] if extra else []))
    by_name = {}
    for f in trails:
        props = f["properties"]
        name = props.get("name")
        if not name or f["geometry"]["type"] != "LineString":
            continue
        cells = by_name.setdefault(name, {})
        for lon, lat in sample_line(f["geometry"]["coordinates"]):
            # One point per ~1 km square is plenty to say where a trail is.
            cells.setdefault((math.floor(lat / 0.01), math.floor(lon / 0.01)), (round(lat, 5), round(lon, 5)))
    trail_rows = []
    for name, cells in sorted(by_name.items()):
        flat = []
        for lat, lon in cells.values():
            flat += [lat, lon]
        trail_rows.append([name, flat])
    data = json.dumps({"v": 1, "points": points, "trails": trail_rows}, separators=(",", ":")).encode()
    with open(out_path, "wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as gz:
            gz.write(data)
    return {"search_places": len(points), "search_trails": len(trail_rows),
            "search_mb": round(os.path.getsize(out_path) / 1e6, 2)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, help="Build output folder with water/pois/trails GeoJSON")
    ap.add_argument("--work", required=True)
    ap.add_argument("--no-tiles", action="store_true", help="Only the search file (for testing without tippecanoe)")
    a = ap.parse_args()
    water = load(os.path.join(a.dir, "water.geojson"))
    pois = load(os.path.join(a.dir, "pois.geojson"))
    trails = load(os.path.join(a.dir, "trails.geojson"))
    stats = build_search(water, pois, trails, os.path.join(a.dir, "search.json.gz"))
    if not a.no_tiles:
        stats.update(build_tiles(water, pois, trails, a.work, os.path.join(a.dir, "places.pmtiles")))
        stats["places_mb"] = round(os.path.getsize(os.path.join(a.dir, "places.pmtiles")) / 1e6, 1)
    print(json.dumps(stats))


if __name__ == "__main__":
    main()
