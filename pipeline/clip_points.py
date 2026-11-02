#!/usr/bin/env python3
"""Drops features outside the state's outline from GeoJSON files, in place (water, places).

State extracts reach a little past the border, so without this a spring near the line
would show up twice with both states installed.
"""
import argparse
import json

import region_clip


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--clip", required=True)
    ap.add_argument("files", nargs="+")
    a = ap.parse_args()
    keep = region_clip.point_keeper(a.clip)
    for f in a.files:
        d = json.load(open(f))
        before = len(d["features"])
        d["features"] = [x for x in d["features"] if x.get("geometry") and keep(x["geometry"])]
        json.dump(d, open(f, "w"), separators=(",", ":"))
        print(f"{f}: kept {len(d['features'])} of {before}")


if __name__ == "__main__":
    main()
