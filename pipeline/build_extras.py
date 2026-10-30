#!/usr/bin/env python3
"""Extra map layers from outside sources, in one file (extras.pmtiles).

  mvum    Forest Service roads from the Motor Vehicle Use Maps: who can drive
          them (passenger car or high clearance), surface, and which months
  rough   OpenStreetMap roads tagged 4WD-only or very rough (outside the
          Forest Service data too, e.g. BLM land)
  burns   Wildfire perimeters since 2000 (national fire history), for
          standing dead trees, blowdown and washed-out trail

Each source is optional: if one can't be reached the others still build.
"""
import argparse
import json
import os
import re
import subprocess
import time
import urllib.parse
import urllib.request

MVUM = "https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_MVUM_02/MapServer/1"
BURNS = ("https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/"
         "InterAgencyFirePerimeterHistory_All_Years_View/FeatureServer/0")


def get_json(url, params, tries=4):
    q = urllib.parse.urlencode(params)
    for i in range(tries):
        try:
            req = urllib.request.Request(url + "/query", data=q.encode(), headers={"User-Agent": "TrailGuideGPS map build"})
            with urllib.request.urlopen(req, timeout=120) as r:
                d = json.load(r)
            if "error" in d:
                raise RuntimeError(d["error"])
            return d
        except Exception as e:  # noqa: BLE001
            if i == tries - 1:
                raise
            print("  retry:", e)
            time.sleep(3 * (i + 1))


def fetch_all(url, where, fields, bbox, simplify):
    """Every feature matching [where] inside [bbox], as GeoJSON features (in chunks of object ids)."""
    base = {"where": where, "geometry": ",".join(map(str, bbox)), "geometryType": "esriGeometryEnvelope",
            "inSR": 4326, "spatialRel": "esriSpatialRelIntersects"}
    ids = get_json(url, {**base, "returnIdsOnly": "true", "f": "json"}).get("objectIds") or []
    print(f"  {len(ids)} features")
    out = []
    for i in range(0, len(ids), 400):
        chunk = ids[i:i + 400]
        d = get_json(url, {"objectIds": ",".join(map(str, chunk)), "outFields": fields, "outSR": 4326,
                           "maxAllowableOffset": simplify, "f": "geojson"})
        out += d.get("features", [])
    return out


def level(s):
    m = re.match(r"\s*(\d)", s or "")
    return int(m.group(1)) if m else None


def mvum(bbox, path):
    feats = fetch_all(MVUM, "1=1", ",".join([
        "id", "name", "mvum_symbol_name", "operationalmaintlevel", "surfacetype", "seasonal",
        "passengervehicle", "passengervehicle_datesopen", "highclearancevehicle", "highclearancevehicle_datesopen",
        "forestname"]), bbox, 0.00005)
    n = 0
    with open(path, "w") as f:
        for ft in feats:
            p = ft.get("properties") or {}
            if not ft.get("geometry"):
                continue
            pc = (p.get("passengervehicle") or "").lower() == "open"
            hc = (p.get("highclearancevehicle") or "").lower() == "open"
            access = "car" if pc else "high" if hc else "ohv"
            props = {
                "id": p.get("id"), "name": (p.get("name") or "").strip() or None, "access": access,
                "level": level(p.get("operationalmaintlevel")), "surface": (p.get("surfacetype") or "").split(" - ")[-1].title() or None,
                "seasonal": (p.get("seasonal") or "").lower() == "seasonal",
                "car_dates": p.get("passengervehicle_datesopen") if pc else None,
                "high_dates": p.get("highclearancevehicle_datesopen") if hc else None,
                "symbol": p.get("mvum_symbol_name"), "forest": p.get("forestname"),
            }
            f.write(json.dumps({"type": "Feature", "properties": props, "geometry": ft["geometry"]}) + "\n")
            n += 1
    return n


