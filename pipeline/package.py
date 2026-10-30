#!/usr/bin/env python3
"""Write manifest.json for a build and a short check report in Markdown.

The report answers the phase 1 gate questions: did every file get built, how
many tiles and features are in each, and was the test trail found?
"""
import argparse
import datetime
import hashlib
import json
import os

from pmtiles.reader import MmapSource, Reader

SOURCES = [
    {"name": "OpenStreetMap via Geofabrik", "license": "ODbL", "attribution": "© OpenStreetMap contributors"},
    {"name": "USGS 3DEP elevation", "license": "Public domain", "attribution": "USGS"},
    {"name": "Natural Earth, OSM water polygons (via Planetiler)", "license": "Public domain / ODbL"},
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def pmtiles_info(path):
    with open(path, "rb") as f:
        r = Reader(MmapSource(f))
        h = r.header()
        meta = r.metadata()
        layers = [l.get("id") for l in meta.get("vector_layers", [])] if isinstance(meta, dict) else []
        return {
            "min_zoom": h["min_zoom"], "max_zoom": h["max_zoom"],
            "tiles": h["addressed_tiles_count"],
            "type": str(h["tile_type"]).split(".")[-1],
            "layers": layers,
        }


def route_check(graph_path, trails_path, trail_name):
    """Routes between the two ends of the longest mapped piece of the test trail."""
    import routing_check as rc
    try:
        g = rc.load(graph_path)
        feats = [f for f in json.load(open(trails_path))["features"]
                 if trail_name.lower() in (f["properties"].get("name") or "").lower()]
        if not feats:
            return f"⚠️ Route check skipped: no trail named like \"{trail_name}\""
        coords = max(feats, key=lambda f: len(f["geometry"]["coordinates"]))["geometry"]["coordinates"]
        a = rc.snap(g, coords[0][1], coords[0][0])
        b = rc.snap(g, coords[-1][1], coords[-1][0])
        if not a or not b:
            return "❌ Route check: couldn't snap the test trail's ends to the network"
        r = rc.route(g, a, b)
        if not r:
            return "❌ Route check: no route found along the test trail"
        miles = r[0] / 1609.344
        straight = rc.dist_m(a[2], a[3], b[2], b[3]) / 1609.344
        return f"✅ Route check: {miles:.2f} mi along \"{trail_name}\" ({straight:.2f} mi straight line, {len(r[1])} segments)"
    except Exception as e:  # report, don't fail the build
        return f"❌ Route check failed: {e}"


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dir", required=True, help="Folder holding the built files")
    ap.add_argument("--region", required=True)
    ap.add_argument("--regions-file", required=True)
    ap.add_argument("--expect-trail", default="", help="Trail name to look for, e.g. Pinnacles")
    ap.add_argument("--summary", required=True, help="Where to write the Markdown report")
    ap.add_argument("--publiclands-stats", default="", help="Stats from build_publiclands.py, if it ran")
    a = ap.parse_args()

    region = json.load(open(a.regions_file))[a.region]
    stats_path = os.path.join(a.dir, "osm-stats.json")
    stats = json.load(open(stats_path)) if os.path.exists(stats_path) else {}

    files = []
    for name in sorted(os.listdir(a.dir)):
        path = os.path.join(a.dir, name)
        if name in ("manifest.json",) or not os.path.isfile(path):
            continue
        entry = {"name": name, "bytes": os.path.getsize(path), "sha256": sha256(path)}
        if name.endswith(".pmtiles"):
            entry["pmtiles"] = pmtiles_info(path)
        files.append(entry)

    built = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    manifest = {
        "app": "TrailGuide GPS",
        "format_version": 1,
        "region": a.region,
        "title": region["title"],
        "bbox": region["bbox"],
        "built_utc": built,
        "units": {"distance": "mi", "elevation": "ft"},
        "files": files,
        "stats": stats,
        "sources": SOURCES,
    }
    with open(os.path.join(a.dir, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)

    # Check report
    expected = ["topo.pmtiles", "contours.pmtiles", "terrain.pmtiles", "water.geojson",
                "trails.geojson", "pois.geojson", "routing.bin.gz"]
    have = {f["name"] for f in files}
    lines = [f"## Map build: {region['title']}", "", f"Built {built}", "", "### Files", "",
             "| File | Size | Detail |", "| --- | --- | --- |"]
    for f in files:
        detail = ""
        if "pmtiles" in f:
            p = f["pmtiles"]
            detail = f"{p['tiles']:,} tiles, zoom {p['min_zoom']}-{p['max_zoom']}"
            if p["layers"]:
                detail += f", layers: {', '.join(p['layers'])}"
        lines.append(f"| {f['name']} | {human(f['bytes'])} | {detail} |")
    missing = [n for n in expected if n not in have]

    lines += ["", "### Checks", ""]
    lines.append(f"- {'✅' if not missing else '❌'} All expected files built" + (f" (missing: {', '.join(missing)})" if missing else ""))
    if stats:
        lines.append(f"- Water sources: {stats.get('water_sources', 0):,} ({stats.get('water_seasonal', 0):,} marked seasonal), "
                     f"including {stats.get('stream_crossings', 0):,} trail and dirt-road stream crossings")
        lines.append(f"- Trail ways: {stats.get('trail_ways', 0)}, named trails: {stats.get('named_trails', 0)}")
        lines.append(f"- Trail network: {stats.get('graph_edges', 0):,} segments, {stats.get('graph_miles', 0):,} mi, {stats.get('graph_mb_unpacked', 0)} MB unpacked, elevation {'included' if stats.get('has_elevation') else 'MISSING'}")
        if a.expect_trail:
            trails = json.load(open(os.path.join(a.dir, "trails.geojson")))["features"] if "trails.geojson" in have else []
            found = any(a.expect_trail.lower() in (t["properties"].get("name") or "").lower() for t in trails)
            lines.append(f"- {'✅' if found else '⚠️'} Trail matching \"{a.expect_trail}\" {'found' if found else 'not found by name (it may be unnamed in OpenStreetMap)'}")
        if a.expect_trail and "routing.bin.gz" in have:
            lines.append("- " + route_check(os.path.join(a.dir, "routing.bin.gz"), os.path.join(a.dir, "trails.geojson"), a.expect_trail))
        if stats.get("graph_sac_miles"):
            lines.append("- Trail difficulty tags (SAC scale) on: " +
                         ", ".join(f"{k.replace('_', ' ')} {v:,} mi" for k, v in stats["graph_sac_miles"].items()))
        if stats.get("places_by_kind"):
            kinds = stats["places_by_kind"]
            lines.append("- Places: " + ", ".join(f"{k} {v:,}" for k, v in kinds.items()))
            if stats.get("places_added_from_areas"):
                lines.append("- Added from areas and trailhead parking lots: " +
                             ", ".join(f"{k} {v:,}" for k, v in sorted(stats["places_added_from_areas"].items())))
        for want in region.get("expect_places", []):
            pois = json.load(open(os.path.join(a.dir, "pois.geojson")))["features"] if "pois.geojson" in have else []
            hits = [p["properties"] for p in pois if want.lower() in (p["properties"].get("name") or "").lower()
                    and p["properties"].get("kind") in ("trailhead", "campsite")]
            shown = ", ".join(sorted({f"{h['name']} ({h['kind']})" for h in hits})[:8])
            lines.append(f"- {'✅' if hits else '⚠️'} Places named like \"{want}\": {shown or 'none'}")
        if stats.get("trail_names_sample"):
            lines.append(f"- Named trails seen: {', '.join(stats['trail_names_sample'])}")
    pl = json.load(open(a.publiclands_stats)) if a.publiclands_stats and os.path.exists(a.publiclands_stats) else None
    if pl and "publiclands.pmtiles" in have:
        c = pl["counts"]
        lines.append(f"- ✅ Public land: {c['land']:,} land areas, {c['boundary']:,} boundaries, {c['special']:,} wilderness and special areas")
        lines.append("- Managers: " + ", ".join(f"{k} {v:,}" for k, v in list(pl["Mang_Name"].items())[:12]))
        lines.append(f"- National forests: {', '.join(pl['forests'])}")
        lines.append(f"- Wilderness areas ({len(pl['wilderness'])}): {', '.join(pl['wilderness'][:12])}…")
    else:
        lines.append("- ⚠️ Public land layer not built (PAD-US source unavailable?); the app works without it")
    lines += ["", "Open the .pmtiles files at https://pmtiles.io to check them visually.", ""]
    with open(a.summary, "w") as f:
        f.write("\n".join(lines))
    print("\n".join(lines))
    if missing:
        raise SystemExit(f"Missing files: {missing}")


if __name__ == "__main__":
    main()
