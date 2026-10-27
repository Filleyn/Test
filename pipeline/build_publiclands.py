#!/usr/bin/env python3
"""Public land layer from PAD-US (USGS Protected Areas Database of the United States).

Reads one state from the PAD-US 4.1 GeoParquet on source.coop with DuckDB, which
only downloads the parts it needs, and writes three tile layers:

  land       who manages each piece of land (Forest Service, BLM, state, city...)
             and whether the public can get in
  boundary   national forest, park, refuge and reservation boundaries, with names
  special    wilderness, wilderness study areas, national monuments and recreation areas
  labels     one name point per boundary and special area

Then tippecanoe turns them into publiclands.pmtiles.
"""
import argparse
import json
import os
import re
import subprocess

import duckdb

SOURCE = "https://data.source.coop/cboettig/padus/padus-4-1/combined.parquet"

BOUNDARY_TYPES = ("PROC", "TRIBL")
SPECIAL_TYPES = ("WA", "WSA", "NM", "NRA", "NCA")


def clean(name):
    return re.sub(r"\s+", " ", (name or "").strip()) or None


def geo_crs(con):
    """EPSG code of the GeoParquet geometry, from its metadata (4326 if not stated)."""
    try:
        meta = con.execute(f"SELECT decode(value) FROM parquet_kv_metadata('{SOURCE}') WHERE decode(key) = 'geo'").fetchone()
        geo = json.loads(meta[0])
        col = geo["columns"][geo.get("primary_column", "SHAPE")]
        crs = col.get("crs")
        if isinstance(crs, dict):
            ident = crs.get("id") or {}
            if ident.get("code"):
                return int(ident["code"])
    except Exception as e:  # noqa: BLE001
        print("Couldn't read CRS from metadata, assuming EPSG:4326:", e)
    return 4326


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--state", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True, help="publiclands.pmtiles path")
    ap.add_argument("--stats", required=True)
    ap.add_argument("--max-zoom", type=int, default=12)
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=True)

    con = duckdb.connect()
    for ext in ("httpfs", "spatial"):
        con.execute(f"INSTALL {ext}; LOAD {ext};")
    epsg = geo_crs(con)
    print("Source CRS: EPSG:", epsg)
    types = {r[0]: str(r[1]) for r in con.execute(f"DESCRIBE SELECT SHAPE FROM read_parquet('{SOURCE}')").fetchall()}
    geom = "SHAPE" if types.get("SHAPE", "").upper().startswith("GEOMETRY") else "ST_GeomFromWKB(SHAPE)"
    if epsg != 4326:
        geom = f"ST_Transform({geom}, 'EPSG:{epsg}', 'EPSG:4326', always_xy := true)"
    con.execute(f"""
        CREATE TABLE pad AS
        SELECT Category, Mang_Type, Mang_Name, Des_Tp, Unit_Nm, Pub_Access, GIS_Acres, {geom} AS g
        FROM read_parquet('{SOURCE}')
        WHERE State_Nm = '{a.state}'
          AND (Category = 'Fee'
               OR (Category = 'Proclamation' AND Des_Tp IN {BOUNDARY_TYPES})
               OR (Category = 'Designation' AND Des_Tp IN {SPECIAL_TYPES}))
    """)

    paths = {k: os.path.join(a.work, f"pad-{k}.geojsonl") for k in ("land", "boundary", "special", "labels")}
    files = {k: open(p, "w") for k, p in paths.items()}
    counts = {k: 0 for k in files}
    rows = con.execute("""
        SELECT Category, Mang_Type, Mang_Name, Des_Tp, Unit_Nm, Pub_Access, GIS_Acres,
               ST_AsGeoJSON(g), ST_AsGeoJSON(ST_PointOnSurface(g))
        FROM pad WHERE g IS NOT NULL AND NOT ST_IsEmpty(g)
    """).fetchall()
    for cat, mtype, mgr, des, name, access, acres, gj, pt in rows:
        props = {"mgr": mgr, "mgr_type": mtype, "des": des, "name": clean(name), "access": access,
                 "acres": round(acres or 0)}
        layer = "land" if cat == "Fee" else "boundary" if cat == "Proclamation" else "special"
        files[layer].write(json.dumps({"type": "Feature", "properties": props, "geometry": json.loads(gj)}) + "\n")
        counts[layer] += 1
        if layer != "land" and props["name"] and pt:
            files["labels"].write(json.dumps({"type": "Feature", "properties": {**props, "layer": layer},
                                              "geometry": json.loads(pt)}) + "\n")
            counts["labels"] += 1
    for f in files.values():
        f.close()

    stats = {"counts": counts}
    for field in ("Mang_Name", "Des_Tp", "Pub_Access"):
        stats[field] = dict(con.execute(f"SELECT {field}, COUNT(*) FROM pad GROUP BY 1 ORDER BY 2 DESC").fetchall())
    stats["wilderness"] = [r[0] for r in con.execute(
        "SELECT DISTINCT Unit_Nm FROM pad WHERE Des_Tp = 'WA' ORDER BY 1").fetchall()]
    stats["forests"] = [r[0] for r in con.execute(
        "SELECT DISTINCT Unit_Nm FROM pad WHERE Des_Tp = 'PROC' AND Mang_Name = 'USFS' ORDER BY 1").fetchall()]
    with open(a.stats, "w") as f:
        json.dump(stats, f, indent=2)
    print(json.dumps(counts))

    cmd = ["tippecanoe", "-o", a.out, "-Z5", f"-z{a.max_zoom}", "--force",
           "--detect-shared-borders", "--simplification=4", "--drop-smallest-as-needed",
           "--no-tile-size-limit", "--attribution=Public land: USGS PAD-US 4.1", "--name=TrailGuide public land"]
    for k, p in paths.items():
        cmd += ["-L", json.dumps({"file": p, "layer": k})]
    subprocess.run(cmd, check=True)


if __name__ == "__main__":
    main()