def burns(bbox, path, since):
    feats = fetch_all(BURNS, f"FIRE_YEAR_INT >= {since}", "INCIDENT,FIRE_YEAR_INT,GIS_ACRES", bbox, 0.0003)
    n = 0
    best = {}
    for ft in feats:
        p = ft.get("properties") or {}
        if not ft.get("geometry"):
            continue
        name = (p.get("INCIDENT") or "").strip().title() or "Unnamed fire"
        year = p.get("FIRE_YEAR_INT")
        acres = p.get("GIS_ACRES") or 0
        if acres < 50:
            continue
        # The history has duplicates from different agencies; keep the biggest per fire and year.
        key = (name.lower(), year)
        if key not in best or acres > best[key][0]:
            best[key] = (acres, {"type": "Feature", "properties": {"name": name, "year": year, "acres": round(acres)}, "geometry": ft["geometry"]})
    with open(path, "w") as f:
        for _, feat in best.values():
            f.write(json.dumps(feat) + "\n")
            n += 1
    return n


def rough(pbf, bbox, path):
    import osmium
    bad = {"very_bad", "horrible", "very_horrible", "impassable"}
    n = 0
    with open(path, "w") as f:
        # Node locations have to be read for ways to have coordinates.
        for w in osmium.FileProcessor(pbf, osmium.osm.NODE | osmium.osm.WAY).with_locations():
            if not w.is_way():
                continue
            t = w.tags
            hw = t.get("highway")
            if hw not in ("track", "unclassified", "service", "tertiary"):
                continue
            why = None
            if t.get("4wd_only") == "yes":
                why = "4wd"
            elif t.get("smoothness") in bad:
                why = "very rough"
            elif t.get("tracktype") == "grade5":
                why = "grade 5"
            elif t.get("tracktype") == "grade4":
                why = "grade 4"
            if not why:
                continue
            pts = [[round(nd.lon, 6), round(nd.lat, 6)] for nd in w.nodes if nd.location.valid()]
            if len(pts) < 2 or not any(bbox[0] <= x <= bbox[2] and bbox[1] <= y <= bbox[3] for x, y in pts):
                continue
            f.write(json.dumps({"type": "Feature", "properties": {"why": why, "name": t.get("name") or t.get("ref"),
                                                                  "surface": t.get("surface")},
                                "geometry": {"type": "LineString", "coordinates": pts}}) + "\n")
            n += 1
    return n


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bbox", required=True)
    ap.add_argument("--pbf", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--stats", required=True)
    ap.add_argument("--burns-since", type=int, default=2000)
    a = ap.parse_args()
    bbox = [float(v) for v in a.bbox.split(",")]
    os.makedirs(a.work, exist_ok=True)
    stats, layers = {}, []
    for name, fn in (("mvum", lambda p: mvum(bbox, p)), ("burns", lambda p: burns(bbox, p, a.burns_since)),
                     ("rough", lambda p: rough(a.pbf, bbox, p))):
        path = os.path.join(a.work, f"{name}.geojsonl")
        print(name)
        try:
            stats[name] = fn(path)
            if stats[name]:
                layers.append((name, path))
        except Exception as e:  # noqa: BLE001
            print(f"  {name} skipped: {e}")
            stats[name] = f"skipped: {str(e)[:120]}"
    with open(a.stats, "w") as f:
        json.dump(stats, f, indent=2)
    print(json.dumps(stats))
    if not layers:
        raise SystemExit("No extra layers could be built")
    cmd = ["tippecanoe", "-o", a.out, "-Z6", "-z14", "--force", "--drop-densest-as-needed", "--simplification=4",
           "--no-tile-size-limit", "--attribution=USDA Forest Service MVUM, NIFC fire history, OpenStreetMap",
           "--name=TrailGuide extras"]
    for name, path in layers:
        cmd += ["-L", json.dumps({"file": path, "layer": name, **({"minzoom": 9} if name != "burns" else {})})]
    subprocess.run(cmd, check=True)


if __name__ == "__main__":
    main()
