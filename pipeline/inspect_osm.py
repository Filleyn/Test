"""Look up how features are tagged in an OSM extract.

Prints every element whose name matches one of the patterns (with all its tags
and a rough centre), and counts how common POI tags appear as points versus
areas. Used to find out why something is missing from the app's maps.
"""
import argparse
import collections
import re

import osmium

COUNT_TAGS = [
    ("highway", "trailhead"), ("tourism", "camp_site"), ("amenity", "toilets"),
    ("amenity", "shelter"), ("tourism", "alpine_hut"), ("waterway", "waterfall"),
    ("tourism", "viewpoint"), ("natural", "spring"), ("natural", "hot_spring"),
]


class Inspector(osmium.SimpleHandler):
    def __init__(self, patterns, bbox):
        super().__init__()
        self.patterns = [re.compile(p, re.I) for p in patterns]
        self.bbox = bbox
        self.hits = []
        self.counts = collections.Counter()
        self.parking_th = collections.Counter()

    def inside(self, lon, lat):
        w, s, e, n = self.bbox
        return w <= lon <= e and s <= lat <= n

    def handle(self, kind, obj, lon, lat):
        tags = {t.k: t.v for t in obj.tags}
        if not tags:
            return
        for k, v in COUNT_TAGS:
            if tags.get(k) == v:
                self.counts[(f"{k}={v}", kind)] += 1
        name = tags.get("name", "")
        if tags.get("amenity") == "parking" and re.search(r"trail ?head|\bTH\b", name, re.I):
            self.parking_th[kind] += 1
        if lon is not None and self.inside(lon, lat) and any(p.search(name) for p in self.patterns):
            self.hits.append((kind, obj.id, round(lat, 5), round(lon, 5), tags))

    def node(self, n):
        if n.tags and n.location.valid():
            self.handle("node", n, n.location.lon, n.location.lat)

    def way(self, w):
        if not w.tags:
            return
        lons, lats = [], []
        for nd in w.nodes:
            if nd.location.valid():
                lons.append(nd.location.lon)
                lats.append(nd.location.lat)
        if lons:
            self.handle("way", w, sum(lons) / len(lons), sum(lats) / len(lats))
        else:
            self.handle("way", w, None, None)

    def relation(self, r):
        if r.tags:
            self.handle("relation", r, None, None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pbf", required=True)
    ap.add_argument("--bbox", required=True)
    ap.add_argument("--names", required=True, help="semicolon-separated regexes")
    a = ap.parse_args()
    h = Inspector([p for p in a.names.split(";") if p.strip()], [float(v) for v in a.bbox.split(",")])
    h.apply_file(a.pbf, locations=True, idx="flex_mem")
    print("## Points vs areas for place types\n")
    print("| Tag | as point | as area/line |\n|---|---|---|")
    for k, v in COUNT_TAGS:
        t = f"{k}={v}"
        print(f"| {t} | {h.counts[(t, 'node')]:,} | {h.counts[(t, 'way')] + h.counts[(t, 'relation')]:,} |")
    print(f"\nParking named like a trailhead: {h.parking_th['node']:,} points, {h.parking_th['way']:,} areas\n")
    print(f"## Name matches ({len(h.hits)})\n")
    for kind, oid, lat, lon, tags in h.hits[:400]:
        tagstr = ", ".join(f"{k}={v}" for k, v in sorted(tags.items()) if not k.startswith(("tiger:", "gnis:")))
        print(f"- {kind} {oid} ({lat}, {lon}): {tagstr}")


if __name__ == "__main__":
    main()
